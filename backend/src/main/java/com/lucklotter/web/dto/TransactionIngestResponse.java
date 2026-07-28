package com.lucklotter.web.dto;

import java.util.UUID;

/**
 * Ingestion outcome (FR-1, FR-9, NFR-3).
 *
 * @param transactionId  the stored transaction — the existing one on a replay
 * @param customerId     the resolved (or newly created) customer
 * @param duplicate      true when this {@code externalTxnId} was already known,
 *                       so nothing was counted a second time
 * @param resolvedFlagId the flag this visit closed, or null if none was open
 */
public record TransactionIngestResponse(
    UUID transactionId,
    UUID customerId,
    boolean duplicate,
    UUID resolvedFlagId
) {
}
