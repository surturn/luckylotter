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

    /** Customers with enough history to have a cadence — the flaggable population (FR-2). */
    long countByBusinessIdAndTransactionCountGreaterThanEqual(UUID businessId, int minTransactions);

    /** Seen, but still below {@code MIN_TRANSACTIONS}: no rhythm yet, so never flagged (FR-2). */
    long countByBusinessIdAndTransactionCountLessThan(UUID businessId, int minTransactions);

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
