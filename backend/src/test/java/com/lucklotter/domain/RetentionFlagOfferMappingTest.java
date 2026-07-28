package com.lucklotter.domain;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.repo.RetentionFlagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the {@code RetentionFlag.offer} inverse mapping (FR-4, FR-7).
 *
 * <p>Until now that association was only ever exercised through
 * {@link RetentionFlagRepository#findForDashboard}, which fetch-joins the offer
 * explicitly. The flag-detail path
 * ({@link RetentionFlagRepository#findByIdAndBusinessId}, behind
 * {@code GET /v1/flags/{id}}) does not — it relies on the mapping resolving the
 * offer on traversal. A broken {@code mappedBy} would leave the fetch-joined
 * list working and the detail view silently offer-less, so both paths are
 * asserted here.
 */
class RetentionFlagOfferMappingTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RetentionFlagRepository flags;

    @Test
    @DisplayName("a flag loaded without a fetch join still resolves its offer")
    void flagResolvesOfferOnPlainTraversal() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business, "cust-1");
        RetentionFlag flag = persistFlag(business, customer);
        persistOffer(business, flag, new BigDecimal("25.00"));
        detach();

        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();

        assertThat(reloaded.getOffer()).isNotNull();
        assertThat(reloaded.getOffer().getDealType()).isEqualTo(DealType.PERCENT_OFF);
        assertThat(reloaded.getOffer().getDealValue()).isEqualByComparingTo("25.00");
        assertThat(reloaded.getOffer().getStatus()).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    @DisplayName("the flag-detail lookup resolves the offer too, with no fetch join")
    void flagDetailLookupResolvesOffer() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business, "cust-2");
        RetentionFlag flag = persistFlag(business, customer);
        persistOffer(business, flag, new BigDecimal("15.00"));
        detach();

        RetentionFlag detail = flags
                .findByIdAndBusinessId(flag.getId(), business.getId())
                .orElseThrow();

        assertThat(detail.getOffer()).isNotNull();
        assertThat(detail.getOffer().getDealValue()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("the association is bidirectional — the offer points back at its flag")
    void offerPointsBackAtItsFlag() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business, "cust-3");
        RetentionFlag flag = persistFlag(business, customer);
        persistOffer(business, flag, new BigDecimal("10.00"));
        detach();

        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();

        assertThat(reloaded.getOffer().getFlag().getId()).isEqualTo(reloaded.getId());
    }

    /**
     * A flag can exist before its offer is written. The mapping must report that
     * as absent rather than blowing up — the dashboard's {@code LEFT JOIN FETCH}
     * implies the same nullability.
     */
    @Test
    @DisplayName("a flag with no offer yet reports null, not an error")
    void flagWithoutOfferReportsNull() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business, "cust-4");
        RetentionFlag flag = persistFlag(business, customer);
        detach();

        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();

        assertThat(reloaded.getOffer()).isNull();
    }

    /** A NO_CONTACT offer must be reachable through the flag like any other. */
    @Test
    @DisplayName("a NO_CONTACT offer is visible through the flag")
    void noContactOfferIsVisibleThroughTheFlag() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business, "cust-5");
        assertThat(customer.isContactable()).isFalse();

        RetentionFlag flag = persistFlag(business, customer);
        Offer offer = newOffer(business, flag, new BigDecimal("20.00"));
        offer.markNoContact();
        em.persist(offer);
        detach();

        RetentionFlag reloaded = flags.findById(flag.getId()).orElseThrow();

        assertThat(reloaded.getOffer().getStatus()).isEqualTo(OfferStatus.NO_CONTACT);
        assertThat(reloaded.getOffer().getFailureCode())
                .isEqualTo(OfferFailureCode.MISSING_CONTACT_DETAILS);
    }

    // --- fixtures -----------------------------------------------------------

    /** Flush and clear, so the next read comes from the database, not the cache. */
    private void detach() {
        em.flush();
        em.clear();
    }

    private Business persistBusiness() {
        Business business = new Business();
        business.setName("Test Café");
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return em.persist(business);
    }

    /** Deliberately has neither contact field — see {@link #noContactOfferIsVisibleThroughTheFlag}. */
    private Customer persistCustomer(Business business, String externalRef) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef(externalRef + "-" + UUID.randomUUID());
        customer.setTransactionCount(RetentionConstants.MIN_TRANSACTIONS);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return em.persist(customer);
    }

    private RetentionFlag persistFlag(Business business, Customer customer) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(business.thresholdDaysFor(customer.getAvgIntervalDays()));
        return em.persist(flag);
    }

    private Offer persistOffer(Business business, RetentionFlag flag, BigDecimal dealValue) {
        return em.persist(newOffer(business, flag, dealValue));
    }

    private Offer newOffer(Business business, RetentionFlag flag, BigDecimal dealValue) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(dealValue);
        return offer;
    }
}
