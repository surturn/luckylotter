package com.lucklotter.service;

import com.lucklotter.domain.RetentionConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Turns a customer's transaction history into their average visit interval
 * (FR-2).
 *
 * <p>The unit is the <em>visit</em>, not the transaction row. Transactions
 * within {@link RetentionConstants#MIN_VISIT_GAP} of each other are one trip to
 * the counter that happened to be rung up more than once, and are collapsed
 * before anything is measured — including the
 * {@link RetentionConstants#MIN_TRANSACTIONS} minimum, so three receipts from a
 * customer's first and only visit is still no cadence rather than a very short
 * one. See that constant for why this matters to the trigger.
 *
 * <p>Across the collapsed visits the mean of the consecutive gaps telescopes:
 * every intermediate visit cancels, leaving
 * {@code (last - first) / (visits - 1)}. Collapsing needs the visits in order,
 * so this sorts; the answer is still order-insensitive, and a backdated
 * transaction arriving late produces the same cadence as if it had arrived in
 * sequence.
 *
 * <p>Consequence worth knowing: this is a mean over the customer's <em>whole</em>
 * history, so a customer whose rhythm changed (weekly for a year, then monthly)
 * reads as something in between. A trailing window would track a changing
 * rhythm better; see the open question in {@code PRD-TODOS.md}.
 */
public final class CadenceCalculator {

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);

    private CadenceCalculator() {
    }

    /**
     * Collapses transaction timestamps into the visits they describe.
     *
     * <p>Each cluster is represented by its earliest timestamp — when the
     * customer arrived, rather than whenever the last item was rung up.
     *
     * @param transactionTimestamps every transaction for one customer, in any order
     * @return one timestamp per visit, oldest first
     */
    public static List<Instant> distinctVisits(Collection<Instant> transactionTimestamps) {
        List<Instant> ordered = new ArrayList<>(transactionTimestamps);
        Collections.sort(ordered);

        List<Instant> visits = new ArrayList<>(ordered.size());
        Instant currentVisit = null;
        for (Instant timestamp : ordered) {
            if (currentVisit == null
                    || Duration.between(currentVisit, timestamp)
                            .compareTo(RetentionConstants.MIN_VISIT_GAP) >= 0) {
                visits.add(timestamp);
                currentVisit = timestamp;
            }
        }
        return visits;
    }

    /**
     * @param transactionTimestamps every transaction for one customer, in any order
     * @return the average interval in days, or {@code null} when the customer
     *         has no meaningful cadence yet — fewer than
     *         {@link RetentionConstants#MIN_TRANSACTIONS} <em>visits</em>, however
     *         many rows those visits arrived as. A null cadence means "not
     *         flaggable" (FR-2), and is also what the
     *         {@code avg_interval_days > 0} check constraint demands.
     */
    public static BigDecimal averageIntervalDays(Collection<Instant> transactionTimestamps) {
        List<Instant> visits = distinctVisits(transactionTimestamps);
        if (visits.size() < RetentionConstants.MIN_TRANSACTIONS) {
            return null;
        }

        Instant first = visits.get(0);
        Instant last = visits.get(visits.size() - 1);
        long spanSeconds = Duration.between(first, last).getSeconds();
        if (spanSeconds <= 0) {
            // Unreachable while MIN_VISIT_GAP is positive — two counted visits
            // are always at least that far apart. Kept so the check constraint
            // stays enforced here even if that constant ever goes to zero.
            return null;
        }
        return BigDecimal.valueOf(spanSeconds)
                .divide(SECONDS_PER_DAY, 6, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(visits.size() - 1L), 2, RoundingMode.HALF_UP);
    }
}
