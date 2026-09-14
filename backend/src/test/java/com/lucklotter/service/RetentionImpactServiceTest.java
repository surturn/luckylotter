package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.RetentionImpactResponse;
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
 * The definitions behind every money figure on the dashboard.
 *
 * <p>These are the assertions that decide whether the numbers mean anything: an
 * offer nobody received is not a miss, a purchase that happened before the offer
 * went out did not happen because of it, and a free item's cost is unknown
 * rather than zero. Each one is a place the product could look better than it is
 * — which is the direction these tests exist to prevent, since a dashboard that
 * flatters itself is worse than one that shows nothing.
 */
@Import({RetentionImpactService.class})
class RetentionImpactServiceTest extends AbstractPostgresTest {

    private static final int WINDOW_DAYS = 14;

    @Autowired
    private RetentionImpactService impact;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private OfferRepository offers;

    @Autowired
    private PosTransactionRepository transactions;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("an offer that never reached the customer is not in the recovery cohort")
    void undeliveredOfferIsNotACohortMember() {
        // The flag the old denominator counted as a failure: the customer had no
        // email and no phone, so nothing was ever sent. Counting it as a miss
        // makes the programme look worse the sparser the contact data is.
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Offer offer = offerFor(business, customer, Instant.now().minus(10, ChronoUnit.DAYS));
        offer.setStatus(OfferStatus.NO_CONTACT);
        offer.setSentAt(null);
        offer.setFailureCode(OfferFailureCode.MISSING_CONTACT_DETAILS);
        offers.saveAndFlush(offer);
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.contacted()).isZero();
        assertThat(response.returned()).isZero();
        assertThat(response.recoveryPercent())
                .as("no denominator means no rate, which is not a rate of 0%%")
                .isNull();
        assertThat(response.flaggedNeverContacted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase inside the window counts as a return and as revenue")
    void purchaseInsideWindowIsAReturn() {
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(10, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("120.00"),
                sentAt.plus(3, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.contacted()).isEqualTo(1);
        assertThat(response.returned()).isEqualTo(1);
        assertThat(response.recoveryPercent()).isEqualByComparingTo("100.0");
        assertThat(response.revenueFromReturnedCustomers()).isEqualByComparingTo("120.00");
        assertThat(response.averageRecoveredCustomerValue()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("spend from before the offer was sent is not credited to it")
    void spendBeforeTheOfferIsNotRevenueFromIt() {
        // A customer who was already spending is not a customer the intervention
        // brought back, and counting their basket would be the single easiest way
        // to make the programme look profitable.
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(10, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("300.00"),
                sentAt.minus(2, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.returned()).isZero();
        assertThat(response.revenueFromReturnedCustomers()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a purchase after the window is not a return")
    void purchaseAfterTheWindowIsNotAReturn() {
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(60, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("80.00"),
                sentAt.plus(WINDOW_DAYS + 6L, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.returned()).isZero();
        assertThat(response.revenueFromReturnedCustomers()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a purchase exactly on the window boundary still counts")
    void windowBoundaryIsInclusive() {
        // Off-by-one in either direction here silently changes every rate on the
        // dashboard, so the edge is pinned rather than assumed.
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(30, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("50.00"),
                sentAt.plus(WINDOW_DAYS, ChronoUnit.DAYS));
        entityManager.flush();

        assertThat(impact.forBusiness(business.getId()).returned()).isEqualTo(1);
    }

    @Test
    @DisplayName("only transactions inside the window are counted as revenue")
    void revenueIgnoresTransactionsOutsideTheWindow() {
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(40, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("10.00"), sentAt.minus(1, ChronoUnit.DAYS));
        transaction(business, customer, new BigDecimal("60.00"), sentAt.plus(2, ChronoUnit.DAYS));
        transaction(business, customer, new BigDecimal("999.00"),
                sentAt.plus(WINDOW_DAYS + 1L, ChronoUnit.DAYS));
        entityManager.flush();

        assertThat(impact.forBusiness(business.getId()).revenueFromReturnedCustomers())
                .isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a percentage deal is costed on the basket it was applied to")
    void percentageDealCostIsAShareOfTheReturningBasket() {
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(10, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("200.00"), sentAt.plus(1, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.interventionCost()).isEqualByComparingTo("50.00");
        assertThat(response.netValue()).isEqualByComparingTo("150.00");
        assertThat(response.currencyCode()).isEqualTo("KES");
    }

    @Test
    @DisplayName("a fixed-amount deal costs its face value per delivered offer")
    void fixedAmountCostIsFaceValuePerOffer() {
        // Costed from the offer, not from the return: the discount is what the
        // business gave away, and it gave it away whether or not the customer
        // came back. Two offers that produced nothing still cost money.
        Business business = business(DealType.FIXED_AMOUNT_OFF, new BigDecimal("100.00"));
        Customer first = customer(business);
        Customer second = customer(business);
        offerFor(business, first, Instant.now().minus(10, ChronoUnit.DAYS));
        offerFor(business, second, Instant.now().minus(10, ChronoUnit.DAYS));
        transaction(business, first, new BigDecimal("400.00"),
                Instant.now().minus(9, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.contacted()).isEqualTo(2);
        assertThat(response.returned()).isEqualTo(1);
        assertThat(response.interventionCost()).isEqualByComparingTo("200.00");
        assertThat(response.netValue()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("a free-item deal leaves cost unknown rather than zero")
    void freeItemDealHasUnknownCost() {
        // The transaction data carries no item prices, so there is nothing to
        // value a free item with. A zero here would quietly overstate the
        // programme's return, and a net figure is withheld with it.
        Business business = business(DealType.FREE_ITEM, new BigDecimal("1.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(10, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("90.00"), sentAt.plus(2, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(business.getId());

        assertThat(response.freeItemOffers()).isEqualTo(1);
        assertThat(response.interventionCost()).isNull();
        assertThat(response.netValue()).isNull();
        assertThat(response.revenueFromReturnedCustomers()).isEqualByComparingTo("90.00");
        assertThat(response.definitions().interventionCost()).contains("free item");
    }

    @Test
    @DisplayName("a shorter window forgets a return the longer one counted")
    void windowLengthChangesTheCohort() {
        // Proof the window is actually applied rather than decoration: the same
        // data, one setting apart, is a recovery and then isn't.
        Business business = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer customer = customer(business);
        Instant sentAt = Instant.now().minus(30, ChronoUnit.DAYS);
        offerFor(business, customer, sentAt);
        transaction(business, customer, new BigDecimal("70.00"), sentAt.plus(10, ChronoUnit.DAYS));
        entityManager.flush();

        assertThat(impact.forBusiness(business.getId()).returned()).isEqualTo(1);

        business.setRecoveryWindowDays(7);
        businesses.saveAndFlush(business);
        entityManager.flush();

        assertThat(impact.forBusiness(business.getId()).returned()).isZero();
    }

    @Test
    @DisplayName("another business's offers and spend never reach this one")
    void tenantScopeHolds() {
        Business mine = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Business theirs = business(DealType.PERCENT_OFF, new BigDecimal("25.00"));
        Customer theirCustomer = customer(theirs);
        Instant sentAt = Instant.now().minus(5, ChronoUnit.DAYS);
        offerFor(theirs, theirCustomer, sentAt);
        transaction(theirs, theirCustomer, new BigDecimal("500.00"), sentAt.plus(1, ChronoUnit.DAYS));
        entityManager.flush();

        RetentionImpactResponse response = impact.forBusiness(mine.getId());

        assertThat(response.contacted()).isZero();
        assertThat(response.revenueFromReturnedCustomers()).isEqualByComparingTo("0.00");
    }

    private Business business(DealType dealType, BigDecimal dealValue) {
        Business business = new Business();
        business.setName("Test Café " + UUID.randomUUID());
        business.setCurrencyCode("KES");
        business.setRecoveryWindowDays(WINDOW_DAYS);
        business.setDefaultDealType(dealType);
        business.setDefaultDealValue(dealValue);
        return businesses.saveAndFlush(business);
    }

    private Customer customer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("POS-" + UUID.randomUUID());
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setTransactionCount(6);
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return customers.saveAndFlush(customer);
    }

    /** A flag with an offer that was delivered — the only way into the cohort. */
    private Offer offerFor(Business business, Customer customer, Instant sentAt) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flag.setFlaggedAt(sentAt.minus(5, ChronoUnit.MINUTES));
        flags.saveAndFlush(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode("TEST-" + UUID.randomUUID().toString().substring(0, 6));
        offer.markSent(sentAt);
        return offers.saveAndFlush(offer);
    }

    private void transaction(Business business, Customer customer, BigDecimal amount,
                            Instant occurredAt) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("TXN-" + UUID.randomUUID());
        transaction.setAmount(amount);
        transaction.setOccurredAt(occurredAt);
        transactions.save(transaction);
    }
}
