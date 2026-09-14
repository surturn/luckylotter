package com.lucklotter.service;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.repo.WeeklyCount;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.OverviewStatsResponse.Definitions;
import com.lucklotter.web.dto.OverviewStatsResponse.PeriodComparison;
import com.lucklotter.web.dto.OverviewStatsResponse.Recovery;
import com.lucklotter.web.dto.OverviewStatsResponse.StatusBreakdown;
import com.lucklotter.web.dto.OverviewStatsResponse.WeeklyPoint;
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
import java.util.stream.Collectors;

/** Aggregates for the overview screen (FR-7), scoped to one business (NFR-1). */
@Service
public class OverviewStatsService {

    /** Eight weeks including the current, partial one. */
    static final int WEEKS = 8;

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

    @Transactional(readOnly = true)
    public OverviewStatsResponse forBusiness(UUID businessId) {
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
                offers.countByBusinessIdAndStatus(businessId, OfferStatus.SENT),
                offers.countByBusinessIdAndStatus(businessId, OfferStatus.NO_CONTACT),
                recovery(businessId, now),
                weeklySeries(businessId),
                comparison(businessId, now),
                new StatusBreakdown(active, returned, onRhythm, belowThreshold, total),
                overdueBuckets(businessId),
                flags.countHighValueActiveFlags(businessId, HIGH_VALUE_MULTIPLE),
                definitions());
    }

    private Recovery recovery(UUID businessId, Instant now) {
        RecoveryCohort cohort = retentionImpact.cohort(businessId, Instant.EPOCH, now);
        return new Recovery(
                cohort.contacted(),
                cohort.returned(),
                cohort.windowDays(),
                cohort.percent(),
                flags.countFlagsWithoutDeliveredOffer(businessId));
    }

    /**
     * This eight-week period against the one immediately before it.
     *
     * <p>Restricted to metrics that are events with a timestamp. "Customers
     * monitored" and "currently quiet" are deliberately absent: they describe
     * how things stand now, and nothing in the schema records how they stood
     * eight weeks ago, so any arrow next to them would be reconstructed rather
     * than measured.
     */
    private PeriodComparison comparison(UUID businessId, Instant now) {
        Instant currentFrom = now.minus(WEEKS * 7L, ChronoUnit.DAYS);
        Instant previousFrom = currentFrom.minus(WEEKS * 7L, ChronoUnit.DAYS);

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
     * Eight weeks, oldest first, **including weeks where nothing happened**.
     *
     * <p>The zero-filling is the point: the queries return only weeks with
     * rows, and rendering those directly would close the gaps and draw a quiet
     * fortnight as though it were continuous activity.
     */
    private List<WeeklyPoint> weeklySeries(UUID businessId) {
        LocalDate currentWeekStart = LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY);
        LocalDate firstWeekStart = currentWeekStart.minusWeeks(WEEKS - 1L);
        Instant from = firstWeekStart.atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> raised = byWeek(flags.countFlagsRaisedByWeek(businessId, from));
        Map<LocalDate, Long> recovered = byWeek(flags.countCustomersRecoveredByWeek(businessId, from));

        List<WeeklyPoint> series = new ArrayList<>(WEEKS);
        for (int week = 0; week < WEEKS; week++) {
            LocalDate weekStart = firstWeekStart.plusWeeks(week);
            series.add(new WeeklyPoint(
                    weekStart,
                    raised.getOrDefault(weekStart, 0L),
                    recovered.getOrDefault(weekStart, 0L)));
        }
        return series;
    }

    private static Map<LocalDate, Long> byWeek(List<WeeklyCount> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> row.getWeekStart().toLocalDate(),
                WeeklyCount::getTotal,
                Long::sum,
                HashMap::new));
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
