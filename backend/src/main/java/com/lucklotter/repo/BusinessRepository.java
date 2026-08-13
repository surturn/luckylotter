package com.lucklotter.repo;

import com.lucklotter.domain.Business;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {

    /**
     * Loads a business with its row locked for the rest of the transaction —
     * the serialization point for the offer budget ceiling (FR-4, NFR-3).
     *
     * <p>The ceiling is a count over a rolling window, which cannot be a
     * counter column because old offers age out of it. That makes the check a
     * read followed by an insert, and two scans running at once — the daily
     * cron overlapping a manual trigger, say — would both read the same count
     * below the cap and both insert, overshooting by one offer per concurrent
     * run.
     *
     * <p>Taken per customer rather than around the whole batch on purpose.
     * Holding it for a full scan would serialize the entire run and undo the
     * one-transaction-per-customer design that stops a single failing customer
     * rolling back everything before it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Business b WHERE b.id = :id")
    Optional<Business> findByIdForUpdate(@Param("id") UUID id);
}
