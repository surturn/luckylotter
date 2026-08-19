package com.lucklotter.config;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
class DemoSeedDispatchTest extends AbstractPostgresTest {

    /** Quiet, flaggable, and reachable — these are the ones mail is for. */
    private static final Set<String> CONTACTABLE_AND_QUIET =
            Set.of("POS-1001", "POS-1002", "POS-1003", "POS-1004");

    /** Quiet and flaggable, but the till recorded no way to reach them. */
    private static final Set<String> UNCONTACTABLE = Set.of("POS-1005", "POS-1006");

    /** The eight weeks of backdated fixture offers, all already delivered. */
    private static final int HISTORICAL_OFFERS = 15;

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

    @BeforeEach
    void seed() {
        clearDatabase();
        sender.delivered.clear();
        seeder.run();
    }

    @AfterEach
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
    @DisplayName("the backdated history is not re-sent")
    void historicalOffersAreNotRedelivered() {
        // The eight weeks of fixture offers are already SENT, and the dispatcher
        // reads PENDING and FAILED only. If that ever changed, a demo would open
        // by mailing fifteen people about offers dated two months ago.
        Instant justNow = Instant.now().minus(Duration.ofMinutes(5));
        List<Offer> sent = offers.findAll().stream()
                .filter(offer -> offer.getStatus() == OfferStatus.SENT)
                .toList();
        List<Offer> backdated = sent.stream()
                .filter(offer -> offer.getSentAt().isBefore(justNow))
                .toList();

        assertThat(backdated).hasSize(HISTORICAL_OFFERS);
        // Everything the sender was handed this run is accounted for by the
        // offers stamped just now — so no backdated offer went through it.
        assertThat(sent).hasSize(HISTORICAL_OFFERS + sender.delivered.size());
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
                    "seed-admin@lucklotter.test", "seed-password");
        }
    }
}
