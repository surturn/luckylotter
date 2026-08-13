package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the flagged-customer list (FR-7).
 *
 * <p>Carries the POS customer reference, never a name — the dashboard shows
 * whatever identifier the POS supplied. Contact details are reduced to
 * {@code contactable} so the list can surface the {@code NO_CONTACT} gap without
 * spreading PII through list responses (NFR-4).
 */
public record FlagSummaryResponse(
    UUID flagId,
    UUID customerId,
    String customerRef,
    /**
     * The customer's name when the POS supplied one, else null. A name is not
     * contact data — it is what lets an admin recognise who this row is about
     * without cross-referencing a till ID — but it is still personal, so the
     * list carries it and nothing else identifying (NFR-4).
     */
    String customerName,
    FlagStatus status,
    Instant lastVisitAt,
    Instant flaggedAt,
    BigDecimal avgIntervalDaysAtFlag,
    BigDecimal thresholdDaysApplied,
    boolean contactable,
    DealType dealType,
    BigDecimal dealValue,
    OfferStatus offerStatus,
    String redemptionCode,
    Instant offerSentAt
) {
}
