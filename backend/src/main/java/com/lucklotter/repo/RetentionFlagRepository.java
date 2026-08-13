package com.lucklotter.repo;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.RetentionFlag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** As {@link #countFlagsRaisedByWeek}, but by the week the customer returned (FR-9). */
    @Query(value = """
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
}
