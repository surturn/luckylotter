package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;

import java.math.BigDecimal;

/**
 * Current trigger tuning and default deal for the caller's business (FR-6).
 *
 * <p>{@code minTransactions} is echoed read-only so the dashboard can explain
 * why low-history customers never appear — it is a fixed constant in Phase 1,
 * not something the admin can change.
 */
public record BusinessConfigResponse(
    String name,
    BigDecimal sensitivityMultiplier,
    int minThresholdDays,
    int maxThresholdDays,
    DealType defaultDealType,
    BigDecimal defaultDealValue,
    int minTransactions
) {
}
