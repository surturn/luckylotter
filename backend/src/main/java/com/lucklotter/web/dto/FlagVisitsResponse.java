package com.lucklotter.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A flagged customer's recent visit history, for the rhythm chart (FR-7).
 *
 * <p>Timestamps only. The chart needs to show <em>when</em> somebody visited
 * and nothing else, so amounts, contact details and transaction IDs stay out of
 * the payload — the narrowest response that answers the question (NFR-4).
 *
 * @param visits    up to {@code VISIT_HISTORY_LIMIT} most recent visits, oldest
 *                  first
 * @param flaggedAt when the cadence break was detected, so the chart can mark
 *                  the moment against the gap that caused it
 */
public record FlagVisitsResponse(
    UUID flagId,
    Instant flaggedAt,
    List<VisitPoint> visits
) {

    public record VisitPoint(Instant occurredAt) {
    }
}
