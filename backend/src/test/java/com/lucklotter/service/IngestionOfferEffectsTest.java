package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.web.dto.TransactionIngestRequest;
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
 * What a new transaction does to the offer waiting behind an open flag: a
 * customer who comes back before it is delivered should not be mailed, and a
 * corrected address should give a failed offer another chance.
 */
@Import(IngestionService.class)
class IngestionOfferEffectsTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private IngestionService ingestion;

    @Test
    @DisplayName("a customer who returns before a queued offer is sent closes it")
    void returnClosesAQueuedOffer() {
        Fixture f = fixture(OfferStatus.PENDING, null);

        ingest(f, Instant.now(), null);

        assertThat(reload(f).getStatus()).isEqualTo(OfferStatus.SUPPRESSED_RETURNED);
        assertThat(reload(f).getFailureCode()).isNull();
    }

    @Test
    @DisplayName("and one that is waiting on a retry")
    void returnClosesAFailedOffer() {
        Fixture f = fixture(OfferStatus.FAILED, OfferFailureCode.SENDER_UNAVAILABLE);

        ingest(f, Instant.now(), null);

        assertThat(reload(f).getStatus()).isEqualTo(OfferStatus.SUPPRESSED_RETURNED);
    }

    @Test
    @DisplayName("an offer that was already delivered stays delivered")
    void deliveredOfferIsUntouched() {
        Fixture f = fixture(OfferStatus.SENT, null);

        ingest(f, Instant.now(), null);

        // The return is the offer's result and is what the recovery figures count.
        assertThat(reload(f).getStatus()).isEqualTo(OfferStatus.SENT);
    }

    @Test
    @DisplayName("a backdated sale does not close the offer, because the customer has not returned")
    void backdatedSaleLeavesTheOfferAlone() {
        Fixture f = fixture(OfferStatus.PENDING, null);

        ingest(f, Instant.now().minus(60, ChronoUnit.DAYS), null);

        assertThat(reload(f).getStatus()).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    @DisplayName("a corrected email address gives a failed offer a fresh start")
    void correctedAddressReopensAFailedOffer() {
        Fixture f = fixture(OfferStatus.FAILED, OfferFailureCode.INVALID_EMAIL_ADDRESS);

        // A backdated row, so the flag stays open and only the address changes.
        ingest(f, Instant.now().minus(60, ChronoUnit.DAYS), "fixed@example.com");

        Offer offer = reload(f);
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.getFailureCode()).isNull();
        assertThat(offer.getSendAttempts()).isZero();
    }

    @Test
    @DisplayName("the same address sent again changes nothing")
    void sameAddressDoesNotReopen() {
        Fixture f = fixture(OfferStatus.FAILED, OfferFailureCode.INVALID_EMAIL_ADDRESS);

        ingest(f, Instant.now().minus(60, ChronoUnit.DAYS), "regular@example.com");

        assertThat(reload(f).getStatus()).isEqualTo(OfferStatus.FAILED);
    }

    // --- fixtures -----------------------------------------------------------

    private record Fixture(Business business, Customer customer, UUID offerId) {
    }

    private Offer reload(Fixture f) {
        em.flush();
        em.clear();
        return em.find(Offer.class, f.offerId());
    }

    private void ingest(Fixture f, Instant occurredAt, String contactEmail) {
        // Cleared first so the flag's offer is read from the database, as it is
        // in production, rather than from the half-built objects set up here.
        em.flush();
        em.clear();
        ingestion.ingest(f.business().getId(), new TransactionIngestRequest(
                f.business().getId(), f.customer().getExternalRef(), null, null,
                "txn-" + UUID.randomUUID(), new BigDecimal("12.50"), occurredAt,
                contactEmail, null));
    }

    private Fixture fixture(OfferStatus status, OfferFailureCode failure) {
        Business business = new Business();
        business.setName("Test Cafe");
        business.setDefaultDealValue(new BigDecimal("15.00"));
        em.persist(business);

        Instant lastVisit = Instant.now().minus(30, ChronoUnit.DAYS);
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(0);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setFirstSeenAt(lastVisit);
        customer.setLastVisitAt(lastVisit);
        em.persist(customer);

        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        em.persist(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("15.00"));
        switch (status) {
            case FAILED -> offer.markFailed(failure);
            case SENT -> offer.markSent(Instant.now().minus(2, ChronoUnit.DAYS));
            default -> { }
        }
        em.persist(offer);
        return new Fixture(business, customer, offer.getId());
    }
}
