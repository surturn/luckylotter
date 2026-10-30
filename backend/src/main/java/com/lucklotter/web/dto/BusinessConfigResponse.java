package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;
import com.lucklotter.domain.BusinessType;

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
    int offerCooldownDays,
    BigDecimal offerCooldownMultiplier,
    int offerCapPerWindow,
    int offerBudgetWindowDays,
    /** Days after a delivered offer in which a purchase still counts as a return. */
    int recoveryWindowDays,
    /** ISO 4217 code for every amount this business records. */
    String currencyCode,
    String timezone,
    DealType defaultDealType,
    BigDecimal defaultDealValue,
    int minTransactions,
    BusinessType businessType,
    /** The words for this kind of business, so the screens speak its language. */
    Vocabulary vocabulary
) {

    /**
     * @param typicalCadenceDays a typical gap between visits for this kind of
     *                           business, used only to write worked examples
     */
    public record Vocabulary(
        String customerNoun,
        String customerNounPlural,
        String visitNoun,
        String usualLabel,
        String freeItemLabel,
        int typicalCadenceDays
    ) {
    }
}
