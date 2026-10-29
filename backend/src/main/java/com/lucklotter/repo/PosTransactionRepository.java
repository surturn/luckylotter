package com.lucklotter.repo;

import com.lucklotter.domain.PosTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosTransactionRepository extends JpaRepository<PosTransaction, UUID> {

    /** Idempotency check for ingestion (NFR-3). */
    Optional<PosTransaction> findByBusinessIdAndExternalTxnId(UUID businessId, String externalTxnId);

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

    /**
     * The first visit after an offer went out, inside the business's recovery
     * window — the return an intervention is credited with (FR-7, §13).
     *
     * <p>Deliberately the same predicate the flagged list counts prior returns
     * with: a customer the list says came back cannot have a detail page that
     * shows no return, and one the list says ignored the offer cannot have a
     * page showing one. The window is a parameter rather than a join because
     * the caller already holds the business, and this query is on the detail
     * path where a second join buys nothing (NFR-5).
     */
    // The casts are load-bearing. Bound parameters arrive untyped, so Postgres
    // resolves `:since + make_interval(...)` as interval + interval and then
    // fails comparing a timestamptz to it — "operator does not exist: timestamp
    // with time zone <= interval". The list query escapes this only because it
    // adds the interval to a column, whose type is already known.
    @Query(value = """
        SELECT t.occurred_at                          AS "occurredAt",
               t.amount                               AS "amount"
        FROM transactions t
        WHERE t.business_id = :businessId
          AND t.customer_id = :customerId
          AND t.occurred_at > CAST(:since AS timestamptz)
          AND t.occurred_at <= CAST(:since AS timestamptz)
                               + make_interval(days => CAST(:windowDays AS int))
        ORDER BY t.occurred_at ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<ReturnRow> findFirstReturn(@Param("businessId") UUID businessId,
                                        @Param("customerId") UUID customerId,
                                        @Param("since") Instant since,
                                        @Param("windowDays") int windowDays);

    /** Projection for {@link #findFirstReturn}. */
    interface ReturnRow {
        Instant getOccurredAt();

        BigDecimal getAmount();
    }

    /**
     * The visit the customer's rhythm was last measured from at an instant —
     * for a flag, the one that stood when it fired.
     *
     * <p>Asking the customer for their latest visit instead would answer with the
     * visit that <em>answered</em> the flag once they came back, and the detail
     * page would then predict a due date later than the flag it is explaining
     * (§13). The cutoff is what makes the difference.
     */
    @Query(value = """
        SELECT t.occurred_at AS "occurredAt"
        FROM transactions t
        WHERE t.business_id = :businessId
          AND t.customer_id = :customerId
          AND t.occurred_at <= CAST(:at AS timestamptz)
        ORDER BY t.occurred_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<VisitRow> findLastVisitAtOrBefore(@Param("businessId") UUID businessId,
                                               @Param("customerId") UUID customerId,
                                               @Param("at") Instant at);

    /** Projection for {@link #findLastVisitAtOrBefore}. */
    interface VisitRow {
        Instant getOccurredAt();
    }
}
