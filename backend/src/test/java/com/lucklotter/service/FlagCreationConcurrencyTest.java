package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that the budget ceiling actually holds when scans overlap
 * (FR-4, NFR-3) — the daily cron running into a manual trigger, say.
 *
 * <p>The ceiling is a count-then-insert over a rolling window, which cannot be
 * a counter column because old offers age out of it. Without a lock, every
 * concurrent caller reads the same count below the cap and every one of them
 * inserts, overshooting by one offer per concurrent run. Until now that
 * reasoning was recorded in a comment and nothing tested it: a test that runs
 * the callers sequentially passes whether or not the lock exists.
 *
 * <p>The pool is sized above the thread count deliberately. Each thread holds
 * its connection while it waits on the business row lock, so a pool smaller
 * than the fan-out would queue threads on connection acquisition instead and
 * quietly reduce the amount of genuine overlap the test produces.
 */
// Set as a property source rather than by re-declaring @DataJpaTest: doing that
// on the subclass shadows the base class's @AutoConfigureTestDatabase(replace =
// NONE), and Spring then tries to swap the Testcontainers datasource for an
// embedded database that isn't on the classpath.
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=24")
@Import({FlagCreationService.class, RedemptionCodeGenerator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlagCreationConcurrencyTest extends AbstractPostgresTest {

    private static final BigDecimal THRESHOLD_APPLIED = new BigDecimal("10.50");
    private static final int FAN_OUT = 16;
    private static final int CAP = 5;

    @Autowired
    private FlagCreationService flagCreation;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private OfferRepository offers;

    @AfterEach
    void cleanUp() {
        offers.deleteAllInBatch();
        flags.deleteAllInBatch();
        customers.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @Test
    @DisplayName("concurrent flag creations at the cap produce exactly cap offers")
    void theCapHoldsUnderConcurrency() throws Exception {
        Business business = business(CAP);

        flagAllAtOnce(customers(business, FAN_OUT));

        // The cap is what the business agreed to spend. One extra offer per
        // overlapping scan is a real cost, and it is unbounded in the number of
        // triggers, so "close enough" is not a passing result here.
        assertThat(committedOffers(business)).isEqualTo(CAP);
        // Every candidate still got an offer row; the rest are suppressed.
        // Derived rather than filtered, because this test has exactly one
        // business and reading offer.getBusiness() outside a transaction would
        // touch a lazy proxy.
        assertThat(offers.count() - committedOffers(business)).isEqualTo(FAN_OUT - CAP);
        // Every candidate is still flagged and still visible: the budget
        // running out is not a reason to hide a lapsing customer (FR-7).
        assertThat(flags.findAll()).hasSize(FAN_OUT);
    }

    @Test
    @DisplayName("each business gets its own cap, and neither waits on the other")
    void capsAreIndependentAcrossBusinesses() throws Exception {
        // Answers the open question of whether concurrent pilots interfere. The
        // lock is per business row, so two tenants scanning at the same time
        // neither share a budget nor serialize against each other.
        Business first = business(CAP);
        Business second = business(CAP);
        List<Candidate> candidates = new ArrayList<>(customers(first, FAN_OUT / 2));
        candidates.addAll(customers(second, FAN_OUT / 2));

        flagAllAtOnce(candidates);

        assertThat(committedOffers(first)).isEqualTo(Math.min(CAP, FAN_OUT / 2));
        assertThat(committedOffers(second)).isEqualTo(Math.min(CAP, FAN_OUT / 2));
    }

    // --- harness ------------------------------------------------------------

    /**
     * Releases every thread from a single latch so the calls genuinely overlap.
     * Submitting them and hoping is not enough — the early submissions would
     * often finish before the later ones start, and the test would pass on an
     * unlocked implementation.
     */
    private void flagAllAtOnce(List<Candidate> candidates) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (Candidate candidate : candidates) {
                results.add(pool.submit(() -> {
                    start.await();
                    flagCreation.flagAndGenerateOffer(
                            candidate.customerId(), candidate.businessId(), THRESHOLD_APPLIED);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                // get() rethrows: a lock timeout or deadlock must fail the test
                // rather than show up as a miscount with no explanation.
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long committedOffers(Business business) {
        return offers.countCommittedSince(
                business.getId(), Instant.now().minus(30, ChronoUnit.DAYS));
    }

    // --- fixtures -----------------------------------------------------------

    private Business business(int cap) {
        Business business = new Business();
        business.setName("Test Café");
        business.setDefaultDealValue(new BigDecimal("25.00"));
        business.setOfferCapPerWindow(cap);
        return businesses.save(business);
    }

    /** Ids only: the worker threads must not touch a detached entity's lazy fields. */
    private record Candidate(UUID customerId, UUID businessId) {
    }

    private List<Candidate> customers(Business business, int count) {
        List<Candidate> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Customer customer = new Customer();
            customer.setBusiness(business);
            customer.setExternalRef("cust-" + UUID.randomUUID());
            customer.setContactEmail("regular@example.com");
            customer.setTransactionCount(3);
            customer.setAvgIntervalDays(new BigDecimal("7.00"));
            customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
            created.add(new Candidate(customers.save(customer).getId(), business.getId()));
        }
        return created;
    }
}
