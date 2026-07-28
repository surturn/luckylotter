package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant. Owns the trigger tuning and the default deal (FR-6).
 *
 * <p>There is no flat inactivity threshold: the threshold is derived per
 * customer from their own cadence via {@link #sensitivityMultiplier}, clamped
 * to [{@link #minThresholdDays}, {@link #maxThresholdDays}].
 */
@Entity
@Table(name = "businesses")
@Getter
@Setter
@NoArgsConstructor
public class Business {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    /** Multiplier applied to a customer's average visit interval (FR-3). */
    @Column(name = "sensitivity_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal sensitivityMultiplier = new BigDecimal("1.5");

    /** Lower clamp — protects very frequent visitors from same-week flagging. */
    @Column(name = "min_threshold_days", nullable = false)
    private int minThresholdDays = 3;

    /** Upper clamp — stops rare visitors from never being flagged at all. */
    @Column(name = "max_threshold_days", nullable = false)
    private int maxThresholdDays = 60;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_deal_type", nullable = false, length = 24)
    private DealType defaultDealType = DealType.PERCENT_OFF;

    @Column(name = "default_deal_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultDealValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * The clamped inactivity threshold, in days, for a customer whose average
     * visit interval is {@code avgIntervalDays} (FR-3).
     *
     * @param avgIntervalDays the customer's learned cadence; must be non-null,
     *                        which means the customer has at least
     *                        {@link RetentionConstants#MIN_TRANSACTIONS}
     *                        transactions
     */
    public BigDecimal thresholdDaysFor(BigDecimal avgIntervalDays) {
        BigDecimal raw = avgIntervalDays.multiply(sensitivityMultiplier);
        BigDecimal min = BigDecimal.valueOf(minThresholdDays);
        BigDecimal max = BigDecimal.valueOf(maxThresholdDays);
        return raw.max(min).min(max);
    }
}
