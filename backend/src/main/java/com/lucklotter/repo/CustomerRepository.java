package com.lucklotter.repo;

import com.lucklotter.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByBusinessIdAndExternalRef(UUID businessId, String externalRef);

    /**
     * Every customer of one business, for the cadence rebuild (FR-2).
     *
     * <p>Unpaged on purpose: the rebuild has to see the whole population or it
     * leaves some customers on a stale cadence, which is the condition it
     * exists to clear. Fine at pilot scale; if a business ever outgrows one
     * page of customers this needs to stream rather than list.
     */
    List<Customer> findByBusinessId(UUID businessId);

    /**
     * Customers with enough history to have a cadence — the flaggable
     * population (FR-2).
     *
     * <p>Counted on the cadence itself rather than on {@code transaction_count},
     * because rows over-count visits: a customer whose only trip was rung up
     * three times has three transactions, no cadence, and cannot be flagged.
     * Counting rows would report them to the admin as monitored when they are
     * not. This and {@link #countByBusinessIdAndAvgIntervalDaysIsNull} partition
     * the business's customers exactly.
     */
    long countByBusinessIdAndAvgIntervalDaysIsNotNull(UUID businessId);

    /** Seen, but with no rhythm yet, so never flagged (FR-2). */
    long countByBusinessIdAndAvgIntervalDaysIsNull(UUID businessId);

    /**
     * Customers who have been flagged at some point and are not flagged now —
     * the ones who came back and stayed back (FR-7).
     *
     * <p>Counted over {@code customers}, not over flags: a customer who lapsed
     * and returned three times is one recovered customer and three resolved
     * flags, and the overview's breakdown has to add up to the customer base
     * rather than to the flag table. "Not flagged now" is the other half of the
     * definition — a customer who came back and has since gone quiet again
     * belongs under "drifting today", which is the more useful thing to tell
     * someone deciding who to contact this morning.
     */
    @Query(value = """
        SELECT COUNT(*)
        FROM customers c
        WHERE c.business_id = :businessId
          AND EXISTS (
              SELECT 1 FROM retention_flags f
              WHERE f.customer_id = c.id AND f.status = 'RESOLVED'
          )
          AND NOT EXISTS (
              SELECT 1 FROM retention_flags f
              WHERE f.customer_id = c.id AND f.status = 'ACTIVE'
          )
        """, nativeQuery = true)
    long countReturnedAndNotCurrentlyFlagged(@Param("businessId") UUID businessId);

    /**
     * Customers with an established rhythm who have never been flagged — the
     * ones the engine is watching and finding nothing wrong with (FR-7).
     *
     * <p>The remaining quarter of the breakdown. It is on the dashboard because
     * without it "28 monitored, 5 drifting, 12 returned" leaves the reader to
     * work out that the other eleven are fine, and a number the reader has to
     * derive is one they will get wrong.
     */
    @Query(value = """
        SELECT COUNT(*)
        FROM customers c
        WHERE c.business_id = :businessId
          AND c.avg_interval_days IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM retention_flags f WHERE f.customer_id = c.id
          )
        """, nativeQuery = true)
    long countOnRhythmNeverFlagged(@Param("businessId") UUID businessId);

    /**
     * The spread of rhythm across the customers who have one (§7).
     *
     * <p>Quartiles rather than a mean alone: a customer base of weekly regulars
     * with three quarterly visitors among them has a mean that describes nobody,
     * and the mean alone would let a handful of outliers define what "typical"
     * means. The mean is returned beside them because the gap between it and the
     * median is itself a fact worth seeing — when they diverge, the base is
     * lopsided and any single number about it is a distortion.
     *
     * <p>Returns one row even for a business with no customers at all: the count
     * is then zero and every percentile null, which is the honest answer and not
     * the same as a typical interval of zero days.
     */
    @Query(value = """
        SELECT COUNT(*)                                                      AS "customers",
               PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY avg_interval_days) AS "p25Days",
               PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY avg_interval_days) AS "medianDays",
               PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY avg_interval_days) AS "p75Days",
               AVG(avg_interval_days)                                        AS "meanDays"
        FROM customers
        WHERE business_id = :businessId
          AND avg_interval_days IS NOT NULL
        """, nativeQuery = true)
    CadenceSpreadRow cadenceSpread(@Param("businessId") UUID businessId);

    /**
     * Customers per rhythm band (§7), the "cadence segments" of the diagnostic.
     *
     * <p>Bands are on the customer's own interval, not on the threshold the
     * engine applied: the threshold carries the business's sensitivity multiplier
     * and clamp, so segmenting on it would describe the settings rather than the
     * customers.
     *
     * <p>Only customers with a cadence are counted, which makes the bands sum to
     * {@link #countByBusinessIdAndAvgIntervalDaysIsNotNull} — the same population
     * the overview calls monitored.
     */
    @Query(value = """
        SELECT CASE
                 WHEN avg_interval_days <= 10  THEN 'WEEKLY'
                 WHEN avg_interval_days <= 21  THEN 'FORTNIGHTLY'
                 WHEN avg_interval_days <= 45  THEN 'MONTHLY'
                 WHEN avg_interval_days <= 120 THEN 'QUARTERLY'
                 ELSE 'RARER'
               END AS "bucket",
               COUNT(*) AS "total"
        FROM customers
        WHERE business_id = :businessId
          AND avg_interval_days IS NOT NULL
        GROUP BY 1
        """, nativeQuery = true)
    List<BucketCount> countCustomersByCadenceSegment(@Param("businessId") UUID businessId);

    /**
     * Customers per purchase-count band (§7), over the whole base.
     *
     * <p>Counts purchases on record rather than visits, consistent with
     * {@code transaction_count} everywhere else — a split bill is several rows
     * and one visit, and the column that says so is the one the engine gates on.
     *
     * <p>The first band is exactly the population that can never be flagged, so
     * this distribution reconciles with the overview's "too little history"
     * figure instead of quietly disagreeing with it.
     */
    @Query(value = """
        SELECT CASE
                 WHEN transaction_count <= 2  THEN 'ONE_OR_TWO'
                 WHEN transaction_count <= 5  THEN 'THREE_TO_FIVE'
                 WHEN transaction_count <= 10 THEN 'SIX_TO_TEN'
                 WHEN transaction_count <= 20 THEN 'ELEVEN_TO_TWENTY'
                 ELSE 'TWENTY_ONE_PLUS'
               END AS "bucket",
               COUNT(*) AS "total"
        FROM customers
        WHERE business_id = :businessId
        GROUP BY 1
        """, nativeQuery = true)
    List<BucketCount> countCustomersByPurchaseCountBand(@Param("businessId") UUID businessId);

    /**
     * At-risk candidates for one business (FR-3). Returns only customers with an
     * established cadence and no open flag; the per-customer threshold
     * comparison itself happens in the service layer via
     * {@code Business.thresholdDaysFor(...)}, since the clamp isn't expressible
     * cleanly in JPQL.
     *
     * <p>{@code cutoff} is a coarse pre-filter — the widest possible threshold,
     * i.e. now minus {@code min_threshold_days} — so the scan stays on the
     * {@code (business_id, last_visit_at)} index rather than loading every
     * customer (NFR-5).
     *
     * <p>{@code minTransactions} is likewise coarse, and deliberately kept even
     * though {@code avgIntervalDays IS NOT NULL} is the real gate: visits can
     * never outnumber rows, so it narrows the scan without ever excluding a
     * genuine candidate.
     */
    @Query("""
        SELECT c FROM Customer c
        WHERE c.business.id = :businessId
          AND c.transactionCount >= :minTransactions
          AND c.avgIntervalDays IS NOT NULL
          AND c.lastVisitAt IS NOT NULL
          AND c.lastVisitAt < :cutoff
          AND NOT EXISTS (
              SELECT 1 FROM RetentionFlag f
              WHERE f.customer = c AND f.status = com.lucklotter.domain.FlagStatus.ACTIVE
          )
        ORDER BY c.lastVisitAt ASC
        """)
    List<Customer> findFlagCandidates(@Param("businessId") UUID businessId,
                                      @Param("minTransactions") int minTransactions,
                                      @Param("cutoff") Instant cutoff);
}
