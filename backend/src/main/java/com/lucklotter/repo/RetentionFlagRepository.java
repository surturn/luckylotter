package com.lucklotter.repo;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.RetentionFlag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
