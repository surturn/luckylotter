package com.lucklotter.config;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.service.IngestionService;
import com.lucklotter.service.RedemptionCodeGenerator;
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
import java.util.UUID;

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
 *
 * <h2>On the backdated history</h2>
 *
 * Part two writes flags and offers with timestamps spread over the previous
 * eight weeks. That is legitimate precisely because every customer here is
 * invented: the whole dataset is a fixture, so a fixture flag is no more a
 * fabrication than a fixture transaction. What would <em>not</em> be
 * legitimate is backdating a flag for a real customer, or drawing a chart from
 * numbers the system never recorded.
 *
 * <p>Without it the overview shows one bar and a recovery rate off six flags,
 * which says nothing about whether the mechanism works over time.
 */
@Component
@ConditionalOnProperty(name = "lucklotter.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final BusinessRepository businesses;
    private final AdminUserRepository adminUsers;
    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;
    private final IngestionService ingestion;
    private final RedemptionCodeGenerator redemptionCodes;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public DemoDataSeeder(BusinessRepository businesses,
                          AdminUserRepository adminUsers,
                          CustomerRepository customers,
                          RetentionFlagRepository flags,
                          OfferRepository offers,
                          IngestionService ingestion,
                          RedemptionCodeGenerator redemptionCodes,
                          PasswordEncoder passwordEncoder,
                          @Value("${lucklotter.seed.admin-email}") String adminEmail,
                          @Value("${lucklotter.seed.admin-password}") String adminPassword) {
        this.businesses = businesses;
        this.adminUsers = adminUsers;
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
        this.ingestion = ingestion;
        this.redemptionCodes = redemptionCodes;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    /**
     * A customer who is quiet right now and will flag on the next scan.
     *
     * @param visits      how many transactions to generate
     * @param cadenceDays the gap between them
     * @param quietDays   how long ago the most recent visit was
     */
    private record Profile(String ref, String name, int visits, int cadenceDays, int quietDays,
                           String email, String phone) {
    }

    /**
     * A customer who was flagged in the past. {@code recoveredAfterDays} of
     * null means they never came back, so their flag is still open.
     */
    private record History(String ref, String name, int cadenceDays, int flaggedWeeksAgo,
                           Integer recoveredAfterDays, String email, String phone) {
    }

    /**
     * Chosen to exercise every path the dashboard can show, not just the happy
     * one: customers who should flag, customers who shouldn't, and customers
     * who flag but cannot be contacted.
     */
    private static final Profile[] PROFILES = {
        // Weekly regulars gone quiet — threshold is 7 x 1.5 = 10.5 days, so
        // these flag.
        new Profile("POS-1001", "Aisha Njeri", 6, 7, 34, "aisha@example.test", "+254700000001"),
        new Profile("POS-1002", "Brian Otieno", 8, 6, 41, "brian@example.test", null),
        new Profile("POS-1003", "Sheila Atieno", 5, 9, 45, null, "+254700000003"),
        // Fortnightly regular, quiet well past 14 x 1.5 = 21 days.
        new Profile("POS-1004", "Diana Kamau", 6, 14, 52, "diana@example.test", "+254700000004"),
        // Flaggable, but no email and no phone: lands at NO_CONTACT (FR-5). Also
        // left deliberately unnamed — a till that recorded nothing but a
        // reference is exactly the row that has no way to reach anyone, and it
        // keeps the dashboard's no-name path and the offer email's no-name
        // greeting fallback both covered by the demo data.
        new Profile("POS-1005", null, 5, 7, 30, null, null),
        new Profile("POS-1006", null, 4, 10, 38, null, null),
        // Still on cadence — must not flag.
        new Profile("POS-2001", "Eric Wanjiru", 7, 7, 2, "eric@example.test", "+254700000011"),
        new Profile("POS-2002", "Faith Mumbi", 9, 5, 1, "faith@example.test", null),
        new Profile("POS-2003", "Grace Wairimu", 6, 12, 4, "grace@example.test", "+254700000013"),
        // Quiet, but below MIN_TRANSACTIONS: no cadence, so not flaggable (FR-2).
        new Profile("POS-3001", "Henry Kiprop", 2, 7, 60, "henry@example.test", null),
        new Profile("POS-3002", "Irene Chebet", 1, 0, 90, "irene@example.test", "+254700000022"),
    };

    /** Eight weeks of prior activity, most of whom came back. */
    private static final History[] HISTORY = {
        new History("POS-4001", "James Mwangi", 7, 8, 6, "james@example.test", "+254700000101"),
        new History("POS-4002", "Karen Wekesa", 6, 8, 9, "karen@example.test", null),
        new History("POS-4003", "Lucy Kimani", 10, 7, 5, "lucy@example.test", "+254700000103"),
        new History("POS-4004", "Martin Langat", 7, 7, null, "martin@example.test", null),
        new History("POS-4005", "Nadia Achieng", 5, 6, 4, "nadia@example.test", "+254700000105"),
        new History("POS-4006", "Oscar Kiprono", 9, 6, 12, "oscar@example.test", null),
        new History("POS-4007", "Paul Mwangi", 7, 5, 7, "paul@example.test", "+254700000107"),
        new History("POS-4008", "Quincy Barasa", 12, 5, null, "quincy@example.test", null),
        new History("POS-4009", "Rita Nyambura", 6, 4, 8, "rita@example.test", "+254700000109"),
        new History("POS-4010", "Samuel Rotich", 8, 4, 6, "samuel@example.test", null),
        new History("POS-4011", "Tanya Wafula", 7, 3, 5, "tanya@example.test", "+254700000111"),
        new History("POS-4012", "Umar Hassan", 11, 3, null, "umar@example.test", null),
        new History("POS-4013", "Vera Chelimo", 6, 2, 9, "vera@example.test", "+254700000113"),
        new History("POS-4014", "Wesley Ochieng", 9, 2, 7, "wesley@example.test", null),
        new History("POS-4015", "Xena Muthoni", 7, 1, 4, "xena@example.test", "+254700000115"),
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
        int transactionCount = seedCurrentCustomers(business, now);
        transactionCount += seedHistory(business, now);

        // The admin's ID, not their address: the seed email comes from config and
        // in a non-demo deploy that is a real operator's inbox (NFR-4).
        log.info("Seeded demo data: businessId={} customers={} transactions={} historicalFlags={} adminUserId={}",
                business.getId(), PROFILES.length + HISTORY.length, transactionCount,
                HISTORY.length, admin.getId());
    }

    /** Customers whose current state the next scan will act on. */
    private int seedCurrentCustomers(Business business, Instant now) {
        int count = 0;
        for (Profile profile : PROFILES) {
            for (int visit = 0; visit < profile.visits(); visit++) {
                // Oldest first, ending `quietDays` ago.
                int daysAgo = profile.quietDays()
                        + profile.cadenceDays() * (profile.visits() - 1 - visit);
                ingest(business, profile.ref(), profile.name(),
                        "SEED-" + profile.ref() + "-" + visit,
                        now.minus(daysAgo, ChronoUnit.DAYS), profile.email(), profile.phone());
                count++;
            }
        }
        return count;
    }

    /**
     * Customers flagged in previous weeks, most of whom returned.
     *
     * <p>Visits are ingested first so the flag is written against a customer
     * who already has cadence — and so ingestion has no open flag to
     * auto-resolve, leaving the historical {@code resolved_at} intact rather
     * than stamping it with the current time.
     */
    private int seedHistory(Business business, Instant now) {
        int count = 0;
        for (History entry : HISTORY) {
            Instant flaggedAt = now.minus(entry.flaggedWeeksAgo() * 7L, ChronoUnit.DAYS);
            // Six visits on cadence, finishing just over threshold before the flag.
            int visits = 6;
            BigDecimal cadence = BigDecimal.valueOf(entry.cadenceDays());
            Instant lastVisitBeforeFlag = flaggedAt.minus(
                    (long) Math.ceil(entry.cadenceDays() * 1.5) + 1, ChronoUnit.DAYS);

            for (int visit = visits - 1; visit >= 0; visit--) {
                ingest(business, entry.ref(), entry.name(),
                        "SEED-H-" + entry.ref() + "-" + visit,
                        lastVisitBeforeFlag.minus(entry.cadenceDays() * (long) visit, ChronoUnit.DAYS),
                        entry.email(), entry.phone());
                count++;
            }

            if (entry.recoveredAfterDays() != null) {
                // The return visit itself, before the flag exists.
                ingest(business, entry.ref(), entry.name(),
                        "SEED-H-" + entry.ref() + "-return",
                        flaggedAt.plus(entry.recoveredAfterDays(), ChronoUnit.DAYS),
                        entry.email(), entry.phone());
                count++;
            }

            Customer customer = customers
                    .findByBusinessIdAndExternalRef(business.getId(), entry.ref())
                    .orElseThrow();
            writeHistoricalFlag(business, customer, entry, flaggedAt, cadence);
        }
        return count;
    }

    private void writeHistoricalFlag(Business business, Customer customer, History entry,
                                     Instant flaggedAt, BigDecimal cadence) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setFlaggedAt(flaggedAt);
        flag.setAvgIntervalDaysAtFlag(cadence);
        flag.setThresholdDaysApplied(business.thresholdDaysFor(cadence));
        if (entry.recoveredAfterDays() != null) {
            flag.resolve(flaggedAt.plus(entry.recoveredAfterDays(), ChronoUnit.DAYS));
        }
        flags.save(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode(redemptionCodes.generate());
        offer.setCreatedAt(flaggedAt);
        offer.setUpdatedAt(flaggedAt);
        // Historical offers were delivered at the time; the dispatcher only
        // picks up PENDING and FAILED, so these stay untouched by later runs.
        offer.markSent(flaggedAt.plus(2, ChronoUnit.MINUTES));
        offers.save(offer);
    }

    /**
     * @param customerName null for a couple of profiles on purpose. A POS export
     *                     that carries only a reference is a real shape of data,
     *                     and keeping some in the demo means the dashboard's
     *                     no-name path and the offer email's greeting fallback
     *                     are both exercised rather than just described.
     */
    private void ingest(Business business, String customerRef, String customerName,
                        String externalTxnId, Instant occurredAt, String email, String phone) {
        ingestion.ingest(business.getId(), new TransactionIngestRequest(
                business.getId(), customerRef, customerName, null, externalTxnId,
                new BigDecimal("450.00"), occurredAt, email, phone));
    }
}
