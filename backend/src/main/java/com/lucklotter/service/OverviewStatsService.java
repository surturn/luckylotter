package com.lucklotter.service;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.repo.TimeBucketCount;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.OverviewStatsResponse.Definitions;
import com.lucklotter.web.dto.OverviewStatsResponse.PeriodComparison;
import com.lucklotter.web.dto.OverviewStatsResponse.Recovery;
import com.lucklotter.web.dto.OverviewStatsResponse.ReportingPeriod;
import com.lucklotter.web.dto.OverviewStatsResponse.SeriesPoint;
import com.lucklotter.web.dto.OverviewStatsResponse.StatusBreakdown;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates for the overview screen (FR-7), scoped to one business (NFR-1).
 *
 * <p>Split down the middle by what a reporting window can honestly move. The
 * counts that describe how things stand — customers monitored, who is drifting,
 * the status breakdown, the severity spread — come from every customer's
 * complete history and are identical whichever window is asked for; a period
 * label or a trend arrow beside them would claim a comparison the database
 * never stored. The figures that describe what happened — flags raised,
 * customers recovered, offers sent, the recovery rate — are events carrying a
 * timestamp, and are reported strictly inside the window. See
 * {@link ReportingWindow} for why the selector never reaches the model.
 */
@Service
public class OverviewStatsService {

    /**
     * How far above the average customer a drifting customer has to be worth to
     * count as high value. Deliberately a multiple of the business's own average
     * rather than an amount, since a valuable customer at a coffee shop and at a
     * restaurant are orders of magnitude apart.
     */
    static final BigDecimal HIGH_VALUE_MULTIPLE = new BigDecimal("2.0");

    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;
    private final RetentionImpactService retentionImpact;

    public OverviewStatsService(CustomerRepository customers,
                                RetentionFlagRepository flags,
                                OfferRepository offers,
                                RetentionImpactService retentionImpact) {
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
        this.retentionImpact = retentionImpact;
    }

    /** The dashboard's own default period, for callers with no selector in front of them. */
    @Transactional(readOnly = true)
    public OverviewStatsResponse forBusiness(UUID businessId) {
        return forBusiness(businessId, ReportingWindow.DEFAULT);
    }

