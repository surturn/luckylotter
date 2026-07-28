package com.lucklotter.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Issued JWT plus the identity it encodes (§10). The token itself carries the
 * business scope — the client's copy of {@code businessId} is for display only
 * and is never trusted as an authorization input (NFR-1).
 */
public record LoginResponse(
    String token,
    Instant expiresAt,
    UUID businessId,
    String businessName
) {
}
