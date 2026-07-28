package com.lucklotter.service;

import com.lucklotter.domain.RetentionConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Turns a customer's visit history into their average visit interval (FR-2).
 *
 * <p>The mean of the consecutive gaps telescopes: every intermediate visit
 * cancels, leaving {@code (last - first) / (visits - 1)}. So the calculation
 * needs only the extremes and the count, and is order-insensitive — a backdated
 * transaction arriving late produces the same answer as if it had arrived in
 * order.
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
     * @param visitTimestamps every visit for one customer, in any order
     * @return the average interval in days, or {@code null} when the customer
     *         has no meaningful cadence yet — fewer than
     *         {@link RetentionConstants#MIN_TRANSACTIONS} visits, or every visit
     *         at the same instant. A null cadence means "not flaggable" (FR-2),
     *         and is also what the {@code avg_interval_days > 0} check
     *         constraint demands.
     */
    public static BigDecimal averageIntervalDays(Collection<Instant> visitTimestamps) {
        if (visitTimestamps.size() < RetentionConstants.MIN_TRANSACTIONS) {
            return null;
        }
        Instant first = null;
        Instant last = null;
        for (Instant visit : visitTimestamps) {
            if (first == null || visit.isBefore(first)) {
                first = visit;
            }
            if (last == null || visit.isAfter(last)) {
                last = visit;
            }
        }
        long spanSeconds = Duration.between(first, last).getSeconds();
        if (spanSeconds <= 0) {
            return null;
        }
        return BigDecimal.valueOf(spanSeconds)
                .divide(SECONDS_PER_DAY, 6, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(visitTimestamps.size() - 1L), 2, RoundingMode.HALF_UP);
    }
}
