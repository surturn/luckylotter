package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A detected cadence break (FR-3). At most one {@link FlagStatus#ACTIVE} flag
 * exists per customer — enforced by a partial unique index, so a retried or
 * overlapping batch run cannot double-flag (FR-8, NFR-3).
 *
 * <p>The threshold and cadence that fired the flag are snapshotted so the flag
 * stays explainable after the business retunes its sensitivity.
 */
@Entity
@Table(name = "retention_flags")
@Getter
@Setter
@NoArgsConstructor
public class RetentionFlag {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FlagStatus status = FlagStatus.ACTIVE;

    /** The clamped threshold, in days, that this flag crossed. */
    @Column(name = "threshold_days_applied", nullable = false, precision = 8, scale = 2)
    private BigDecimal thresholdDaysApplied;

    /** The customer's learned cadence at the moment of flagging. */
    @Column(name = "avg_interval_days_at_flag", nullable = false, precision = 8, scale = 2)
    private BigDecimal avgIntervalDaysAtFlag;

    @Column(name = "flagged_at", nullable = false, updatable = false)
    private Instant flaggedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * The generated offer. Inverse side — {@link Offer#getFlag()} owns the FK.
     * Mapped so the dashboard query can fetch flag + offer in one statement
     * (NFR-5).
     */
    @OneToOne(mappedBy = "flag", fetch = FetchType.LAZY)
    private Offer offer;

    /** Closes the flag on the customer's next ingested transaction (FR-9). */
    public void resolve(Instant at) {
        this.status = FlagStatus.RESOLVED;
        this.resolvedAt = at;
    }
}
