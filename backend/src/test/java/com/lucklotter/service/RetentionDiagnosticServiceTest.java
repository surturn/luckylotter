package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.RetentionDiagnosticResponse;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.CadenceSegment;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.CadenceSpread;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.Opportunity;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.PurchaseBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnostic report's own claims (§7).
 *
 * <p>Two failures matter here and neither is a crash. The first is a report that
 * contradicts the dashboard it summarises — the same business, two screens, two
 * answers — which is why the reconciliation case below drives both services off
 * one fixture. The second is a projection that reads as a promise: the estimate
 * must be withheld rather than approximated when the cohort behind it is too
 * small to mean anything, and it must never be presented as money an
 * intervention would cause.
 */
@Import({RetentionDiagnosticService.class, RetentionImpactService.class,
        OverviewStatsService.class, FlagQueryService.class})
class RetentionDiagnosticServiceTest extends AbstractPostgresTest {

    @Autowired
    private RetentionDiagnosticService diagnostics;

    @Autowired
    private OverviewStatsService overview;

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
    @DisplayName("the report agrees with the dashboard about the same business")
    void theReportReconcilesWithTheOverview() {
        // The whole reason the diagnostic is assembled rather than recomputed.
        // The same fixture through both endpoints has to give the same answer, or
        // the product has two truths and the reader has to pick one.
        Business business = business();
        driftingCustomer(business);
        returnedCustomer(business);
        onRhythmCustomer(business);
        customerWithoutCadence(business);
        entityManager.flush();

        RetentionDiagnosticResponse.ExecutiveSummary summary =
                diagnostics.forBusiness(business.getId()).executiveSummary();
        var dashboard = overview.forBusiness(business.getId());

        assertThat(summary.customersAnalyzed()).isEqualTo(dashboard.customersTotal());
        assertThat(summary.customersWithSufficientHistory())
                .isEqualTo(dashboard.customersMonitored());
        assertThat(summary.customersShowingCadenceDisruption())
                .isEqualTo(dashboard.activeFlags());
        assertThat(summary.highValueCustomersAtRisk())
                .isEqualTo(dashboard.highValueDrifting());
        assertThat(summary.customersReturnedAfterIntervention())
                .isEqualTo(dashboard.statusBreakdown().returned());
    }

