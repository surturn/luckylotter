package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full flag view (FR-7, §10). Adds the cadence evidence behind the trigger, so
 * an admin auditing precision can see why this customer fired — the multiplier
 * and clamps in force at flag time, not the business's current settings.
 */
public record FlagDetailResponse(
    UUID flagId,
    FlagStatus status,
    Instant flaggedAt,
    Instant resolvedAt,

    UUID customerId,
    String customerRef,
    Instant firstSeenAt,
    Instant lastVisitAt,
    int transactionCount,
    BigDecimal avgIntervalDays,
    boolean contactable,

    BigDecimal avgIntervalDaysAtFlag,
    BigDecimal thresholdDaysApplied,

    UUID offerId,
    DealType dealType,
    BigDecimal dealValue,
    OfferStatus offerStatus,
    OfferFailureCode offerFailureCode,
    Instant offerSentAt
) {
}
