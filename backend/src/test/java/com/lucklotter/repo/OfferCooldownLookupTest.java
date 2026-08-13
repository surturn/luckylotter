package com.lucklotter.repo;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.RetentionFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Coverage for {@link OfferRepository#findLastSentAtForCustomer} — the instant
 * the offer cooldown is measured from (FR-4).
 *
 * <p>Needs a real database rather than a stub because the query is the
 * interesting part: it reaches offers through <em>every</em> flag a customer has
 * ever had, not just their open one, and it must ignore offers that were
 * generated but never delivered.
 */
class OfferCooldownLookupTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private OfferRepository offers;

    @Test
    @DisplayName("a customer who has never been sent anything has no cooldown anchor")
    void noOffersMeansEmpty() {
        Business business = persistBusiness();
        Customer customer = persistCustomer(business);
        detach();

        assertThat(offers.findLastSentAtForCustomer(customer.getId())).isEmpty();
    }

    @Test
    @DisplayName("the most recent delivery wins, across separate flags")
    void takesTheLatestAcrossFlags() {
        // The realistic shape: the customer lapsed, was won back, lapsed again.
        // Each lapse is its own flag, and only the newest delivery should anchor
        // the cooldown. Looking at the open flag alone would miss it entirely,
        // since a customer inside their cooldown has no open flag by definition.
        Business business = persistBusiness();
        Customer customer = persistCustomer(business);
        Instant older = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant newer = Instant.now().minus(10, ChronoUnit.DAYS);

        persistSentOffer(business, persistFlag(business, customer, FlagStatus.RESOLVED), older);
        persistSentOffer(business, persistFlag(business, customer, FlagStatus.RESOLVED), newer);
        detach();

        Optional<Instant> lastSent = offers.findLastSentAtForCustomer(customer.getId());
        assertThat(lastSent).isPresent();
        assertThat(lastSent.get()).isCloseTo(newer, within(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("an offer that never reached the customer does not start a cooldown")
    void undeliveredOffersAreIgnored() {
        // A NO_CONTACT offer is not a reward anyone could be cycling for. If it
        // anchored the cooldown, a customer the business has never managed to
        // contact would be silently suppressed from the dashboard for months —
        // the exact coverage gap NO_CONTACT exists to make visible.
        Business business = persistBusiness();
        Customer customer = persistCustomer(business);
        RetentionFlag flag = persistFlag(business, customer, FlagStatus.RESOLVED);

        Offer offer = newOffer(business, flag);
        offer.markNoContact();
        em.persist(offer);
        detach();

        assertThat(offers.findLastSentAtForCustomer(customer.getId())).isEmpty();
    }

    @Test
    @DisplayName("another customer's offers are not visible to this one")
    void scopedToTheCustomer() {
        Business business = persistBusiness();
        Customer subject = persistCustomer(business);
        Customer other = persistCustomer(business);

        persistSentOffer(business, persistFlag(business, other, FlagStatus.RESOLVED),
                Instant.now().minus(2, ChronoUnit.DAYS));
        detach();

        assertThat(offers.findLastSentAtForCustomer(subject.getId())).isEmpty();
    }

    // --- fixtures -----------------------------------------------------------

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

    private Customer persistCustomer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setTransactionCount(3);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return em.persist(customer);
    }

    private RetentionFlag persistFlag(Business business, Customer customer, FlagStatus status) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(business.thresholdDaysFor(customer.getAvgIntervalDays()));
        flag.setStatus(status);
        if (status == FlagStatus.RESOLVED) {
            // The partial unique index only permits one ACTIVE flag per
            // customer, so a history of lapses has to be resolved to exist.
            flag.setResolvedAt(Instant.now());
        }
        return em.persist(flag);
    }

    private void persistSentOffer(Business business, RetentionFlag flag, Instant sentAt) {
        Offer offer = newOffer(business, flag);
        offer.markSent(sentAt);
        em.persist(offer);
    }

    private Offer newOffer(Business business, RetentionFlag flag) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(new BigDecimal("25.00"));
        return offer;
    }
}
