package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Update the trigger tuning and default deal (FR-6).
 *
 * <p>Field-level bounds are checked here; the cross-field rule
 * ({@code minThresholdDays <= maxThresholdDays}) is enforced in the service and
 * again by a DB check constraint (NFR-2).
 */
public record BusinessConfigUpdateRequest(

    @NotNull(message = "sensitivityMultiplier is required")
    @DecimalMin(value = "1.0", message = "sensitivityMultiplier below 1.0 would flag customers who are still on cadence")
    @DecimalMax(value = "10.0", message = "sensitivityMultiplier above 10.0 would effectively disable flagging")
    BigDecimal sensitivityMultiplier,

    @NotNull(message = "minThresholdDays is required")
    @Min(value = 1, message = "minThresholdDays must be at least 1")
    @Max(value = 365)
    Integer minThresholdDays,

    @NotNull(message = "maxThresholdDays is required")
    @Min(value = 1, message = "maxThresholdDays must be at least 1")
    @Max(value = 365)
    Integer maxThresholdDays,

    @NotNull(message = "defaultDealType is required")
    DealType defaultDealType,

    @NotNull(message = "defaultDealValue is required")
    @DecimalMin(value = "0.01", inclusive = true, message = "defaultDealValue must be positive")
    BigDecimal defaultDealValue
) {
}
