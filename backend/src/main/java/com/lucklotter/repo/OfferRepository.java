package com.lucklotter.repo;

import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    Optional<Offer> findByFlagId(UUID flagId);

    /** Sendable backlog — PENDING and FAILED only; NO_CONTACT is terminal. */
    List<Offer> findByBusinessIdAndStatusIn(UUID businessId, List<OfferStatus> statuses);

    long countByBusinessIdAndStatus(UUID businessId, OfferStatus status);
}
