package com.lucklotter.repo;

import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    Optional<Offer> findByFlagId(UUID flagId);

    /** Sendable backlog — PENDING and FAILED only; NO_CONTACT is terminal. */
    List<Offer> findByBusinessIdAndStatusIn(UUID businessId, List<OfferStatus> statuses);

    long countByBusinessIdAndStatus(UUID businessId, OfferStatus status);

    /**
     * Offers actually delivered inside a window, for the period-over-period
     * comparison (FR-7). Counted on {@code sentAt}, not {@code createdAt}: the
     * card says "offers sent", so an offer that was generated in one period and
     * delivered in the next belongs to the period it reached someone in.
     *
     * <p>Half-open {@code [from, to)} so a delivery on the boundary is not
     * counted in both periods.
     */
    long countByBusinessIdAndSentAtGreaterThanEqualAndSentAtLessThan(
            UUID businessId, Instant from, Instant to);

    /**
     * When an offer last actually reached this customer — the instant the
     * cooldown is measured from (FR-4).
     *
     * <p>Delivered offers only, deliberately. An offer that sat at
     * {@code NO_CONTACT} or {@code FAILED} was never received, so it is not a
     * reward the customer could be cycling for, and letting it start a cooldown
     * would silently suppress the next flag for someone the business has never
     * successfully contacted.
     *
     * <p>Reaches offers through every flag the customer has ever had, not just
     * the open one, so it needs the plain {@code retention_flags(customer_id)}
     * index rather than the partial unique index on active flags (NFR-5).
     */
    @Query("""
        SELECT MAX(o.sentAt) FROM Offer o
        WHERE o.flag.customer.id = :customerId
          AND o.sentAt IS NOT NULL
        """)
    Optional<Instant> findLastSentAtForCustomer(@Param("customerId") UUID customerId);

    /**
     * Offers this business has committed to sending since {@code windowStart} —
     * what the budget ceiling is measured against (FR-4).
     *
     * <p>Counted from {@code createdAt}, not {@code sentAt}. An offer sits
     * {@code PENDING} between the scan generating it and the dispatcher sending
     * it, so counting deliveries would leave a whole batch invisible to the
     * ceiling and let the next scan spend the same budget again.
     *
     * <p>{@code NO_CONTACT} and {@code SUPPRESSED_BUDGET} are excluded because
     * neither will ever reach anyone: charging the budget for them would let a
     * business full of contactless customers exhaust a cap without a single
     * offer being delivered. {@code FAILED} does count — it was attempted, and
     * it is still in the retry backlog.
     */
    @Query("""
        SELECT COUNT(o) FROM Offer o
        WHERE o.business.id = :businessId
          AND o.createdAt >= :windowStart
          AND o.status IN (com.lucklotter.domain.OfferStatus.PENDING,
                           com.lucklotter.domain.OfferStatus.SENT,
                           com.lucklotter.domain.OfferStatus.FAILED)
        """)
    long countCommittedSince(@Param("businessId") UUID businessId,
                             @Param("windowStart") Instant windowStart);
}
