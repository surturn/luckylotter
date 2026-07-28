package com.lucklotter.repo;

import com.lucklotter.domain.PosTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosTransactionRepository extends JpaRepository<PosTransaction, UUID> {

    /** Idempotency check for ingestion (NFR-3). */
    Optional<PosTransaction> findByBusinessIdAndExternalTxnId(UUID businessId, String externalTxnId);

    boolean existsByBusinessIdAndExternalTxnId(UUID businessId, String externalTxnId);

    /**
     * Visit timestamps for one customer, oldest first — the input to the average
     * interval calculation (FR-2). Rides the
     * {@code (business_id, customer_id, occurred_at)} index (NFR-5).
     */
    @Query("""
        SELECT t.occurredAt FROM PosTransaction t
        WHERE t.customer.id = :customerId
        ORDER BY t.occurredAt ASC
        """)
    List<Instant> findVisitTimestamps(@Param("customerId") UUID customerId);

    /**
     * Visit timestamps for several customers at once, newest first (FR-7).
     *
     * <p>One query for a whole page of the dashboard rather than one per row —
     * the same N+1 the fetch-joined flag query exists to avoid, just moved up
     * to the HTTP layer if done naively. Callers trim each customer's list to
     * the display limit.
     */
    @Query("""
        SELECT t.customer.id AS customerId, t.occurredAt AS occurredAt
        FROM PosTransaction t
        WHERE t.customer.id IN :customerIds
        ORDER BY t.occurredAt DESC
        """)
    List<CustomerVisit> findVisitsForCustomers(@Param("customerIds") Collection<UUID> customerIds);

    /** Projection for {@link #findVisitsForCustomers}. */
    interface CustomerVisit {
        UUID getCustomerId();

        Instant getOccurredAt();
    }
}
