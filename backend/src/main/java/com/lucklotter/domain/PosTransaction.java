package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ingested POS transaction — a visit (FR-1).
 *
 * <p>Named {@code PosTransaction} rather than {@code Transaction} to avoid
 * colliding with Spring's transaction abstractions at every import site.
 *
 * <p>{@link #externalTxnId} is the natural POS ID and the idempotency anchor:
 * unique per business, so replaying the same record is a no-op rather than a
 * second counted visit (NFR-3).
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class PosTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "external_txn_id", nullable = false, length = 160)
    private String externalTxnId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
