package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.TransactionIngestRequest;
import com.lucklotter.web.dto.TransactionIngestResponse;
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
 * What a late-arriving transaction may and may not do to an open flag (FR-9).
 *
 * <p>Any new transaction used to resolve a flag, which is right for live POS
 * traffic and wrong for a historical import: backfilling a year of sales for a
 * customer who is currently lapsed would close their flag on the strength of a
 * months-old receipt, and do it silently across the whole file.
 */
@Import(IngestionService.class)
class IngestionBackdatingTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private RetentionFlagRepository flags;

    @Test
    @DisplayName("a backdated transaction leaves an open flag open")
    void backdatedTransactionDoesNotResolve() {
        Business business = business();
        Customer customer = customer(business, Instant.now().minus(30, ChronoUnit.DAYS));
        RetentionFlag flag = openFlag(business, customer);
        em.flush();

        // A sale from six weeks ago, arriving now in a history import. The
        // customer has still not been seen for 30 days.
        TransactionIngestResponse response = ingest(
                business, customer, Instant.now().minus(42, ChronoUnit.DAYS));

        assertThat(response.resolvedFlagId()).isNull();
        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FlagStatus.ACTIVE);
        assertThat(reloaded.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("a live transaction still resolves the flag")
    void forwardTransactionResolves() {
        // The rule must not cost the case it exists for: someone walking back
        // in is exactly what a flag is waiting for (FR-9).
        Business business = business();
        Customer customer = customer(business, Instant.now().minus(30, ChronoUnit.DAYS));
        RetentionFlag flag = openFlag(business, customer);
        em.flush();

        TransactionIngestResponse response = ingest(business, customer, Instant.now());

        assertThat(response.resolvedFlagId()).isEqualTo(flag.getId());
        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FlagStatus.RESOLVED);
        assertThat(reloaded.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("a backdated transaction still counts toward cadence")
    void backdatedTransactionStillCountsAsHistory() {
        // Not resolving the flag is not the same as ignoring the sale: the
        // transaction is real history and belongs in the customer's rhythm.
        Business business = business();
        Customer customer = customer(business, Instant.now().minus(30, ChronoUnit.DAYS));
        openFlag(business, customer);
        em.flush();

        ingest(business, customer, Instant.now().minus(42, ChronoUnit.DAYS));

        Customer reloaded = em.find(Customer.class, customer.getId());
        assertThat(reloaded.getTransactionCount()).isEqualTo(1);
        // And the backdated arrival must not drag the last-visit date backwards.
        assertThat(reloaded.getLastVisitAt())
                .isAfter(Instant.now().minus(31, ChronoUnit.DAYS));
    }

    // --- fixtures -----------------------------------------------------------

    private TransactionIngestResponse ingest(Business business, Customer customer,
                                             Instant occurredAt) {
        return ingestion.ingest(business.getId(), new TransactionIngestRequest(
                business.getId(), customer.getExternalRef(), null, null,
                "txn-" + UUID.randomUUID(), new BigDecimal("12.50"), occurredAt,
                null, null));
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café");
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return em.persist(business);
    }

    private Customer customer(Business business, Instant lastVisitAt) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(0);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setFirstSeenAt(lastVisitAt);
        customer.setLastVisitAt(lastVisitAt);
        return em.persist(customer);
    }

    private RetentionFlag openFlag(Business business, Customer customer) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        return em.persist(flag);
    }
}