    @Test
    @DisplayName("the rhythm bands add up to the customers who have a rhythm")
    void cadenceSegmentsPartitionTheMonitoredPopulation() {
        Business business = business();
        cadenceCustomer(business, "7.00");    // weekly
        cadenceCustomer(business, "14.00");   // fortnightly
        cadenceCustomer(business, "30.00");   // monthly
        cadenceCustomer(business, "100.00");  // quarterly
        cadenceCustomer(business, "200.00");  // less often
        customerWithoutCadence(business);
        entityManager.flush();

        List<CadenceSegment> segments =
                diagnostics.forBusiness(business.getId()).customerBehaviour().cadenceSegments();

        assertThat(segments).hasSize(5);
        assertThat(segments).extracting(CadenceSegment::key)
                .containsExactly("WEEKLY", "FORTNIGHTLY", "MONTHLY", "QUARTERLY", "RARER");
        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.customers()).isEqualTo(1));
        assertThat(segments.stream().mapToLong(CadenceSegment::customers).sum())
                .as("the band with no rhythm in it is excluded, leaving the monitored population")
                .isEqualTo(5);
        assertThat(segments.get(0).sharePercent()).isEqualByComparingTo("20.0");
        // The boundaries travel with the band, so the page never has to imply
        // them from the label.
        assertThat(segments.get(0).intervalRange()).isEqualTo("up to 10 days");
        assertThat(segments.get(4).intervalRange()).isEqualTo("over 120 days");
    }

    @Test
    @DisplayName("an empty band is still a band")
    void emptyCadenceSegmentsAreStillReported() {
        Business business = business();
        cadenceCustomer(business, "7.00");
        entityManager.flush();

        List<CadenceSegment> segments =
                diagnostics.forBusiness(business.getId()).customerBehaviour().cadenceSegments();

        // Dropping them would move the boundaries between one report and the
        // next, and two reports whose bands differ cannot be compared.
        assertThat(segments).hasSize(5);
        assertThat(segments.get(1).customers()).isZero();
        assertThat(segments.get(1).sharePercent()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("the purchase bands cover the whole customer base, not just the flaggable part")
    void purchaseBandsCoverEveryone() {
        Business business = business();
        purchaseCustomer(business, 1);   // no rhythm can exist yet
        purchaseCustomer(business, 5);
        purchaseCustomer(business, 10);
        purchaseCustomer(business, 20);
        purchaseCustomer(business, 25);
        entityManager.flush();

        RetentionDiagnosticResponse.CustomerBehaviour behaviour =
                diagnostics.forBusiness(business.getId()).customerBehaviour();

        assertThat(behaviour.purchaseCountDistribution())
                .extracting(PurchaseBand::key)
                .containsExactly("ONE_OR_TWO", "THREE_TO_FIVE", "SIX_TO_TEN",
                        "ELEVEN_TO_TWENTY", "TWENTY_ONE_PLUS");
        assertThat(behaviour.purchaseCountDistribution().stream()
                .mapToLong(PurchaseBand::customers).sum())
                .as("including the customers who can never be flagged — that band is a data "
                        + "gap, and hiding it would leave the distribution short of the base")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("typical intervals are quartiles, not one average dressed up")
    void typicalIntervalsAreQuartiles() {
        Business business = business();
        cadenceCustomer(business, "7.00");
        cadenceCustomer(business, "14.00");
        cadenceCustomer(business, "21.00");
        cadenceCustomer(business, "28.00");
        entityManager.flush();

        CadenceSpread spread =
                diagnostics.forBusiness(business.getId()).customerBehaviour()
                        .typicalPurchaseIntervals();

        // Interpolated between customers, which is the point: a quarter of this
        // base visits more often than 12.3 days and no individual customer does.
        assertThat(spread.p25Days()).isEqualByComparingTo("12.3");
        assertThat(spread.medianDays()).isEqualByComparingTo("17.5");
        assertThat(spread.p75Days()).isEqualByComparingTo("22.8");
        assertThat(spread.meanDays()).isEqualByComparingTo("17.5");
    }

    @Test
    @DisplayName("with nobody to measure, there is no typical interval")
    void typicalIntervalsAreNullWithoutACadence() {
        Business business = business();
        customerWithoutCadence(business);
        entityManager.flush();

        CadenceSpread spread =
                diagnostics.forBusiness(business.getId()).customerBehaviour()
                        .typicalPurchaseIntervals();

        // Null, not zero. "Typical interval: 0 days" would read as a finding
        // about the customers rather than a statement about the data.
        assertThat(spread).isNull();
    }

    @Test
    @DisplayName("the opportunity is the scope, the rate and the observed spend, multiplied")
    void opportunityIsBuiltFromObservedFigures() {
        Business business = business();
        for (int i = 0; i < 10; i++) {
            returnedAfterOffer(business);
        }
        for (int i = 0; i < 4; i++) {
            driftingCustomer(business);
        }
        entityManager.flush();

        Opportunity opportunity = diagnostics.forBusiness(business.getId()).opportunity();

        assertThat(opportunity.available()).isTrue();
        assertThat(opportunity.unavailableReason()).isNull();
        assertThat(opportunity.customersInScope()).isEqualTo(4);
        assertThat(opportunity.cohortContacted()).isEqualTo(10);
        assertThat(opportunity.historicalRecoveryPercent()).isEqualByComparingTo("100.0");
        assertThat(opportunity.averageReturningCustomerValue()).isEqualByComparingTo("450.00");
        assertThat(opportunity.expectedReturns()).isEqualTo(4);
        assertThat(opportunity.estimatedRevenue()).isEqualByComparingTo("1800.00");
        // A quarter off each returning basket of 450 is 112.50 an offer, charged
        // against all four drifting customers: contacting only the ones who come
        // back is not a thing a business can do.
        assertThat(opportunity.estimatedCost()).isEqualByComparingTo("450.00");
        assertThat(opportunity.estimatedNet()).isEqualByComparingTo("1350.00");
    }

    @Test
    @DisplayName("the cost is the cost of contacting everyone, not only the ones who return")
    void opportunityCostsTheWholeScope() {
        Business business = business();
        for (int i = 0; i < 5; i++) {
            returnedAfterOffer(business);
        }
        for (int i = 0; i < 5; i++) {
            contactedButNeverReturned(business);
        }
        for (int i = 0; i < 6; i++) {
            driftingCustomer(business);
        }
        entityManager.flush();

        Opportunity opportunity = diagnostics.forBusiness(business.getId()).opportunity();

        // 5 of 10 came back. Six customers are drifting, so three returns are
        // expected — but six offers have to go out to get them. Costing the
        // three would halve what acting on this actually costs, and the
        // difference is the part a business would notice on the bill.
        assertThat(opportunity.historicalRecoveryPercent()).isEqualByComparingTo("50.0");
        assertThat(opportunity.expectedReturns()).isEqualTo(3);
        assertThat(opportunity.estimatedRevenue()).isEqualByComparingTo("1350.00");
        assertThat(opportunity.estimatedCost()).isEqualByComparingTo("337.50");
        assertThat(opportunity.estimatedNet()).isEqualByComparingTo("1012.50");
    }

    @Test
    @DisplayName("the estimate is withheld when the rate rests on too few offers")
    void opportunityIsWithheldOnAThinCohort() {
        Business business = business();
        returnedAfterOffer(business);
        returnedAfterOffer(business);
        returnedAfterOffer(business);
        driftingCustomer(business);
        entityManager.flush();

        Opportunity opportunity = diagnostics.forBusiness(business.getId()).opportunity();

        // Three offers is not a rate. Publishing a projection built on it would
        // teach the reader to trust the next one, which is the expensive mistake.
        assertThat(opportunity.available()).isFalse();
        assertThat(opportunity.estimatedRevenue()).isNull();
        assertThat(opportunity.expectedReturns()).isZero();
        assertThat(opportunity.unavailableReason())
                .contains("3 offers")
                .contains(String.valueOf(RetentionDiagnosticService.MIN_COHORT));
    }

    @Test
    @DisplayName("a cohort that never returned is not a zero-value opportunity")
    void opportunityIsWithheldUntilSomebodyReturns() {
        Business business = business();
        for (int i = 0; i < 10; i++) {
            contactedButNeverReturned(business);
        }
        driftingCustomer(business);
        entityManager.flush();

        Opportunity opportunity = diagnostics.forBusiness(business.getId()).opportunity();

        // "Estimated opportunity: 0" would be a claim that the programme is
        // worth nothing, made on evidence that says only that it has not worked
        // yet. Different statement, different response.
        assertThat(opportunity.available()).isFalse();
        assertThat(opportunity.unavailableReason()).contains("No customer has returned");
    }

    @Test
    @DisplayName("with nobody drifting there is nothing to project, whatever the track record")
    void opportunityNeedsSomebodyToActOn() {
        Business business = business();
        for (int i = 0; i < 10; i++) {
            returnedAfterOffer(business);
        }
        entityManager.flush();

        Opportunity opportunity = diagnostics.forBusiness(business.getId()).opportunity();

        assertThat(opportunity.available()).isFalse();
        assertThat(opportunity.expectedReturns()).isZero();
        assertThat(opportunity.unavailableReason()).contains("No customers are drifting");
    }

    @Test
    @DisplayName("the executive summary omits the headline rather than showing a figure with no working")
    void theSummaryCarriesTheEstimateOnlyWhenThereIsOne() {
        Business business = business();
        returnedAfterOffer(business);
        driftingCustomer(business);
        entityManager.flush();

        assertThat(diagnostics.forBusiness(business.getId())
                .executiveSummary().estimatedRetentionOpportunity())
                .as("too thin a cohort to project from")
                .isNull();
    }

    @Test
    @DisplayName("the method and the definitions travel with the figures they explain")
    void methodologyIsVisible() {
        Business business = business();
        entityManager.flush();

        RetentionDiagnosticResponse report = diagnostics.forBusiness(business.getId());

        // §7 is explicit that the methodology must be visible, and that no
        // recoverable-revenue number may be manufactured. Both are asserted on
        // the text the caller actually receives.
        assertThat(report.opportunity().method())
                .contains("multiplied")
                .contains("control group");
        assertThat(report.definitions().opportunity()).contains("Not recoverable revenue");
        assertThat(report.definitions().purchaseCountDistribution())
                .contains("cannot be flagged");
        assertThat(report.definitions().cadenceSegments()).contains("bands include ones nobody is in");
    }

    @Test
    @DisplayName("the at-risk section names the customers and says what to do about each")
    void atRiskCustomersCarryTheirReason() {
        Business business = business();
        driftingCustomer(business);
        entityManager.flush();

        RetentionDiagnosticResponse report = diagnostics.forBusiness(business.getId());

        assertThat(report.atRiskTotal()).isEqualTo(1);
        assertThat(report.atRiskCustomers()).hasSize(1);
        var row = report.atRiskCustomers().get(0);
        assertThat(row.recommendedAction()).isNotBlank();
        assertThat(row.avgIntervalDaysAtFlag()).isNotNull();
        // Truncated on purpose; the full list is its own screen.
        assertThat(report.atRiskCustomers()).hasSizeLessThanOrEqualTo(
                RetentionDiagnosticService.AT_RISK_SHOWN);
    }

    // --- fixtures -----------------------------------------------------------

    private Business business() {
        Business business = new Business();
        business.setName("Test Café " + UUID.randomUUID());
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.saveAndFlush(business);
    }

    /** A customer with an open flag — drifting today, whatever else is true of them. */
    private RetentionFlag driftingCustomer(Business business) {
        return flag(business, customer(business, "7.00"), null);
    }

    /**
     * A customer who was contacted and came back: an offer delivered, then a
     * purchase inside the business's recovery window.
     */
    private void returnedAfterOffer(Business business) {
        Instant sentAt = Instant.now().minus(20, ChronoUnit.DAYS);
        RetentionFlag flag = flag(business, customer(business, "7.00"), sentAt);
        offer(business, flag, sentAt);
        transaction(business, flag.getCustomer(), sentAt.plus(2, ChronoUnit.DAYS),
                new BigDecimal("450.00"));
    }

    /** Delivered, unanswered — the shape that must not be priced as a zero. */
    private void contactedButNeverReturned(Business business) {
        Instant sentAt = Instant.now().minus(20, ChronoUnit.DAYS);
        RetentionFlag flag = flag(business, customer(business, "7.00"), sentAt);
        offer(business, flag, sentAt);
    }

    private RetentionFlag flag(Business business, Customer customer, Instant offerSentAt) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        if (offerSentAt != null) {
            // Resolved, because a customer who came back is not drifting now —
            // and that is also what makes them countable as returned.
            flag.setStatus(FlagStatus.RESOLVED);
            flag.setResolvedAt(offerSentAt.plus(2, ChronoUnit.DAYS));
        }
        return flags.saveAndFlush(flag);
    }

    private void offer(Business business, RetentionFlag flag, Instant sentAt) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("25.00"));
        offer.setRedemptionCode("LL-" + UUID.randomUUID().toString().substring(0, 8));
        offer.setCreatedAt(sentAt);
        offer.markSent(sentAt);
        offers.saveAndFlush(offer);
    }

    private Customer cadenceCustomer(Business business, String intervalDays) {
        return customer(business, intervalDays);
    }

    private Customer purchaseCustomer(Business business, int transactionCount) {
        Customer customer = customer(business, null);
        customer.setTransactionCount(transactionCount);
        return customers.saveAndFlush(customer);
    }

    private Customer onRhythmCustomer(Business business) {
        return customer(business, "7.00");
    }

    private Customer customerWithoutCadence(Business business) {
        return customer(business, null);
    }

    private Customer customer(Business business, String intervalDays) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("POS-" + UUID.randomUUID());
        customer.setTransactionCount(6);
        if (intervalDays != null) {
            customer.setAvgIntervalDays(new BigDecimal(intervalDays));
        }
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return customers.saveAndFlush(customer);
    }

    private void transaction(Business business, Customer customer, Instant occurredAt,
                             BigDecimal amount) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("TXN-" + UUID.randomUUID());
        transaction.setAmount(amount);
        transaction.setOccurredAt(occurredAt);
        transactions.save(transaction);
    }

    /** The overview's own fixture, so the reconciliation test drives both sides alike. */
    private Customer returnedCustomer(Business business) {
        Instant sentAt = Instant.now().minus(20, ChronoUnit.DAYS);
        RetentionFlag flag = flag(business, customer(business, "7.00"), sentAt);
        offer(business, flag, sentAt);
        return flag.getCustomer();
    }
}
