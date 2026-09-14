package com.lucklotter.repo;

import java.math.BigDecimal;

/**
 * How far apart the customers' rhythms are (§7), from one aggregate row.
 *
 * <p>Interface projection rather than a record because the query is native:
 * Spring Data can bind a native result to an interface, but a record would need
 * a {@code @SqlResultSetMapping} to be constructed at all. The same shape as
 * {@link TimeBucketCount} and {@link BucketCount}, for the same reason.
 *
 * <p>The quartiles come back as floating point because
 * {@code PERCENTILE_CONT} interpolates — the 25th percentile of 7, 8 and 30 days
 * is 7.5 days, not one of the values — and are rounded to a day's tenth by the
 * service, which is finer than anything an operator acts on. Every one of them
 * is null when no customer has a cadence.
 */
public interface CadenceSpreadRow {

    /** Customers with a measurable rhythm; zero is possible, nulls accompany it. */
    long getCustomers();

    BigDecimal getP25Days();

    BigDecimal getMedianDays();

    BigDecimal getP75Days();

    BigDecimal getMeanDays();
}
