package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.repo.CustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the one-off cadence rebuild (FR-2) — the correction that reaches
 * customers who have not transacted since the visit-collapse fix.
 *
 * <p>The case that matters is the one that motivated the endpoint: a customer
 * carrying a pre-fix average computed from receipt rows rather than trips. It is
 * invisible in the dashboard, because a wrong average looks exactly like a right
 * one, and it feeds the flag threshold directly.
 */
@Import(CadenceRebuildService.class)
class CadenceRebuildServiceTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private CadenceRebuildService rebuild;

    @Autowired
    private CustomerRepository customers;

    @Test
    @DisplayName("a stale row-derived cadence is rebuilt from collapsed visits")
    void staleCadenceIsCorrected() {
        Business business = business();
        Customer customer = customer(business);
        // Three weekly visits, each rung up as three receipts minutes apart.
        Instant firstVisit = Instant.now().minus(21, ChronoUnit.DAYS);
        for (int week = 0; week < 3; week++) {
            Instant visit = firstVisit.plus(week * 7L, ChronoUnit.DAYS);
            for (int receipt = 0; receipt < 3; receipt++) {
                transaction(business, customer, visit.plus(receipt * 4L, ChronoUnit.MINUTES));
            }
        }
        // What the pre-fix calculation stored: 9 rows across the same 14-day
        // span read as a 1.75-day rhythm, so this weekly regular's threshold
        // collapses to the 3-day floor and three quiet days flags them.
        customer.setAvgIntervalDays(new BigDecimal("1.75"));
        customer.setTransactionCount(9);
        em.flush();
        em.clear();

        CadenceRebuildService.RebuildSummary summary = rebuild.rebuild(business.getId());

        Customer rebuilt = customers.findById(customer.getId()).orElseThrow();
        // Three visits, first to last is 14 days over two gaps: 7.00 a visit,
        // which is the weekly rhythm the receipts were hiding.
        assertThat(rebuilt.getAvgIntervalDays()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(summary.cadenceChanged()).isEqualTo(1);
        assertThat(summary.examined()).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer whose visits collapse below the minimum loses their cadence")
    void collapsingBelowTheMinimumRemovesFlaggability() {
        // The direction the fix moves people in: three receipts from one trip is
        // no rhythm at all, and this customer was previously flaggable on it.
        Business business = business();
        Customer customer = customer(business);
        Instant visit = Instant.now().minus(10, ChronoUnit.DAYS);
        for (int receipt = 0; receipt < 3; receipt++) {
            transaction(business, customer, visit.plus(receipt * 5L, ChronoUnit.MINUTES));
        }
        customer.setAvgIntervalDays(new BigDecimal("0.01"));
        customer.setTransactionCount(3);
        em.flush();
        em.clear();

        CadenceRebuildService.RebuildSummary summary = rebuild.rebuild(business.getId());

        Customer rebuilt = customers.findById(customer.getId()).orElseThrow();
        assertThat(rebuilt.getAvgIntervalDays()).isNull();
        assertThat(rebuilt.hasEstablishedCadence()).isFalse();
        assertThat(summary.becameUnflaggable()).isEqualTo(1);
    }

    @Test
    @DisplayName("first and last visit are rebuilt from the whole history")
    void visitBoundsAreRebuilt() {
        // Ingestion only moves lastVisitAt forward, so a backdated import can
        // leave firstSeenAt later than the earliest transaction on file.
        Business business = business();
        Customer customer = customer(business);
        Instant earliest = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant latest = Instant.now().minus(2, ChronoUnit.DAYS);
        transaction(business, customer, earliest);
        transaction(business, customer, Instant.now().minus(16, ChronoUnit.DAYS));
        transaction(business, customer, latest);
        customer.setFirstSeenAt(Instant.now().minus(20, ChronoUnit.DAYS));
        em.flush();
        em.clear();

        rebuild.rebuild(business.getId());

        Customer rebuilt = customers.findById(customer.getId()).orElseThrow();
        assertThat(rebuilt.getFirstSeenAt()).isCloseTo(earliest, within(1_000));
        assertThat(rebuilt.getLastVisitAt()).isCloseTo(latest, within(1_000));
    }

    @Test
    @DisplayName("a customer with no transactions is left alone, not zeroed")
    void customersWithoutHistoryAreUntouched() {
        Business business = business();
        Customer customer = customer(business);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setTransactionCount(4);
        em.flush();
        em.clear();

        CadenceRebuildService.RebuildSummary summary = rebuild.rebuild(business.getId());

        Customer untouched = customers.findById(customer.getId()).orElseThrow();
        assertThat(untouched.getAvgIntervalDays()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(summary.cadenceChanged()).isZero();
    }

    @Test
    @DisplayName("another business's customers are not rebuilt")
    void scopedToTheBusiness() {
        Business business = business();
        Business other = business();
        Customer theirs = customer(other);
        theirs.setAvgIntervalDays(new BigDecimal("1.00"));
        transaction(other, theirs, Instant.now().minus(9, ChronoUnit.DAYS));
        transaction(other, theirs, Instant.now().minus(6, ChronoUnit.DAYS));
        transaction(other, theirs, Instant.now().minus(3, ChronoUnit.DAYS));
        em.flush();
        em.clear();

        CadenceRebuildService.RebuildSummary summary = rebuild.rebuild(business.getId());

        assertThat(summary.examined()).isZero();
        assertThat(customers.findById(theirs.getId()).orElseThrow().getAvgIntervalDays())
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    // --- fixtures -----------------------------------------------------------

    private static org.assertj.core.data.TemporalUnitOffset within(long millis) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                millis, java.time.temporal.ChronoUnit.MILLIS);
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café");
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return em.persist(business);
    }

    private Customer customer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(0);
        customer.setLastVisitAt(Instant.now().minus(2, ChronoUnit.DAYS));
        return em.persist(customer);
    }

    private void transaction(Business business, Customer customer, Instant occurredAt) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("txn-" + UUID.randomUUID());
        transaction.setOccurredAt(occurredAt);
        transaction.setAmount(new BigDecimal("12.50"));
        em.persist(transaction);
    }
}
