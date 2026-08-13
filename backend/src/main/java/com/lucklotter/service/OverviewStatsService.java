package com.lucklotter.service;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.repo.WeeklyCount;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.OverviewStatsResponse.RecoveryRate;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates for the overview screen (FR-7), scoped to one business (NFR-1). */
@Service
public class OverviewStatsService {

    /** Eight weeks including the current, partial one. */
    static final int WEEKS = 8;

    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;

    public OverviewStatsService(CustomerRepository customers,
                                RetentionFlagRepository flags,
                                OfferRepository offers) {
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
    }

    @Transactional(readOnly = true)
    public OverviewStatsResponse forBusiness(UUID businessId) {
        long monitored = customers.countByBusinessIdAndAvgIntervalDaysIsNotNull(businessId);
        long belowThreshold = customers.countByBusinessIdAndAvgIntervalDaysIsNull(businessId);

        long active = flags.countByBusinessIdAndStatus(businessId, FlagStatus.ACTIVE);
        long resolved = flags.countByBusinessIdAndStatus(businessId, FlagStatus.RESOLVED);

        return new OverviewStatsResponse(
                monitored,
                belowThreshold,
                active,
                resolved,
                offers.countByBusinessIdAndStatus(businessId, OfferStatus.SENT),
                offers.countByBusinessIdAndStatus(businessId, OfferStatus.NO_CONTACT),
                recoveryRate(resolved, active + resolved),
                weeklySeries(businessId),
                comparison(businessId),
                new StatusBreakdown(resolved, active, belowThreshold),
                overdueBuckets(businessId));
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
    private OverviewStatsResponse.PeriodComparison comparison(UUID businessId) {
        Instant now = Instant.now();
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

        BigDecimal recoveryNow = ratio(recoveredNow, raisedNow);
        BigDecimal recoveryBefore = ratio(recoveredBefore, raisedBefore);

        return new OverviewStatsResponse.PeriodComparison(
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

    /** @return null when there is no denominator — no rate, as opposed to 0%. */
    private static BigDecimal ratio(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /**
     * Recovery carries its own denominator, so a card can show what the
     * percentage is actually made of.
     */
    private static RecoveryRate recoveryRate(long recovered, long totalFlags) {
        if (totalFlags == 0) {
            // No flags yet means no rate — distinct from a rate of 0%, which
            // would claim nobody came back.
            return new RecoveryRate(0, 0, null);
        }
        BigDecimal percent = BigDecimal.valueOf(recovered)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalFlags), 1, RoundingMode.HALF_UP);
        return new RecoveryRate(recovered, totalFlags, percent);
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

        List<WeeklyPoint> series = new java.util.ArrayList<>(WEEKS);
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
}