    @Transactional(readOnly = true)
    public OverviewStatsResponse forBusiness(UUID businessId, ReportingWindow window) {
        Instant now = Instant.now();

        long monitored = customers.countByBusinessIdAndAvgIntervalDaysIsNotNull(businessId);
        long belowThreshold = customers.countByBusinessIdAndAvgIntervalDaysIsNull(businessId);

        long active = flags.countByBusinessIdAndStatus(businessId, FlagStatus.ACTIVE);
        long resolved = flags.countByBusinessIdAndStatus(businessId, FlagStatus.RESOLVED);

        // The breakdown is built from the customer side, and the four buckets are
        // disjoint by construction, so it sums to the customer base. See
        // StatusBreakdown: a dashboard whose cards do not reconcile teaches the
        // reader to distrust all of them.
        long returned = customers.countReturnedAndNotCurrentlyFlagged(businessId);
        long onRhythm = customers.countOnRhythmNeverFlagged(businessId);
        long total = monitored + belowThreshold;

        return new OverviewStatsResponse(
                total,
                monitored,
                belowThreshold,
                active,
                resolved,
                // Offers sent is an event with a timestamp, so it belongs to the
                // period — and it has to, because the trend arrow beside it is
                // computed over exactly these rows. An all-time headline under a
                // period arrow invites the reader to compare two things that
                // aren't comparable.
                offers.countByBusinessIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                        businessId, windowStart(now, window), now),
                // Waiting on contact details is a backlog, not an event: how many
                // are stuck right now. Deliberately unwindowed, and the sub-line
                // on the card says "waiting" rather than counting the period.
                offers.countByBusinessIdAndStatus(businessId, OfferStatus.NO_CONTACT),
                reportingPeriod(window),
                recovery(businessId, now, window),
                activitySeries(businessId, window),
                comparison(businessId, now, window),
                new StatusBreakdown(active, returned, onRhythm, belowThreshold, total),
                overdueBuckets(businessId),
                flags.countHighValueActiveFlags(businessId, HIGH_VALUE_MULTIPLE),
                definitions());
    }

    private static ReportingPeriod reportingPeriod(ReportingWindow window) {
        return new ReportingPeriod(
                window.name(),
                window.label(),
                window.days(),
                window.bucket() == ChronoUnit.WEEKS ? "week" : "day");
    }

    private Recovery recovery(UUID businessId, Instant now, ReportingWindow window) {
        Instant from = windowStart(now, window);
        RecoveryCohort cohort = retentionImpact.cohort(businessId, from, now);
        return new Recovery(
                cohort.contacted(),
                cohort.returned(),
                cohort.windowDays(),
                cohort.percent(),
                // Same period as the rate beside it. An all-time figure sitting
                // under a windowed percentage reads as part of the same claim.
                flags.countFlagsWithoutDeliveredOfferSince(businessId, from));
    }

    private static Instant windowStart(Instant now, ReportingWindow window) {
        return now.minus(window.days(), ChronoUnit.DAYS);
    }

    /**
     * The reporting period against the one immediately before it, of equal
     * length so the two sides are comparable whatever the selector says.
     *
     * <p>Restricted to metrics that are events with a timestamp. "Customers
     * monitored" and "currently quiet" are deliberately absent: they describe
     * how things stand now, and nothing in the schema records how they stood a
     * period ago, so any arrow next to them would be reconstructed rather than
     * measured.
     */
    private PeriodComparison comparison(UUID businessId, Instant now, ReportingWindow window) {
        Instant currentFrom = windowStart(now, window);
        Instant previousFrom = currentFrom.minus(window.days(), ChronoUnit.DAYS);

        long raisedNow = flags.countByBusinessIdAndFlaggedAtGreaterThanEqualAndFlaggedAtLessThan(
                businessId, currentFrom, now);
        long raisedBefore = flags.countByBusinessIdAndFlaggedAtGreaterThanEqualAndFlaggedAtLessThan(
                businessId, previousFrom, currentFrom);

        long recoveredNow = flags.countByBusinessIdAndResolvedAtGreaterThanEqualAndResolvedAtLessThan(
                businessId, currentFrom, now);
        long recoveredBefore = flags.countByBusinessIdAndResolvedAtGreaterThanEqualAndResolvedAtLessThan(
                businessId, previousFrom, currentFrom);

        long sentNow = offers.countByBusinessIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                businessId, currentFrom, now);
        long sentBefore = offers.countByBusinessIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                businessId, previousFrom, currentFrom);

        // The same cohort definition as the recovery card, bounded to each period
        // by delivery time. A trend arrow computed from a different filter than
        // the number it sits under is the exact inconsistency this screen is
        // supposed to have stopped showing.
        BigDecimal recoveryNow = retentionImpact.cohort(businessId, currentFrom, now).percent();
        BigDecimal recoveryBefore =
                retentionImpact.cohort(businessId, previousFrom, currentFrom).percent();

        return new PeriodComparison(
                raisedNow, raisedBefore, changePercent(raisedNow, raisedBefore),
                recoveredNow, recoveredBefore, changePercent(recoveredNow, recoveredBefore),
                sentNow, sentBefore, changePercent(sentNow, sentBefore),
                recoveryNow, recoveryBefore,
                recoveryNow == null || recoveryBefore == null
                        ? null : recoveryNow.subtract(recoveryBefore));
    }

    private List<OverviewStatsResponse.OverdueBucket> overdueBuckets(UUID businessId) {
        return flags.countActiveFlagsByOverdueBucket(businessId).stream()
                .map(row -> new OverviewStatsResponse.OverdueBucket(row.getBucket(), row.getTotal()))
                .toList();
    }

    /**
     * @return null when {@code before} is zero. Growth from nothing has no
     *         percentage, and rendering it as "+100%" or "+∞" would dress up
     *         the first week of data as a trend.
     */
    private static BigDecimal changePercent(long now, long before) {
        if (before == 0) {
            return null;
        }
        return BigDecimal.valueOf(now - before)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before), 1, RoundingMode.HALF_UP);
    }

    /**
     * Activity across the reporting period, oldest first, **including buckets
     * where nothing happened**.
     *
     * <p>The zero-filling is the point: the queries return only days with rows,
     * and rendering those directly would close the gaps and draw a quiet
     * fortnight as though it were continuous activity.
     *
     * <p>The division follows the window rather than being fixed. A week is a
     * useful cut of eight weeks and a useless one of seven days, where it would
     * collapse the chart to a single bar. The current week is partial and drawn
     * as such — dropping it would hide the week the operator is standing in, and
     * scaling it up to a full week would invent activity.
     */
    private List<SeriesPoint> activitySeries(UUID businessId, ReportingWindow window) {
        boolean weekly = window.bucket() == ChronoUnit.WEEKS;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int buckets = weekly ? window.days() / 7 : window.days();
        LocalDate lastBucketStart = weekly ? today.with(DayOfWeek.MONDAY) : today;
        LocalDate firstBucketStart = weekly
                ? lastBucketStart.minusWeeks(buckets - 1L)
                : lastBucketStart.minusDays(buckets - 1L);

        Instant from = firstBucketStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<LocalDate, Long> raised = byBucket(flags.countFlagsRaisedByDay(businessId, from), weekly);
        Map<LocalDate, Long> recovered =
                byBucket(flags.countCustomersRecoveredByDay(businessId, from), weekly);

        List<SeriesPoint> series = new ArrayList<>(buckets);
        for (int index = 0; index < buckets; index++) {
            LocalDate bucketStart = weekly
                    ? firstBucketStart.plusWeeks(index)
                    : firstBucketStart.plusDays(index);
            series.add(new SeriesPoint(
                    bucketStart,
                    raised.getOrDefault(bucketStart, 0L),
                    recovered.getOrDefault(bucketStart, 0L)));
        }
        return series;
    }

    /**
     * Rolls day rows up into the bucket the window draws, summing what lands in
     * each. A day at UTC midnight falls into exactly the Monday that truncating
     * to the week would have produced, so the two agree rather than merely
     * resembling each other.
     */
    private static Map<LocalDate, Long> byBucket(List<TimeBucketCount> rows, boolean weekly) {
        Map<LocalDate, Long> totals = new HashMap<>();
        for (TimeBucketCount row : rows) {
            LocalDate day = row.getBucketStart().toLocalDate();
            totals.merge(weekly ? day.with(DayOfWeek.MONDAY) : day, row.getTotal(), Long::sum);
        }
        return totals;
    }

    /**
     * What each figure on the screen means, in the words an operator would use.
     *
     * <p>Sent with the numbers rather than kept in documentation, because the
     * person reading a recovery rate needs to know its denominator at the moment
     * they read it — and a rate whose definition only an engineer can supply is
     * one nobody can act on or defend in a meeting.
     */
    private Definitions definitions() {
        return new Definitions(
                "Customers with at least three separate visits, so a personal rhythm can be "
                        + "measured. Only these customers can be flagged.",
                "Customers seen fewer than three times. They are tracked and their spend is "
                        + "counted, but there is no rhythm yet to drift from.",
                "Of the customers whose offer was actually delivered, how many bought again "
                        + "within the recovery window. Flags whose offer never reached anyone are "
                        + "shown separately and are not counted as misses.",
                "Every customer of the business, split four ways. The four numbers add up to the "
                        + "total: drifting today, returned and not drifting now, on rhythm and "
                        + "never flagged, and too little history to judge.",
                "Drifting customers whose total spend is at least twice the average customer's. "
                        + "The threshold scales with the business, so it means the same thing at a "
                        + "coffee shop and at a restaurant.");
    }
}
