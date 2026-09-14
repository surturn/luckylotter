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
 *
 * <p>Every count here names its cohort, and {@link Definitions} carries the
 * plain-language version of each. The breakdown is a partition of the customer
 * base and adds up to {@link #customersTotal} exactly, so a reader who adds the
 * cards together gets the number on the first card rather than a puzzle.
 */
public record OverviewStatsResponse(
    /** Every customer of the business, whatever state they are in. */
    long customersTotal,
    /** Customers with enough history to have a cadence, i.e. flaggable. */
    long customersMonitored,
    /** Seen, but under {@code MIN_TRANSACTIONS} visits — no rhythm yet, never flagged. */
    long customersBelowThreshold,
    long activeFlags,
    long resolvedFlags,
    long offersSent,
    long offersNoContact,
    /** Recovery over delivered offers, with its denominator and its window. */
    Recovery recovery,
    /** Exactly 8 entries, oldest first, including weeks where nothing happened. */
    List<WeeklyPoint> weeklySeries,
    /** This 8-week period against the previous one. */
    PeriodComparison comparison,
    /** How the whole customer base currently splits. Adds up to {@code customersTotal}. */
    StatusBreakdown statusBreakdown,
    /** Severity spread of the currently-quiet customers; empty when none are. */
    List<OverdueBucket> overdueBuckets,
    /** Drifting customers whose historical spend is well above the business's average. */
    long highValueDrifting,
    Definitions definitions
) {

    /**
     * Recovery as a fraction, not just a percentage.
     *
     * <p>The denominator travels with the number because "100% recovered" over
     * two flags and "68%" over four hundred are entirely different claims, and
     * a card showing only the former would overstate the system's evidence.
     *
     * <p>The denominator is <em>offers delivered</em>, not flags raised: a flag
     * whose offer never reached the customer is reported separately and is not
     * counted as an attempt that failed.
     *
     * @param percent null when {@code contacted} is zero — there is no rate
     *                yet, which is not the same as a rate of 0%
     */
    public record Recovery(long contacted,
                           long returned,
                           int windowDays,
                           BigDecimal percent,
                           /** Flags raised that no offer ever reached. */
                           long neverContacted) {
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
     * <p>The two recovery percentages use the same cohort definition as the card
     * above, bounded to each period by when the offer was delivered — so the
     * arrow compares like with like instead of putting one filter against
     * another.
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
     * The customer base, split four ways.
     *
     * <p>A partition, not a selection: the four counts are disjoint and sum to
     * {@link #total}. That is the whole point — this is the card that used to
     * mix flag counts with customer counts and leave an operator unable to
     * reconcile the dashboard against itself.
     *
     * <p>A customer who returned and has since gone quiet appears under
     * {@code drifting}, because that is the state they are in today and the
     * action it calls for.
     */
    public record StatusBreakdown(long drifting,
                                  long returned,
                                  long onRhythm,
                                  long notEnoughData,
                                  long total) {
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

    /** Plain-language definitions, rendered beside the cards they explain. */
    public record Definitions(String customersMonitored,
                              String customersBelowThreshold,
                              String recovery,
                              String statusBreakdown,
                              String highValueDrifting) {
    }
}
