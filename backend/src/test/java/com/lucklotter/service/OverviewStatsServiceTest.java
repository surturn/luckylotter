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
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.OverviewStatsResponse.SeriesPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overview's internal consistency, which is the thing a reader notices
 * first and trusts last.
 *
 * <p>The screen used to show a status breakdown that mixed flag counts with
 * customer counts, so its parts did not add up to anything — an operator who
 * tried to reconcile the dashboard against itself found it couldn't be done, and
 * the reasonable conclusion from there is that none of the numbers are solid.
 * These tests hold the partition together.
 */
@Import({OverviewStatsService.class, RetentionImpactService.class})
class OverviewStatsServiceTest extends AbstractPostgresTest {

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
    @DisplayName("the status breakdown is a partition of the customer base")
    void breakdownAddsUpToTheCustomerBase() {
        Business business = business();
        driftingCustomer(business);
        returnedCustomer(business);
        onRhythmCustomer(business);
        customerWithoutCadence(business);
        entityManager.flush();

        OverviewStatsResponse response = overview.forBusiness(business.getId());
        OverviewStatsResponse.StatusBreakdown breakdown = response.statusBreakdown();

        assertThat(breakdown.drifting()).isEqualTo(1);
        assertThat(breakdown.returned()).isEqualTo(1);
        assertThat(breakdown.onRhythm()).isEqualTo(1);
        assertThat(breakdown.notEnoughData()).isEqualTo(1);
        assertThat(breakdown.total())
                .as("the four buckets are disjoint and cover everyone")
                .isEqualTo(4);
        assertThat(breakdown.drifting() + breakdown.returned()
                + breakdown.onRhythm() + breakdown.notEnoughData())
                .isEqualTo(breakdown.total());
        assertThat(response.customersTotal()).isEqualTo(4);
        assertThat(response.customersMonitored()).isEqualTo(3);
        assertThat(response.customersBelowThreshold()).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer who came back and lapsed again counts as drifting, not returned")
    void returnedCustomerWhoLapsedAgainIsDrifting() {
        // Both statements are true of them, and only one of them tells an
        // operator what to do this morning.
        Business business = business();
        Customer customer = returnedCustomer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flags.saveAndFlush(flag);
        entityManager.flush();

        OverviewStatsResponse.StatusBreakdown breakdown =
                overview.forBusiness(business.getId()).statusBreakdown();

        assertThat(breakdown.drifting()).isEqualTo(1);
        assertThat(breakdown.returned()).isZero();
    }

    @Test
    @DisplayName("high-value drifting customers are measured against the business's own average")
    void highValueIsRelativeToTheBusiness() {
        // Three customers, and the thresholds why. The bar is twice the mean of
        // customer totals, so with only two customers it can never be cleared:
        // one holding all the spend puts the mean at half that spend, and twice
        // the mean is the spend itself. The rule needs a customer base to mean
        // anything, which is why the seeder ships one.
        Business business = business();
        Customer big = driftingCustomer(business);
        Customer small = driftingCustomer(business);
        Customer alsoSmall = driftingCustomer(business);
        transaction(business, big, new BigDecimal("900.00"));
        transaction(business, small, new BigDecimal("100.00"));
        transaction(business, alsoSmall, new BigDecimal("100.00"));
        entityManager.flush();

        assertThat(overview.forBusiness(business.getId()).highValueDrifting()).isEqualTo(1);
    }

    @Test
    @DisplayName("with too few customers to have an average, nothing is high value")
    void highValueNeedsACustomerBase() {
        // Not a bug to paper over: the alternative is a fixed amount, which is
        // meaningless across a coffee shop and a restaurant.
        Business business = business();
        Customer only = driftingCustomer(business);
        transaction(business, only, new BigDecimal("9000.00"));
        entityManager.flush();

        assertThat(overview.forBusiness(business.getId()).highValueDrifting()).isZero();
    }

    @Test
    @DisplayName("every card on the overview carries its own definition")
    void definitionsTravelWithTheNumbers() {
        Business business = business();
        entityManager.flush();

        OverviewStatsResponse.Definitions definitions =
                overview.forBusiness(business.getId()).definitions();

        // Not decoration: a rate whose denominator only an engineer can supply is
        // one nobody can act on or defend.
        assertThat(definitions.recovery()).contains("offer was actually delivered");
        assertThat(definitions.customersBelowThreshold()).contains("fewer than three");
        assertThat(definitions.statusBreakdown()).contains("add up");
    }

    @Test
    @DisplayName("nothing delivered yet means no recovery rate, not a rate of zero")
    void noCohortMeansNoRate() {
        Business business = business();
        entityManager.flush();

        OverviewStatsResponse.Recovery recovery = overview.forBusiness(business.getId()).recovery();

        assertThat(recovery.contacted()).isZero();
        assertThat(recovery.percent()).isNull();
        assertThat(recovery.windowDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("the reporting period never reaches the cadence model")
    void theWindowDoesNotMoveTheModel() {
        // The point of the whole exercise. A reporting selector that quietly
        // re-derived the model would let two operators on the same business
        // disagree about who is drifting — and the customer who drops off the
        // list is the one nobody calls.
        Business business = business();
        driftingCustomer(business);
        returnedCustomer(business);
        onRhythmCustomer(business);
        customerWithoutCadence(business);
        entityManager.flush();

        OverviewStatsResponse sevenDays = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_7_DAYS);
        OverviewStatsResponse eightWeeks = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_8_WEEKS);

        assertThat(sevenDays.customersMonitored()).isEqualTo(eightWeeks.customersMonitored());
        assertThat(sevenDays.customersBelowThreshold())
                .isEqualTo(eightWeeks.customersBelowThreshold());
        assertThat(sevenDays.statusBreakdown()).isEqualTo(eightWeeks.statusBreakdown());
        assertThat(sevenDays.highValueDrifting()).isEqualTo(eightWeeks.highValueDrifting());
        assertThat(sevenDays.activeFlags()).isEqualTo(eightWeeks.activeFlags());
    }

    @Test
    @DisplayName("the activity series is cut into the window's own unit")
    void theSeriesFollowsTheWindow() {
        Business business = business();
        entityManager.flush();

        OverviewStatsResponse sevenDays = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_7_DAYS);
        OverviewStatsResponse thirtyDays = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_30_DAYS);
        OverviewStatsResponse eightWeeks = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_8_WEEKS);

        // A week is a useful division of eight weeks and a useless one of seven
        // days, where it would collapse the chart to a single bar.
        assertThat(sevenDays.activitySeries()).hasSize(7);
        assertThat(sevenDays.reportingPeriod().unit()).isEqualTo("day");
        assertThat(thirtyDays.activitySeries()).hasSize(30);
        assertThat(eightWeeks.activitySeries()).hasSize(8);
        assertThat(eightWeeks.reportingPeriod().unit()).isEqualTo("week");
    }

    @Test
    @DisplayName("the series covers the period with no gaps, oldest first")
    void quietBucketsAreStillDrawn() {
        Business business = business();
        entityManager.flush();

        List<SeriesPoint> series =
                overview.forBusiness(business.getId(), ReportingWindow.LAST_7_DAYS).activitySeries();

        // Nothing happened at all in this business, so every bucket is zero —
        // and every bucket is present. Rendering only the buckets with rows
        // would close a quiet week and draw it as continuous activity.
        assertThat(series).allSatisfy(point -> {
            assertThat(point.flagsRaised()).isZero();
            assertThat(point.customersRecovered()).isZero();
        });
        assertThat(series.get(0).bucketStart())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(6));
        assertThat(series.get(6).bucketStart())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(series).extracting(SeriesPoint::bucketStart).isSorted();
    }

    @Test
    @DisplayName("an event outside the window is counted in the longer period, not the shorter")
    void theWindowBoundsWhichEventsCount() {
        Business business = business();
        driftingCustomer(business);
        flagRaisedDaysAgo(business, customer(business), 20);
        entityManager.flush();

        OverviewStatsResponse sevenDays = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_7_DAYS);
        OverviewStatsResponse eightWeeks = overview.forBusiness(business.getId(),
                ReportingWindow.LAST_8_WEEKS);

        assertThat(sevenDays.comparison().flagsRaisedNow()).isEqualTo(1);
        assertThat(eightWeeks.comparison().flagsRaisedNow()).isEqualTo(2);
        assertThat(sumFlagsRaised(sevenDays.activitySeries())).isEqualTo(1);
        assertThat(sumFlagsRaised(eightWeeks.activitySeries())).isEqualTo(2);
    }

    @Test
    @DisplayName("the period is compared against one of equal length")
    void theComparisonIsAlwaysLikeForLike() {
        Business business = business();
        driftingCustomer(business);
        // Forty days back. Inside the 8-week period, outside the 30-day one, and
        // inside the 30-day period's predecessor.
        flagRaisedDaysAgo(business, customer(business), 40);
        entityManager.flush();

        // One event, three verdicts, each the one its window dictates. A
        // comparison against a period of a different length would put it on both
        // sides or neither, and report a trend that is an artefact of arithmetic
        // rather than a change in the business.
        OverviewStatsResponse.PeriodComparison week =
                overview.forBusiness(business.getId(), ReportingWindow.LAST_7_DAYS).comparison();
        OverviewStatsResponse.PeriodComparison month =
                overview.forBusiness(business.getId(), ReportingWindow.LAST_30_DAYS).comparison();
        OverviewStatsResponse.PeriodComparison eightWeeks =
                overview.forBusiness(business.getId(), ReportingWindow.LAST_8_WEEKS).comparison();

        assertThat(week.flagsRaisedNow())
                .as("today's flag only; the one from 40 days ago is outside a 7-day period")
                .isEqualTo(1);
        assertThat(week.flagsRaisedBefore()).isZero();
        assertThat(month.flagsRaisedNow())
                .as("still today's flag; 40 days ago is outside a 30-day period").isEqualTo(1);
        assertThat(month.flagsRaisedBefore())
                .as("but inside the 30 days before that, so it counts against the earlier period")
                .isEqualTo(1);
        assertThat(eightWeeks.flagsRaisedNow())
                .as("and both flags fall inside an 8-week period").isEqualTo(2);
        assertThat(eightWeeks.flagsRaisedBefore()).isZero();
    }

    @Test
    @DisplayName("a card and the trend arrow beside it are measured over the same rows")
    void theHeadlineAndItsArrowAgree() {
        Business business = business();
        offerSentDaysAgo(business, 2);
        offerSentDaysAgo(business, 20);
        entityManager.flush();

        // The failure this guards against is subtle and was real: the card showed
        // an all-time total while the arrow under it compared two periods, so the
        // reader was invited to check one number against another that measured a
        // different set of rows. They agree now, on every window.
        for (ReportingWindow window : ReportingWindow.values()) {
            OverviewStatsResponse response = overview.forBusiness(business.getId(), window);
            assertThat(response.offersSent())
                    .as("offers sent, on %s", window)
                    .isEqualTo(response.comparison().offersSentNow());
        }

        assertThat(overview.forBusiness(business.getId(), ReportingWindow.LAST_7_DAYS).offersSent())
                .as("the offer from 20 days ago is outside a 7-day period")
                .isEqualTo(1);
        assertThat(overview.forBusiness(business.getId(), ReportingWindow.LAST_8_WEEKS).offersSent())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("flags nobody could reach are counted in the same period as the rate beside them")
    void unreachableFlagsFollowTheWindow() {
        Business business = business();
        flagWithoutDeliveredOfferDaysAgo(business, 20);
        entityManager.flush();

        // All-time here would sit an unchanging number under a recovery rate that
        // moves with the selector, and nothing on the card would say the two are
        // not the same claim.
        assertThat(overview.forBusiness(business.getId(), ReportingWindow.LAST_7_DAYS)
                .recovery().neverContacted()).isZero();
        assertThat(overview.forBusiness(business.getId(), ReportingWindow.LAST_30_DAYS)
                .recovery().neverContacted()).isEqualTo(1);
    }

    @Test
    @DisplayName("the recovery window stays the customer's own, not the reporting period's")
    void theRecoveryWindowIsNotTheReportingWindow() {
        Business business = business();
        returnedCustomer(business);
        entityManager.flush();

        // Two different things share the word "window", and conflating them would
        // make a seven-day report claim a customer had seven days to come back.
        // The recovery window is a property of the business; the reporting period
        // only decides which offers are in scope.
        OverviewStatsResponse sevenDays =
                overview.forBusiness(business.getId(), ReportingWindow.LAST_7_DAYS);

        assertThat(sevenDays.reportingPeriod().key()).isEqualTo("LAST_7_DAYS");
        assertThat(sevenDays.reportingPeriod().label()).isEqualTo("Last 7 days");
        assertThat(sevenDays.reportingPeriod().days()).isEqualTo(7);
        assertThat(sevenDays.recovery().windowDays()).isEqualTo(14);
    }

    private static long sumFlagsRaised(List<SeriesPoint> series) {
        return series.stream().mapToLong(SeriesPoint::flagsRaised).sum();
    }

    /**
     * A flag raised at a chosen moment, on its own customer. A customer can hold
     * only one active flag at a time — a partial unique index, and the right
     * constraint: two open flags for one person would mean two offers.
     */
    private RetentionFlag flagRaisedDaysAgo(Business business, Customer customer, int days) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flag.setFlaggedAt(Instant.now().minus(days, ChronoUnit.DAYS));
        return flags.saveAndFlush(flag);
    }

    private void offerSentDaysAgo(Business business, int days) {
        Offer offer = offerFor(business, flagRaisedDaysAgo(business, customer(business), days), days);
        offer.markSent(Instant.now().minus(days, ChronoUnit.DAYS));
        offers.saveAndFlush(offer);
    }

    private void flagWithoutDeliveredOfferDaysAgo(Business business, int days) {
        Offer offer = offerFor(business, flagRaisedDaysAgo(business, customer(business), days), days);
        offer.markNoContact();
        offers.saveAndFlush(offer);
    }

    private Offer offerFor(Business business, RetentionFlag flag, int days) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("25.00"));
        offer.setRedemptionCode("LL-" + UUID.randomUUID().toString().substring(0, 8));
        // Created when the flag was raised. The window bounds unreachable offers
        // on this, so a fixture that left it at "now" would not exercise it.
        offer.setCreatedAt(Instant.now().minus(days, ChronoUnit.DAYS));
        return offer;
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café " + UUID.randomUUID());
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.saveAndFlush(business);
    }

    private Customer driftingCustomer(Business business) {
        Customer customer = customer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flags.saveAndFlush(flag);
        return customer;
    }

    private Customer returnedCustomer(Business business) {
        Customer customer = customer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flag.setStatus(FlagStatus.RESOLVED);
        flag.setResolvedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        flags.saveAndFlush(flag);
        return customer;
    }

    private Customer onRhythmCustomer(Business business) {
        return customer(business);
    }

    private Customer customerWithoutCadence(Business business) {
        Customer customer = customer(business);
        customer.setAvgIntervalDays(null);
        return customers.saveAndFlush(customer);
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

    private void transaction(Business business, Customer customer, BigDecimal amount) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("TXN-" + UUID.randomUUID());
        transaction.setAmount(amount);
        transaction.setOccurredAt(Instant.now().minus(5, ChronoUnit.DAYS));
        transactions.save(transaction);
    }
}
