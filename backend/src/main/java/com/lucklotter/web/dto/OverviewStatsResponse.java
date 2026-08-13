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
    /** Seen, but under {@code MIN_TRANSACTIONS} visits — no rhythm yet, never flagged. */
    long customersBelowThreshold,
    long activeFlags,
    long resolvedFlags,
    long offersSent,
    long offersNoContact,
    RecoveryRate recoveryRate,
    /** Exactly 8 entries, oldest first, including weeks where nothing happened. */
    List<WeeklyPoint> weeklySeries,
    /** This 8-week period against the previous one. */
    PeriodComparison comparison,
    /** How the whole customer base currently splits. */
    StatusBreakdown statusBreakdown,
    /** Severity spread of the currently-quiet customers; empty when none are. */
    List<OverdueBucket> overdueBuckets
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

    /**
     * This period against the one before it, both eight weeks long.
     *
     * <p>Only covers metrics that are <em>events in time</em> — flags raised,
     * customers recovered, offers sent. Deliberately not "customers monitored"
     * or "currently quiet": those are counts of how things stand right now, and
     * nothing records how they stood eight weeks ago. Showing a trend arrow
     * against them would mean reconstructing a past the database never stored.
     *
     * @param changePercent null when the previous period had nothing to compare
     *                      against — growth from zero is not a percentage, and
     *                      rendering it as one invents precision
     */
    public record PeriodComparison(long flagsRaisedNow,
                                   long flagsRaisedBefore,
                                   BigDecimal flagsRaisedChangePercent,
                                   long recoveredNow,
                                   long recoveredBefore,
                                   BigDecimal recoveredChangePercent,
                                   long offersSentNow,
                                   long offersSentBefore,
                                   BigDecimal offersSentChangePercent,
                                   BigDecimal recoveryPercentNow,
                                   BigDecimal recoveryPercentBefore,
                                   BigDecimal recoveryPercentChange) {
    }

    /**
     * How the monitored population currently splits. The three add up to every
     * customer the business has, which is why "not enough data" is included
     * rather than quietly dropped — those customers are seen but unflaggable.
     */
    public record StatusBreakdown(long cameBack, long stillQuiet, long notEnoughData) {
    }

    /**
     * How far past their own threshold the currently-quiet customers are.
     *
     * <p>This is the honest version of "why were these customers flagged".
     * There is exactly one trigger — a break in the customer's own rhythm — so
     * the real variation is severity, not reason. Measured as a multiple of each
     * customer's threshold, because ten quiet days is unremarkable for a
     * quarterly visitor and alarming for a weekly one.
     *
     * @param bucket one of {@code JUST_PAST}, {@code WELL_PAST}, {@code LONG_OVERDUE}
     */
    public record OverdueBucket(String bucket, long customers) {
    }
}
