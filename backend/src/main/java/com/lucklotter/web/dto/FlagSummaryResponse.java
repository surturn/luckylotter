package com.lucklotter.web.dto;

import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.OverdueBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the flagged-customer list — the action screen (§5, FR-7).
 *
 * <p>Carries the POS customer reference, never contact details; those are
 * reduced to {@code contactable} so the list can surface the
 * {@code NO_CONTACT} gap without spreading PII through list responses (NFR-4).
 * A name is carried when the POS supplied one, because it is what lets an admin
 * recognise the row, and nothing more identifying travels with it.
 *
 * <p>The value and history fields exist because "who should I act on today"
 * cannot be answered by lateness alone: the customer who has spent twelve times
 * as much as the next and the one who visited twice are not the same decision.
 */
public record FlagSummaryResponse(
    UUID flagId,
    UUID customerId,
    String customerRef,
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
    Instant offerSentAt,

    /** Purchases on record, not visits — a split bill counts more than once. */
    int visitCount,

    /** Everything this customer has ever spent, in the business's own currency. */
    BigDecimal totalSpend,

    BigDecimal averageTransactionValue,

    /**
     * What the POS declared this customer usually orders, or null. Never
     * inferred: transactions carry an amount but no line items, so there is
     * nothing to derive a favourite from, and guessing wrong is worse than
     * saying nothing (§14).
     */
    String usualItem,

    /** Quiet days past the threshold. Null for a resolved flag (§13). */
    BigDecimal daysOverdue,

    /**
     * Severity of the lapse, on the same boundaries as the overview's chart.
     * Null for a resolved flag: how overdue a customer is stops being a
     * question once they have come back.
     */
    OverdueBand riskBand,

    /** Offers delivered to this customer before this flag's own. */
    long priorInterventions,

    /** How many of those were followed by a purchase inside the recovery window. */
    long priorReturns,

    /** 0-100, combining the {@link Priority} components below. 0 for a resolved flag. */
    int priorityScore,

    Priority priority,

    /**
     * What to do about this row, derived from the state it is actually in.
     *
     * <p>Not a suggestion and not a prediction: each value corresponds to a
     * fact already recorded — no contact details, a failed send, a spent
     * budget, an offer delivered and unanswered.
     */
    String recommendedAction
) {

    /**
     * Why a customer sits where they do in the list.
     *
     * <p>Returned so the screen can show the working. An ordering an operator
     * can't account for is one they will work around rather than trust, and the
     * top of this list is where the day's effort goes.
     */
    public record Priority(int risk, int value, int history) {
    }
}
