package com.lucklotter.config;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.service.FlagCreationService;
import com.lucklotter.service.IngestionService;
import com.lucklotter.service.OfferDispatchService;
import com.lucklotter.service.OfferSendService;
import com.lucklotter.service.RedemptionCodeGenerator;
import com.lucklotter.service.RetentionScanService;
import com.lucklotter.service.notify.NotificationSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo has to deliver its own mail (§13, FR-5).
 *
 * <p>The claim the demo makes is that the notification path is automatic:
 * customers go quiet, the system notices, and the offers go out with nobody
 * pressing anything. Until the seeder ran the scan, a freshly started demo sat
 * with an empty inbox until either the 02:00 schedule came round or an operator
 * clicked <em>Run scan now</em> — so the one claim worth showing was the one
 * thing the audience had to take on trust.
 *
 * <p>This drives the seeder directly rather than booting the application, so it
 * does not depend on the shared test database happening to be empty when the JVM
 * starts. The seeder is a no-op once any business exists, which would otherwise
 * make this pass or fail on test ordering.
 *
 * <p><strong>Deliberately not transactional</strong>, for the reason given on
 * {@code FlagCreationServiceTest}: the scan flags each customer in a
 * {@code REQUIRES_NEW} transaction, which would suspend the test transaction and
 * never see fixtures it had not committed.
 */
