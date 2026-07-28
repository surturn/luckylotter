package com.lucklotter.service;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionConstants;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.repo.WeeklyCount;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.OverviewStatsResponse.RecoveryRate;
import com.lucklotter.web.dto.OverviewStatsResponse.WeeklyPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
        long monitored = customers.countByBusinessIdAndTransactionCountGreaterThanEqual(
                businessId, RetentionConstants.MIN_TRANSACTIONS);
        long belowThreshold = customers.countByBusinessIdAndTransactionCountLessThan(
                businessId, RetentionConstants.MIN_TRANSACTIONS);

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
                weeklySeries(businessId));
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
