package com.lucklotter.repo;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionFlag;
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
 * Coverage for the rolling offer budget ceiling (FR-4) — the bound on what the
 * retention programme can cost in total, as opposed to the cooldown's bound on
 * what one customer can extract.
 *
 * <p>Against real Postgres because two of the guarantees here are the schema's:
 * that {@code SUPPRESSED_BUDGET} is an accepted status at all (V6 has to
 * successfully replace V1's four-value check constraint), and that it is
 * allowed to carry no {@code failure_code} when every other terminal status
 * requires one.
 */
class OfferBudgetCeilingTest extends AbstractPostgresTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private OfferRepository offers;

    @Test
    @DisplayName("a suppressed offer is storable and carries no failure code")
    void suppressedBudgetOfferPersists() {
        // Guards the V6 migration: if the auto-generated name for V1's inline
        // status check were ever different, the DROP would fail the migration
        // outright — but if the failure_code constraint were left untouched,
        // this insert is what catches it.
        Business business = persistBusiness();
        Customer customer = persistCustomer(business);
        Offer offer = newOffer(business, persistFlag(business, customer));
        offer.markSuppressedBudget();
        em.persist(offer);
        detach();

        Offer reloaded = em.find(Offer.class, offer.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.SUPPRESSED_BUDGET);
        assertThat(reloaded.getFailureCode()).isNull();
        assertThat(reloaded.getSentAt()).isNull();
    }

    @Test
    @DisplayName("offers that will never be delivered do not consume the budget")
    void undeliverableOffersDoNotCountAgainstTheCap() {
        // Charging the budget for these would let a business whose POS carries
        // no contact details exhaust its cap without one offer reaching anyone,
        // and would make the suppression self-sustaining once it started.
        Business business = persistBusiness();
        Customer customer = persistCustomer(business);

        Offer noContact = newOffer(business, persistFlag(business, customer));
        noContact.markNoContact();
        em.persist(noContact);

        Offer suppressed = newOffer(business, persistFlag(business, persistCustomer(business)));
        suppressed.markSuppressedBudget();
        em.persist(suppressed);
        detach();

        assertThat(offers.countCommittedSince(business.getId(), windowStart())).isZero();
    }

    @Test
    @DisplayName("pending and failed offers both count as committed")
    void committedOffersCount() {
        // PENDING matters most: an offer sits there between the scan generating
        // it and the dispatcher sending it, so counting deliveries alone would
        // leave a whole batch invisible and let the next scan spend the same
        // budget twice. FAILED counts because it was attempted and is still in
        // the retry backlog.
        Business business = persistBusiness();
        em.persist(newOffer(business, persistFlag(business, persistCustomer(business))));

        Offer sent = newOffer(business, persistFlag(business, persistCustomer(business)));
        sent.markSent(Instant.now());
        em.persist(sent);

        Offer failed = newOffer(business, persistFlag(business, persistCustomer(business)));
        failed.markFailed(com.lucklotter.domain.OfferFailureCode.SENDER_TIMEOUT);
        em.persist(failed);
        detach();

        assertThat(offers.countCommittedSince(business.getId(), windowStart())).isEqualTo(3);
    }

    @Test
    @DisplayName("offers older than the window have rolled out of it")
    void offersOutsideTheWindowAreExcluded() {
        Business business = persistBusiness();
        Offer old = newOffer(business, persistFlag(business, persistCustomer(business)));
        em.persist(old);
        em.flush();
        // created_at defaults to now() in the DB, so age it explicitly rather
        // than trusting a fixture to have been written in the past.
        em.getEntityManager()
                .createNativeQuery("UPDATE offers SET created_at = :past WHERE id = :id")
                .setParameter("past", Instant.now().minus(45, ChronoUnit.DAYS))
                .setParameter("id", old.getId())
                .executeUpdate();
        detach();

        assertThat(offers.countCommittedSince(business.getId(), windowStart())).isZero();
    }

    @Test
    @DisplayName("another business's spend is not charged to this one")
    void scopedToTheBusiness() {
        Business business = persistBusiness();
        Business other = persistBusiness();
        em.persist(newOffer(other, persistFlag(other, persistCustomer(other))));
        detach();

        assertThat(offers.countCommittedSince(business.getId(), windowStart())).isZero();
    }

    // --- fixtures -----------------------------------------------------------

    private static Instant windowStart() {
        return Instant.now().minus(30, ChronoUnit.DAYS);
    }

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

    private RetentionFlag persistFlag(Business business, Customer customer) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(business.thresholdDaysFor(customer.getAvgIntervalDays()));
        flag.setStatus(FlagStatus.RESOLVED);
        flag.setResolvedAt(Instant.now());
        return em.persist(flag);
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
