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
