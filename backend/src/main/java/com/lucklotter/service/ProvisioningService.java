package com.lucklotter.service;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.BusinessType;
import com.lucklotter.domain.DealType;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Operator-side account management: creating a business with its first admin,
 * resetting a password, deactivating an admin.
 *
 * <p>Reached only from the operator command line, never over HTTP. A
 * provisioning endpoint would need a platform-admin role that sits above every
 * tenant, which is a new way to cross the tenant boundary for the sake of a
 * task the operator does a few times a year.
 *
 * <p>Temporary passwords are returned to the caller and are never logged.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    // No 0/O, 1/I/L: a password read down the phone to a client survives being
    // typed.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
    private static final int PASSWORD_GROUPS = 4;
    private static final int GROUP_LENGTH = 4;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final BusinessRepository businesses;
    private final AdminUserRepository adminUsers;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public ProvisioningService(BusinessRepository businesses,
                               AdminUserRepository adminUsers,
                               PasswordEncoder passwordEncoder) {
        this.businesses = businesses;
        this.adminUsers = adminUsers;
        this.passwordEncoder = passwordEncoder;
    }

    public record NewBusiness(String name,
                              BusinessType businessType,
                              String adminEmail,
                              String currencyCode,
                              String timezone,
                              DealType dealType,
                              BigDecimal dealValue,
                              String replyToEmail) {
    }

    public record Provisioned(UUID businessId, UUID adminUserId, String adminEmail, String temporaryPassword) {
    }

    public record Reset(UUID adminUserId, String adminEmail, String temporaryPassword) {
    }

    @Transactional
    public Provisioned createBusiness(NewBusiness request) {
        String name = required(request.name(), "name");
        if (request.businessType() == null) {
            throw new ValidationException("type is required: one of " + validTypes());
        }
        String adminEmail = email(request.adminEmail(), "admin-email");
        String currency = request.currencyCode() == null ? "" : request.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new ValidationException("currency must be a three-letter ISO 4217 code, e.g. KES");
        }
        String timezone = required(request.timezone(), "timezone");
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new ValidationException("timezone must be an IANA zone id, e.g. Africa/Nairobi");
        }
        if (request.dealValue() == null || request.dealValue().signum() <= 0) {
            throw new ValidationException("deal-value must be greater than zero");
        }
        String replyTo = request.replyToEmail() == null || request.replyToEmail().isBlank()
                ? null : email(request.replyToEmail(), "reply-to");

        // Checked ignoring case and including deactivated accounts: the column's
        // UNIQUE is case-sensitive, so the database alone would let
        // "Owner@x.com" and "owner@x.com" both in, and login matches either.
        if (adminUsers.findByEmailIgnoreCase(adminEmail).isPresent()) {
            throw new ValidationException("An admin with that email already exists");
        }

        Business business = new Business();
        business.setName(name);
        business.setCurrencyCode(currency);
        business.setTimezone(timezone);
        // The type's starting settings first, then the operator's explicit choices
        // on top: a deal type given on the command line beats the preset's.
        business.applyPreset(request.businessType());
        if (request.dealType() != null) {
            business.setDefaultDealType(request.dealType());
        }
        business.setDefaultDealValue(request.dealValue());
        business.setReplyToEmail(replyTo);
        businesses.save(business);

        String temporary = generatePassword();
        AdminUser admin = new AdminUser();
        admin.setBusiness(business);
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(temporary));
        admin.setMustChangePassword(true);
        adminUsers.save(admin);

        log.info("Business provisioned: businessId={} adminUserId={}", business.getId(), admin.getId());
        return new Provisioned(business.getId(), admin.getId(), adminEmail, temporary);
    }

    /** Issues a new temporary password, ending every existing session for the account. */
    @Transactional
    public Reset resetPassword(String adminEmail) {
        AdminUser admin = adminUsers.findByEmailIgnoreCase(email(adminEmail, "email"))
                .orElseThrow(() -> new ValidationException("No admin with that email"));
        String temporary = generatePassword();
        admin.replacePassword(passwordEncoder.encode(temporary), true);
        log.info("Admin password reset by operator: adminUserId={}", admin.getId());
        return new Reset(admin.getId(), admin.getEmail(), temporary);
    }

    /** The account stops working on its very next request; see the JWT filter. */
    @Transactional
    public UUID deactivateAdmin(String adminEmail) {
        AdminUser admin = adminUsers.findByEmailIgnoreCase(email(adminEmail, "email"))
                .orElseThrow(() -> new ValidationException("No admin with that email"));
        admin.setActive(false);
        log.info("Admin deactivated by operator: adminUserId={}", admin.getId());
        return admin.getId();
    }

    /** For the error message: what the operator may pass to --type. */
    public static String validTypes() {
        return Arrays.stream(BusinessType.values())
                .map(type -> type.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .collect(Collectors.joining(", "));
    }

    private String generatePassword() {
        StringBuilder out = new StringBuilder();
        for (int group = 0; group < PASSWORD_GROUPS; group++) {
            if (group > 0) {
                out.append('-');
            }
            for (int i = 0; i < GROUP_LENGTH; i++) {
                out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return out.toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        return value.trim();
    }

    private static String email(String value, String field) {
        String trimmed = required(value, field);
        if (!EMAIL.matcher(trimmed).matches() || trimmed.length() > 255) {
            throw new ValidationException(field + " must be a valid email address");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
