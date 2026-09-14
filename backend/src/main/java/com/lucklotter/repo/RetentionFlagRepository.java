package com.lucklotter.repo;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.RetentionFlag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetentionFlagRepository extends JpaRepository<RetentionFlag, UUID> {

    /** The customer's open flag, if any — the FR-9 resolution target. */
    Optional<RetentionFlag> findByCustomerIdAndStatus(UUID customerId, FlagStatus status);

    boolean existsByCustomerIdAndStatus(UUID customerId, FlagStatus status);

    /**
     * Dashboard list, scoped to one business (FR-7, NFR-1). Fetches the customer
     * and offer eagerly so rendering a page doesn't fan out into N+1 queries
     * (NFR-5). Both are to-one associations, so the {@link Pageable} still
     * paginates in SQL.
     */
    @Query("""
        SELECT f FROM RetentionFlag f
        JOIN FETCH f.customer
        LEFT JOIN FETCH f.offer
        WHERE f.business.id = :businessId
        """)
    Page<RetentionFlag> findForDashboard(@Param("businessId") UUID businessId, Pageable pageable);

    /** As {@link #findForDashboard}, narrowed to one flag status. */
    @Query("""
        SELECT f FROM RetentionFlag f
        JOIN FETCH f.customer
        LEFT JOIN FETCH f.offer
        WHERE f.business.id = :businessId
          AND f.status = :status
        """)
    Page<RetentionFlag> findForDashboardByStatus(@Param("businessId") UUID businessId,
                                                @Param("status") FlagStatus status,
                                                Pageable pageable);

    Optional<RetentionFlag> findByIdAndBusinessId(UUID id, UUID businessId);

    long countByBusinessIdAndStatus(UUID businessId, FlagStatus status);

    /**
     * Flags opened per week, for the overview chart (FR-7).
     *
     * <p>Native because {@code date_trunc} has no JPQL equivalent, and grouping
     * in the database beats shipping every flag to the JVM to be counted.
     * {@code AT TIME ZONE 'UTC'} pins the week boundary so the buckets don't
     * shift with the database session's timezone.
     *
     * <p>Weeks with no activity are simply absent from the result — the service
     * fills them, because a chart that omits quiet weeks compresses time and
     * misreports the trend.
     */
    @Query(value = """
        SELECT date_trunc('week', flagged_at AT TIME ZONE 'UTC') AS "weekStart",
               COUNT(*) AS "total"
        FROM retention_flags
        WHERE business_id = :businessId
          AND flagged_at >= :from
        GROUP BY 1
        """, nativeQuery = true)
    List<WeeklyCount> countFlagsRaisedByWeek(@Param("businessId") UUID businessId,
                                             @Param("from") Instant from);

    /**
     * Flags raised inside a window — the current or the preceding period, for
     * the overview's period-over-period comparison (FR-7).
     *
     * <p>Half-open on purpose: {@code [from, to)}. A closed upper bound would
     * count a flag raised exactly on the boundary in both periods and overstate
     * both sides of the comparison.
     */
    long countByBusinessIdAndFlaggedAtGreaterThanEqualAndFlaggedAtLessThan(
            UUID businessId, Instant from, Instant to);

    /** Customers who came back inside a window, by the moment they returned (FR-9). */
    long countByBusinessIdAndResolvedAtGreaterThanEqualAndResolvedAtLessThan(
            UUID businessId, Instant from, Instant to);

    /**
     * How far past their own threshold the currently-quiet customers are.
     *
     * <p>The one honest breakdown of "why these customers are flagged": there is
     * a single trigger, so the interesting variation is severity, not category.
     * Expressed as a multiple of each customer's own threshold rather than in
     * days, since ten quiet days means something different to a weekly regular
     * and a quarterly one.
     */
    @Query(value = """
        SELECT CASE
                 WHEN quiet_days < threshold_days_applied * 1.5 THEN 'JUST_PAST'
                 WHEN quiet_days < threshold_days_applied * 3   THEN 'WELL_PAST'
                 ELSE 'LONG_OVERDUE'
               END AS "bucket",
               COUNT(*) AS "total"
        FROM (
            SELECT f.threshold_days_applied,
                   EXTRACT(EPOCH FROM (now() - c.last_visit_at)) / 86400 AS quiet_days
            FROM retention_flags f
            JOIN customers c ON c.id = f.customer_id
            WHERE f.business_id = :businessId
              AND f.status = 'ACTIVE'
        ) AS quiet
        GROUP BY 1
        """, nativeQuery = true)
    List<BucketCount> countActiveFlagsByOverdueBucket(@Param("businessId") UUID businessId);

    /** As {@link #countFlagsRaisedByWeek}, but by the week the customer returned (FR-9). */    @Query(value = """
        SELECT date_trunc('week', resolved_at AT TIME ZONE 'UTC') AS "weekStart",
               COUNT(*) AS "total"
        FROM retention_flags
        WHERE business_id = :businessId
          AND resolved_at IS NOT NULL
          AND resolved_at >= :from
        GROUP BY 1
        """, nativeQuery = true)
    List<WeeklyCount> countCustomersRecoveredByWeek(@Param("businessId") UUID businessId,
                                                    @Param("from") Instant from);

    /**
     * Flags nobody was ever reached about — the offer exists but never left the
     * building (no contact details, a delivery failure, or a spent budget).
     *
     * <p>Deliberately kept out of the recovery rate and reported beside it
     * instead. A flag nobody could act on is not a failure of the intervention,
     * and folding it into the denominator would make the product look worse the
     * more incomplete the business's customer data is — which is the opposite of
     * what the figure is for. Shown next to the rate, it says how much of the
     * opportunity the business could not even attempt.
     */
    @Query(value = """
        SELECT COUNT(*)
        FROM retention_flags f
        JOIN offers o ON o.flag_id = f.id
        WHERE f.business_id = :businessId
          AND o.status <> 'SENT'
        """, nativeQuery = true)
    long countFlagsWithoutDeliveredOffer(@Param("businessId") UUID businessId);

    /**
     * Every counter behind the retention-impact section, over the offers sent in
     * the half-open window {@code [from, to)} (FR-7).
     *
     * <p>The one query every recovery and revenue figure is built from — the
     * all-time number on the overview and both sides of its period comparison
     * call this with different bounds rather than each writing its own filter.
     * That is what stops "40% recovered" and the revenue beside it from being
     * two different cohorts wearing the same label.
     *
     * <p>The cohort is offers <em>delivered</em> in the window, not flags raised:
     * an offer that never reached the customer cannot have brought them back, and
     * counting it as an attempt that failed would make the programme look worse
     * the sparser the business's contact data is.
     *
     * <p>The return is detected on {@code transactions} rather than on
     * {@code resolved_at}, which keeps the count of returns and the revenue
     * attributed to them the same predicate over the same rows.
     * {@code resolved_at} is the flag's own view of the same event, and a window
     * computed from it would miss a return whose transaction arrived backdated
     * into the window it belongs to.
     *
     * <p>Cost is an <em>upper bound</em> for a percentage deal: the discount is
     * taken to have been applied to the whole returned basket, which is the most
     * it could have cost. A free item is counted but not costed — it has no price
     * anywhere in the data, and the caller reports the count rather than a
     * made-up zero.
     */
    @Query(value = """
        SELECT COUNT(*)                                        AS "contacted",
               COUNT(*) FILTER (WHERE w.revenue > 0)           AS "returned",
               COALESCE(SUM(w.revenue), 0)                     AS "revenueFromReturned",
               COALESCE(SUM(CASE
                     WHEN o.deal_type = 'FIXED_AMOUNT_OFF' THEN o.deal_value
                     WHEN o.deal_type = 'PERCENT_OFF' THEN w.revenue * o.deal_value / 100
                   END), 0)                                    AS "interventionCost",
               COUNT(*) FILTER (WHERE o.deal_type = 'FREE_ITEM') AS "freeItemOffers"
        FROM retention_flags f
        JOIN offers o ON o.flag_id = f.id
                     AND o.status = 'SENT'
                     AND o.sent_at >= :from
                     AND o.sent_at < :to
        JOIN businesses b ON b.id = f.business_id
        CROSS JOIN LATERAL (
            SELECT COALESCE(SUM(t.amount), 0) AS revenue
            FROM transactions t
            WHERE t.customer_id = f.customer_id
              AND t.occurred_at >= o.sent_at
              AND t.occurred_at <= o.sent_at + make_interval(days => b.recovery_window_days)
        ) w
        WHERE f.business_id = :businessId
        """, nativeQuery = true)
    RetentionImpactRow retentionImpact(@Param("businessId") UUID businessId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    /**
     * Drifting customers worth more than {@code multiple} × the average customer
     * (FR-7).
     *
     * <p>The baseline is the mean of customer totals, not of transactions: a
     * mean over transactions would let one customer with a hundred small
     * purchases define what "valuable" means for everyone else, and the number is
     * meant to describe customers, not baskets.
     *
     * <p>Written as a multiple of the business's own average rather than a fixed
     * amount, because a valuable customer at a coffee shop and at a restaurant
     * are three orders of magnitude apart and only the business knows which it
     * is.
     *
     * <p>Each drifting customer is measured against an average that includes
     * them, and the bar is therefore unreachable in a customer base of one or
     * two: with two, one holding all the spend puts the mean at half of it, and
     * twice the mean is the spend itself. That is the safe direction to fail in —
     * the count decides where effort is spent, and a count that needs a real
     * customer base before it says anything is better than one that calls a
     * single big spender high value on no evidence. The demo seeder ships enough
     * customers for the number to be live.
     */
    @Query(value = """
        WITH customer_totals AS (
            SELECT t.customer_id, SUM(t.amount) AS total
            FROM transactions t
            WHERE t.business_id = :businessId
            GROUP BY t.customer_id
        ), baseline AS (
            SELECT COALESCE(AVG(total), 0) AS average FROM customer_totals
        )
        SELECT COUNT(*)
        FROM retention_flags f
        JOIN customer_totals ct ON ct.customer_id = f.customer_id
        CROSS JOIN baseline
        WHERE f.business_id = :businessId
          AND f.status = 'ACTIVE'
          AND ct.total >= baseline.average * :multiple
        """, nativeQuery = true)
    long countHighValueActiveFlags(@Param("businessId") UUID businessId,
                                   @Param("multiple") BigDecimal multiple);

    /**
     * Total historical spend per customer, for the value column on the flag list
     * and the high-value count on the overview (FR-7).
     *
     * <p>Whole history, not a reporting window: "value" here answers what this
     * customer is worth to the business, which is the number that decides
     * whether an offer is worth sending. Restricting it to a window would make
     * the same customer's value change depending on which screen they are viewed
     * from.
     */
    @Query(value = """
        SELECT t.customer_id                            AS "customerId",
               COALESCE(SUM(t.amount), 0)               AS "totalSpend",
               COUNT(*)                                 AS "transactionCount",
               COALESCE(AVG(t.amount), 0)               AS "averageTransactionValue"
        FROM transactions t
        WHERE t.business_id = :businessId
          AND t.customer_id IN (:customerIds)
        GROUP BY t.customer_id
        """, nativeQuery = true)
    List<CustomerValueRow> customerValues(@Param("businessId") UUID businessId,
                                          @Param("customerIds") Collection<UUID> customerIds);

    /**
     * Mean total spend across every customer of the business, the baseline the
     * high-value flag is drawn against.
     *
     * <p>A mean of customer totals rather than of transactions: averaging
     * transactions would let one customer with a hundred small purchases define
     * what "valuable" means for everyone else.
     */
    @Query(value = """
        SELECT COALESCE(AVG(totals.total), 0)
        FROM (
            SELECT SUM(t.amount) AS total
            FROM transactions t
            WHERE t.business_id = :businessId
            GROUP BY t.customer_id
        ) AS totals
        """, nativeQuery = true)
    BigDecimal averageCustomerSpend(@Param("businessId") UUID businessId);
}
