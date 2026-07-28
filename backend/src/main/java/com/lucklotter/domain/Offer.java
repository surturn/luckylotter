package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The win-back offer generated for a flag (FR-4, FR-5). Exactly one per flag.
 *
 * <p>Deal type and value are snapshotted from the business config at generation
 * time, so retuning the config later doesn't rewrite what was already offered.
 */
@Entity
@Table(name = "offers")
@Getter
@Setter
@NoArgsConstructor
public class Offer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flag_id", nullable = false, unique = true)
    private RetentionFlag flag;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 24)
    private DealType dealType;

    @Column(name = "deal_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal dealValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OfferStatus status = OfferStatus.PENDING;

    /**
     * Why delivery failed, as a bounded code — never a provider error string, so
     * customer contact details can't leak into this column (NFR-4).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private OfferFailureCode failureCode;

    /**
     * The code the customer quotes to claim this offer.
     *
     * <p>Generated when the offer is created and unique within the business.
     * Phase 1 issues and displays it; nothing records redemption yet, so a code
     * is a reference the business honours manually rather than a token the
     * system can retire.
     */
    @Column(name = "redemption_code", length = 16)
    private String redemptionCode;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSent(Instant at) {
        this.status = OfferStatus.SENT;
        this.sentAt = at;
        this.failureCode = null;
    }

    /**
     * Records a delivery failure. Takes a code, not a message — log the
     * provider's own text against this offer's ID instead of storing it.
     */
    public void markFailed(OfferFailureCode code) {
        this.status = OfferStatus.FAILED;
        this.sentAt = null;
        this.failureCode = Objects.requireNonNull(code, "failureCode is required for a FAILED offer");
    }

    /** Terminal: the customer has no email and no phone to send to. */
    public void markNoContact() {
        this.status = OfferStatus.NO_CONTACT;
        this.sentAt = null;
        this.failureCode = OfferFailureCode.MISSING_CONTACT_DETAILS;
    }
}
