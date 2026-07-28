package com.lucklotter.config;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.DealType;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.service.IngestionService;
import com.lucklotter.web.dto.TransactionIngestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds a demo business, an admin login, and a transaction history that
 * produces flags on the first scan (G4, §13).
 *
 * <p>Off unless {@code lucklotter.seed.enabled=true}, and a no-op if any
 * business already exists, so it can't overwrite real pilot data.
 *
 * <p>Transactions go in through {@link IngestionService} rather than straight
 * SQL, so the seeded cadence is computed by the same code the API uses — demo
 * data can't drift from production behaviour.
 */
@Component
@ConditionalOnProperty(name = "lucklotter.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final BusinessRepository businesses;
    private final AdminUserRepository adminUsers;
    private final IngestionService ingestion;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public DemoDataSeeder(BusinessRepository businesses,
                          AdminUserRepository adminUsers,
                          IngestionService ingestion,
                          PasswordEncoder passwordEncoder,
                          @Value("${lucklotter.seed.admin-email}") String adminEmail,
                          @Value("${lucklotter.seed.admin-password}") String adminPassword) {
        this.businesses = businesses;
        this.adminUsers = adminUsers;
        this.ingestion = ingestion;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    /**
     * One seeded customer profile.
     *
     * @param visits      how many transactions to generate
     * @param cadenceDays the gap between them
     * @param quietDays   how long ago the most recent visit was
     */
    private record Profile(String ref, int visits, int cadenceDays, int quietDays,
                           String email, String phone) {
    }

    /**
     * Chosen to exercise every path the dashboard can show, not just the happy
     * one: customers who should flag, customers who shouldn't, and customers
     * who flag but cannot be contacted.
     */
    private static final Profile[] PROFILES = {
        // Weekly regulars gone quiet — threshold is 7 x 1.5 = 10.5 days, so
        // these flag.
        new Profile("POS-1001", 6, 7, 34, "aisha@example.test", "+254700000001"),
        new Profile("POS-1002", 8, 6, 41, "brian@example.test", null),
        new Profile("POS-1003", 5, 9, 45, null, "+254700000003"),
        // Fortnightly regular, quiet well past 14 x 1.5 = 21 days.
        new Profile("POS-1004", 6, 14, 52, "diana@example.test", "+254700000004"),
        // Flaggable, but no email and no phone: lands at NO_CONTACT (FR-5).
        new Profile("POS-1005", 5, 7, 30, null, null),
        new Profile("POS-1006", 4, 10, 38, null, null),
        // Still on cadence — must not flag.
        new Profile("POS-2001", 7, 7, 2, "eric@example.test", "+254700000011"),
        new Profile("POS-2002", 9, 5, 1, "faith@example.test", null),
        new Profile("POS-2003", 6, 12, 4, "grace@example.test", "+254700000013"),
        // Quiet, but below MIN_TRANSACTIONS: no cadence, so not flaggable (FR-2).
        new Profile("POS-3001", 2, 7, 60, "henry@example.test", null),
        new Profile("POS-3002", 1, 0, 90, "irene@example.test", "+254700000022"),
    };

    @Override
    public void run(String... args) {
        if (businesses.count() > 0) {
            log.info("Seed skipped: a business already exists");
            return;
        }

        Business business = new Business();
        business.setName("Kaldi's Coffee House");
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        businesses.save(business);

        AdminUser admin = new AdminUser();
        admin.setBusiness(business);
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        adminUsers.save(admin);

        Instant now = Instant.now();
        int transactionCount = 0;
        for (Profile profile : PROFILES) {
            for (int visit = 0; visit < profile.visits(); visit++) {
                // Oldest first, ending `quietDays` ago.
                int daysAgo = profile.quietDays()
                        + profile.cadenceDays() * (profile.visits() - 1 - visit);
                ingestion.ingest(business.getId(), new TransactionIngestRequest(
                        business.getId(),
                        profile.ref(),
                        "SEED-" + profile.ref() + "-" + visit,
                        new BigDecimal("450.00"),
                        now.minus(daysAgo, ChronoUnit.DAYS),
                        profile.email(),
                        profile.phone()));
                transactionCount++;
            }
        }

        log.info("Seeded demo data: businessId={} customers={} transactions={} adminEmail={}",
                business.getId(), PROFILES.length, transactionCount, adminEmail);
    }
}
