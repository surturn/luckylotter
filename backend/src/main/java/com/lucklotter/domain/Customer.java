package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer of a business, identified only by the POS's own reference
 * (FR-1). Never logs in — Phase 1 customers are outbound recipients only.
 *
 * <p>{@link #contactEmail} and {@link #contactPhone} are both optional because
 * POS exports vary in what they carry. A customer with neither is still
 * tracked and still flagged; their offer lands at
 * {@link OfferStatus#NO_CONTACT}.
 *
 * <p>{@link #avgIntervalDays} is denormalized cadence state recomputed on each
 * ingested transaction (FR-2). It stays null until {@link #transactionCount}
 * reaches {@link RetentionConstants#MIN_TRANSACTIONS} — a null cadence means
 * not flaggable.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    /** The POS's customer identifier. Unique within the business only. */
    @Column(name = "external_ref", nullable = false, length = 160)
    private String externalRef;

    /**
     * Display name, for addressing the customer in an offer. Optional, and PII:
     * never log it (NFR-4). Use {@link #getExternalRef()} to identify a customer
     * in logs and error messages.
     */
    @Column(name = "name", length = 160)
    private String name;

    /**
     * What this customer usually orders, as supplied by the POS — never
     * inferred from transaction history, which carries no line items. Optional
     * and personal: never log it (NFR-4).
     */
    @Column(name = "usual_item", length = 120)
    private String usualItem;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "transaction_count", nullable = false)
    private int transactionCount = 0;

    @Column(name = "avg_interval_days", precision = 8, scale = 2)
    private BigDecimal avgIntervalDays;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_visit_at")
    private Instant lastVisitAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** True once there is enough history for the cadence to mean something. */
    public boolean hasEstablishedCadence() {
        return transactionCount >= RetentionConstants.MIN_TRANSACTIONS && avgIntervalDays != null;
    }

    /**
     * The name to greet this customer by, or null when none is on file.
     *
     * <p>First token only: POS exports carry full names, and "Hi Sydney" reads
     * like a person wrote it where "Hi Sydney Kamau" reads like a mail merge.
     * Callers must handle null rather than substituting an empty string — a
     * greeting addressed to nobody is worse than no greeting.
     */
    public String greetingName() {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().split("\\s+")[0];
    }

    /** False when the customer has neither an email nor a phone (FR-5). */
    public boolean isContactable() {
        return (contactEmail != null && !contactEmail.isBlank())
            || (contactPhone != null && !contactPhone.isBlank());
    }
}