@Import({DemoSeedDispatchTest.SeederConfig.class, IngestionService.class,
        RetentionScanService.class, FlagCreationService.class, OfferDispatchService.class,
        OfferSendService.class, RedemptionCodeGenerator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeedDispatchTest extends AbstractPostgresTest {

    /** Quiet, flaggable, and reachable — these are the ones mail is for. */
    private static final Set<String> CONTACTABLE_AND_QUIET =
            Set.of("POS-1001", "POS-1002", "POS-1003", "POS-1004");

    /** Quiet and flaggable, but the till recorded no way to reach them. */
    private static final Set<String> UNCONTACTABLE = Set.of("POS-1005", "POS-1006");

    /** Still on cadence, or too few visits to have a cadence at all. */
    private static final Set<String> NOT_FLAGGABLE =
            Set.of("POS-2001", "POS-2002", "POS-2003", "POS-3001", "POS-3002");

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private RecordingSender sender;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private AdminUserRepository adminUsers;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private PosTransactionRepository transactions;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private OfferRepository offers;

    /**
     * Seeded once for the class rather than once per test: the fixture now
     * carries several hundred customers and a few thousand transactions, and
     * every test here only reads it. Paying for that seed five times over was
     * the bulk of this class's wall-clock.
     *
     * <p>The send log is cleared before seeding, not after. The context runs the
     * seeder once on its way up as well, so by the time this runs the recorder
     * already holds deliveries addressed to customers {@link #clearDatabase()}
     * is about to delete — and an assertion counting what this run handed over
     * would be counting those too.
     */
    @BeforeAll
    void seed() {
        clearDatabase();
        sender.delivered.clear();
        seeder.run();
    }

    @AfterAll
    void cleanUp() {
        clearDatabase();
    }

    @Test
    @DisplayName("seeding alone delivers the offers — no scheduled run, no button")
    void seedingDeliversOffers() {
        // Nothing between the seeder finishing and this assertion called the
        // scan endpoint or waited for 02:00.
        assertThat(refsHandedToSender()).containsAll(CONTACTABLE_AND_QUIET);

        for (String ref : CONTACTABLE_AND_QUIET) {
            Offer offer = currentOfferFor(ref);
            assertThat(offer.getStatus()).isEqualTo(OfferStatus.SENT);
            assertThat(offer.getSentAt()).isNotNull();
            assertThat(offer.getRedemptionCode()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a customer with no contact details is never handed to the sender")
    void uncontactableCustomersAreNotDispatched() {
        // NO_CONTACT is terminal: it means there is nothing to send to, so an
        // attempt would be a guaranteed failure dressed up as a retry.
        assertThat(refsHandedToSender()).doesNotContainAnyElementsOf(UNCONTACTABLE);

        for (String ref : UNCONTACTABLE) {
            Offer offer = currentOfferFor(ref);
            assertThat(offer.getStatus()).isEqualTo(OfferStatus.NO_CONTACT);
            assertThat(offer.getSentAt()).isNull();
            // The code is set when the offer is generated, not by a delivery
            // attempt — it says why nobody will ever try, not that a try failed.
            assertThat(offer.getFailureCode()).isEqualTo(OfferFailureCode.MISSING_CONTACT_DETAILS);
        }
    }

    @Test
    @DisplayName("customers still on cadence get no offer and no mail")
    void healthyCustomersAreLeftAlone() {
        // The demo is only honest if the trigger declines to fire as readily as
        // it fires — an inbox with everyone in it would prove nothing.
        assertThat(refsHandedToSender()).doesNotContainAnyElementsOf(NOT_FLAGGABLE);

        for (String ref : NOT_FLAGGABLE) {
            assertThat(flags.findByCustomerIdAndStatus(customerId(ref), FlagStatus.ACTIVE))
                    .as("%s should not be flagged", ref)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the fixture looks like a real book, not a handful of rows")
    void fixtureHasTheShapeOfARealBook() {
        // The point of §22's scale-up: percentages off a few dozen customers are
        // noise, and a book where every ticket cost the same amount makes the
        // revenue figures arithmetically true and visually meaningless.
        List<Customer> all = customers.findAll();
        assertThat(all).hasSizeGreaterThan(300);

        List<PosTransaction> ledger = transactions.findAll();
        assertThat(ledger).hasSizeGreaterThan(1000);
        assertThat(ledger.stream().map(PosTransaction::getAmount).distinct().count())
                .as("distinct ticket amounts")
                .isGreaterThan(50);

        List<RetentionFlag> allFlags = flags.findAll();
        long atRisk = allFlags.stream()
                .filter(flag -> flag.getStatus() == FlagStatus.ACTIVE)
                .count();
        assertThat(atRisk).as("customers the trigger flagged").isPositive();
        // Not everyone: the trigger has to decline as readily as it fires, or
        // there is nothing for the operator to triage.
        assertThat(atRisk).isLessThan(all.size() / 2);

        // Resolved history is what the retention report's recovery rate is
        // computed from, so a fixture without any would leave the headline
        // figure at zero.
        assertThat(allFlags).anyMatch(flag -> flag.getStatus() == FlagStatus.RESOLVED);
    }

    @Test
    @DisplayName("the backdated history is not re-sent")
    void historicalOffersAreNotRedelivered() {
        // The fixture's backdated offers are already SENT, and the dispatcher
        // reads PENDING and FAILED only. If that ever changed, a demo would open
        // by mailing hundreds of people about offers dated weeks ago.
        Instant justNow = Instant.now().minus(Duration.ofMinutes(5));
        List<Offer> sent = offers.findAll().stream()
                .filter(offer -> offer.getStatus() == OfferStatus.SENT)
                .toList();
        long backdated = sent.stream()
                .filter(offer -> offer.getSentAt().isBefore(justNow))
                .count();
        long stampedThisRun = sent.size() - backdated;

        // There has to be backdated history for this test to be about anything.
        assertThat(backdated).isPositive();
        // Everything the sender was handed this run is accounted for by the
        // offers stamped just now — so no backdated offer went through it.
        assertThat(stampedThisRun).isEqualTo(sender.delivered.size());
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Everything is looked up by ID rather than walked from an entity: with the
     * test transaction switched off there is no session open to initialise a
     * lazy association through.
     */
    private Set<String> refsHandedToSender() {
        return sender.delivered.stream()
                .map(customers::findById)
                .map(found -> found.orElseThrow().getExternalRef())
                .collect(Collectors.toSet());
    }

    private UUID customerId(String ref) {
        return customers.findByBusinessIdAndExternalRef(businessId(), ref)
                .orElseThrow(() -> new AssertionError("Seed produced no customer " + ref))
                .getId();
    }

    private UUID businessId() {
        return businesses.findAll().get(0).getId();
    }

    /** The offer on the customer's open flag — the one this scan just raised. */
    private Offer currentOfferFor(String ref) {
        UUID flagId = flags.findByCustomerIdAndStatus(customerId(ref), FlagStatus.ACTIVE)
                .orElseThrow(() -> new AssertionError("No open flag for " + ref))
                .getId();
        return offers.findByFlagId(flagId).orElseThrow();
    }

    private void clearDatabase() {
        offers.deleteAllInBatch();
        flags.deleteAllInBatch();
        transactions.deleteAllInBatch();
        customers.deleteAllInBatch();
        adminUsers.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    /**
     * Records who it was asked to contact instead of contacting them. Stands in
     * for the SMTP sender, which is the one part of this path that cannot run in
     * a test — what is under test here is which offers reach a sender at all.
     */
    static class RecordingSender implements NotificationSender {

        private final List<UUID> delivered = new CopyOnWriteArrayList<>();

        @Override
        public void send(Offer offer, Customer customer) {
            delivered.add(customer.getId());
        }
    }

    @TestConfiguration
    static class SeederConfig {

        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }

        @Bean
        @SuppressWarnings("deprecation")
        PasswordEncoder testPasswordEncoder() {
            // Hashing is not what this test is about, and BCrypt on the seeded
            // admin is pure wall-clock.
            return NoOpPasswordEncoder.getInstance();
        }

        @Bean
        DemoDataSeeder demoDataSeeder(BusinessRepository businesses,
                                      AdminUserRepository adminUsers,
                                      CustomerRepository customers,
                                      RetentionFlagRepository flags,
                                      OfferRepository offers,
                                      IngestionService ingestion,
                                      RetentionScanService retentionScan,
                                      RedemptionCodeGenerator redemptionCodes,
                                      PasswordEncoder passwordEncoder) {
            return new DemoDataSeeder(businesses, adminUsers, customers, flags, offers,
                    ingestion, retentionScan, redemptionCodes, passwordEncoder,
                    "seed-admin@lucklotter.test", "seed-password", "cafe-restaurant");
        }
    }
}
