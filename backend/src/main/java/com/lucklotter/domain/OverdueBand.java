package com.lucklotter.domain;

import java.math.BigDecimal;

/**
 * How far past their own threshold a quiet customer is, as a band rather than a
 * number of days.
 *
 * <p>Expressed as a multiple of each customer's own threshold because ten quiet
 * days means something different to a weekly regular and a quarterly one — the
 * same reason the threshold itself is per-customer (FR-3). It is the only
 * honest severity split available: there is exactly one trigger, so the
 * interesting variation between two flagged customers is how far past it each
 * has gone, not why.
 *
 * <p>These two boundaries are also written into the overview's bucket query
 * ({@code RetentionFlagRepository.countActiveFlagsByOverdueBucket}), which
 * groups in SQL and therefore can't call this. If they ever change they have to
 * change in both places, or the flag list's risk column stops reconciling with
 * the overview's bar chart.
 */
public enum OverdueBand {

    /** Just past their gap — flagged, but only recently. */
    JUST_PAST,

    /** Well past it — the lapse is no longer plausibly a holiday. */
    WELL_PAST,

    /** Long overdue — the habit has most likely already broken. */
    LONG_OVERDUE;

    private static final BigDecimal WELL_PAST_FROM = new BigDecimal("1.5");
    private static final BigDecimal LONG_OVERDUE_FROM = new BigDecimal("3");

    /**
     * @param overdueness quiet days divided by the threshold the flag crossed;
     *                    1.0 is the moment of flagging. Null when the customer
     *                    has no last visit on file, or the flag is resolved and
     *                    the question no longer applies.
     */
    public static OverdueBand of(BigDecimal overdueness) {
        if (overdueness == null) {
            return null;
        }
        if (overdueness.compareTo(LONG_OVERDUE_FROM) >= 0) {
            return LONG_OVERDUE;
        }
        return overdueness.compareTo(WELL_PAST_FROM) >= 0 ? WELL_PAST : JUST_PAST;
    }
}
