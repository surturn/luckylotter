package com.lucklotter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregate counters for the overview screen (FR-7).
 *
 * <p>Computed server-side in one pass. The alternative — counting a paginated
 * flag list on the client — is wrong either way round: count one page and the
 * numbers are simply incorrect, or fetch every page and the client performs a
 * table scan to produce a single integer.
 */
public record OverviewStatsResponse(
    /** Customers with enough history to have a cadence, i.e. flaggable. */
    long customersMonitored,
    /** Seen, but under {@code MIN_TRANSACTIONS} — no rhythm yet, never flagged. */
    long customersBelowThreshold,
    long activeFlags,
    long resolvedFlags,
    long offersSent,
    long offersNoContact,
    RecoveryRate recoveryRate,
    /** Exactly 8 entries, oldest first, including weeks where nothing happened. */
    List<WeeklyPoint> weeklySeries
) {

    /**
     * Recovery as a fraction, not just a percentage.
     *
     * <p>The denominator travels with the number because "100% recovered" over
     * two flags and "68%" over four hundred are entirely different claims, and
     * a card showing only the former would overstate the system's evidence.
     * The §11 precision audit needs the same denominator.
     *
     * @param percent null when {@code totalFlags} is zero — there is no rate
     *                yet, which is not the same as a rate of 0%
     */
    public record RecoveryRate(long recovered, long totalFlags, BigDecimal percent) {
    }

    /**
     * One week of activity.
     *
     * @param weekStart          Monday of the week, UTC
     * @param flagsRaised        flags opened during the week
     * @param customersRecovered flags resolved during the week — a customer who
     *                           came back
     */
    public record WeeklyPoint(LocalDate weekStart, long flagsRaised, long customersRecovered) {
    }
}
