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
    /** Delivered inside the reporting period, matching the arrow beside it. */
    long offersSent,
    /** Offers stuck for want of contact details. A backlog right now, not a count for the period. */
    long offersNoContact,
    /**
     * Which reporting period these figures cover, and what it is divided into.
     *
     * <p>Sent with the numbers because a period is not a display preference: a
     * recovery rate over seven days and one over eight weeks are different
     * claims, and a caller that renders either without saying which has shown
     * the reader a number they cannot interpret.
     */
    ReportingPeriod reportingPeriod,
    /** Recovery over delivered offers <em>inside the reporting period</em>. */
    Recovery recovery,
    /**
     * Activity over the reporting period, oldest first and including buckets
     * where nothing happened. One entry per bucket, so the length follows the
     * period: seven or thirty days, or eight weeks.
     */
    List<SeriesPoint> activitySeries,
    /** The reporting period against the one of equal length before it. */
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
     * <p>The denominator is <em>offers delivered inside the reporting period</em>,
     * not flags raised: a flag whose offer never reached the customer is reported
     * separately and is not counted as an attempt that failed. The bound is on
     * when the offer was sent, not on {@code windowDays} — that is the customer's
     * own recovery window, a property of the business, and it applies within
     * every reporting period rather than defining one.
     *
     * @param percent null when {@code contacted} is zero — there is no rate
     *                yet, which is not the same as a rate of 0%
     */
    public record Recovery(long contacted,
                           long returned,
                           int windowDays,
                           BigDecimal percent,
                           /** Flags in the same period that no offer ever reached. */
                           long neverContacted) {
    }

    /**
     * The reporting period a set of figures was measured over.
     *
     * <p>Carries no dates: the series and the comparison each bound themselves
     * from the same {@code days}, and a pair of instants on top of that would be
     * a second answer to a question already answered — the one that goes stale
     * the moment the two disagree.
     *
     * @param key   the selector value, e.g. {@code LAST_8_WEEKS}
     * @param label the same thing in the operator's words, e.g. "Last 8 weeks"
     * @param unit  {@code day} or {@code week} — what one series point covers
     */
    public record ReportingPeriod(String key, String label, int days, String unit) {
    }

    /**
     * One bucket of activity — a day or a week, per
     * {@link ReportingPeriod#unit()}.
     *
     * @param bucketStart        midnight UTC of the day, or the Monday of the
     *                           week
     * @param flagsRaised        flags opened during the bucket
     * @param customersRecovered flags resolved during the bucket — customers who
     *                           came back
     */
    public record SeriesPoint(LocalDate bucketStart, long flagsRaised, long customersRecovered) {
    }

    /**
     * The reporting period against the one of equal length before it.
     *
     * <p>Only covers metrics that are <em>events in time</em> — flags raised,
     * customers recovered, offers sent. Deliberately not "customers monitored"
     * or "currently quiet": those are counts of how things stand right now, and
     * nothing records how they stood a period ago. Showing a trend arrow against
     * them would mean reconstructing a past the database never stored.
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
