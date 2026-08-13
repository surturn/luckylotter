package com.lucklotter.domain;

import java.time.Duration;

/** Fixed, non-configurable tuning constants for the retention engine. */
public final class RetentionConstants {

    /**
     * <em>Visits</em> a customer must have before {@code avg_interval_days} is
     * computed and they become flaggable (FR-2, FR-3). Two visits give one
     * interval — not enough to call a rhythm. Deliberately a constant, not
     * per-business config, in Phase 1: no exceptions.
     *
     * <p>Counted in visits, not transaction rows — see {@link #MIN_VISIT_GAP}.
     * The name is kept because it is exposed read-only as {@code minTransactions}
     * on the business config API and cited throughout the PRD.
     */
    public static final int MIN_TRANSACTIONS = 3;

    /**
     * How far apart two transactions must be before they count as two separate
     * visits.
     *
     * <p>A POS export is a list of sales, not a list of trips to the counter.
     * One visit routinely arrives as several rows: a split bill, items rung up
     * separately, a till that exports line items. Counting those as distinct
     * visits shortens the learned cadence without lengthening the span the
     * average is taken over, which drags the flag threshold down toward
     * {@code min_threshold_days} and wins the customer an offer far sooner than
     * their real rhythm justifies. That happens by accident on honest data, and
     * is the cheapest way to game the trigger on purpose.
     *
     * <p>Six hours separates "paid in three goes at one counter" from "came back
     * later in the day", which is a real second visit and must survive. It is
     * also a floor on the average: consecutive counted visits are at least this
     * far apart, so the cadence can never round to zero and violate the
     * {@code avg_interval_days > 0} check constraint.
     */
    public static final Duration MIN_VISIT_GAP = Duration.ofHours(6);

    /**
     * How many recent visits the rhythm chart shows (FR-7). Enough to make a
     * regular cadence and the break in it obvious, without turning a table row
     * into a dense plot.
     */
    public static final int VISIT_HISTORY_LIMIT = 12;

    private RetentionConstants() {
    }
}
