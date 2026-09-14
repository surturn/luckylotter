package com.lucklotter.repo;

import java.math.BigDecimal;

/**
 * The counters behind the retention-impact figures, from one aggregate row.
 *
 * <p>An interface projection rather than a record because the query is native:
 * Spring Data can bind a native result to an interface, but a record would need
 * a {@code @SqlResultSetMapping} to be constructed at all. The same shape as
 * {@link TimeBucketCount} and {@link BucketCount}, for the same reason.
 *
 * <p>Everything the dashboard claims about money is derived from this one query,
 * so the count of returns and the revenue attributed to those returns come from
 * the same predicate over the same rows and cannot drift apart the way two
 * separately-written filters would.
 */
public interface RetentionImpactRow {

    /** Flags in the window whose offer was actually delivered. */
    long getContacted();

    /** Of those, how many bought again inside their own recovery window. */
    long getReturned();

    /** What those customers spent inside their windows. */
    BigDecimal getRevenueFromReturned();

    /**
     * Upper bound for a percentage deal — the discount is taken to have applied
     * to the whole returned basket. Excludes free items, which have no price.
     */
    BigDecimal getInterventionCost();

    /** Delivered offers that cannot be costed in currency at all. */
    long getFreeItemOffers();
}
