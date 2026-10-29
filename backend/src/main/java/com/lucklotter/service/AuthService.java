package com.lucklotter.service;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.security.JwtService;
import com.lucklotter.web.dto.ChangePasswordRequest;
import com.lucklotter.web.dto.LoginRequest;
import com.lucklotter.web.dto.LoginResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin login (FR-6). */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AdminUserRepository adminUsers;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AdminUserRepository adminUsers,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.adminUsers = adminUsers;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        AdminUser user = adminUsers.findByEmailIgnoreCaseAndActiveTrue(request.email())
                .orElse(null);

        // Same failure for "no such account" and "wrong password", and the hash
        // is still compared when the account is missing, so response timing
        // doesn't reveal which emails exist.
        boolean matches = user != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!matches) {
            if (user == null) {
                passwordEncoder.encode(request.password());
            }
            // The email is the credential being probed — logging it would put a
            // login-failure list of real addresses in the log file (NFR-4).
            log.warn("Failed login attempt");
            throw new BadCredentialsException("Invalid email or password");
        }

        JwtService.IssuedToken token = jwtService.issue(user);
        log.info("Admin logged in: adminUserId={} businessId={}",
                user.getId(), user.getBusiness().getId());
        return toResponse(user, token);
    }

    /**
     * Replaces the caller's own password and returns a fresh session.
     *
     * <p>Every token issued before this moment stops working (see
     * {@code password_changed_at}), including the one that made this call, so
     * the response carries a replacement and the client swaps it in without
     * making the admin sign in again.
     */
    @Transactional
    public LoginResponse changePassword(AdminPrincipal caller, ChangePasswordRequest request) {
        AdminUser user = adminUsers.findById(caller.adminUserId())
                .orElseThrow(() -> new NotFoundException("Account not found"));

        // A 400, not a 401: the session is fine, the form field is wrong, and a
        // 401 would sign the admin out of a perfectly good session.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("Password change refused: wrong current password adminUserId={}", user.getId());
            throw new ValidationException("Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ValidationException("New password must be different from the current one");
        }

        user.replacePassword(passwordEncoder.encode(request.newPassword()), false);
        log.info("Admin changed password: adminUserId={} businessId={}",
                user.getId(), user.getBusiness().getId());
        return toResponse(user, jwtService.issue(user));
    }

    private static LoginResponse toResponse(AdminUser user, JwtService.IssuedToken token) {
        return new LoginResponse(
                token.token(),
                token.expiresAt(),
                user.getBusiness().getId(),
                user.getBusiness().getName(),
                user.isMustChangePassword());
    }
}
