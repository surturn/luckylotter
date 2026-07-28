package com.lucklotter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * POS ingestion payload (FR-1).
 *
 * <p>{@code externalTxnId} is the natural POS transaction ID and doubles as the
 * idempotency key — replaying it is a no-op (NFR-3). A request may instead carry
 * an {@code Idempotency-Key} header, which the controller falls back to when the
 * POS has no stable per-transaction ID of its own.
 *
 * <p>{@code contactEmail} and {@code contactPhone} are optional: not every POS
 * exposes customer contact details. Supplying neither means any offer generated
 * for this customer will be marked {@code NO_CONTACT} rather than sent.
 */
public record TransactionIngestRequest(

    @NotNull(message = "businessId is required")
    UUID businessId,

    @NotBlank(message = "customerRef is required")
    @Size(max = 160)
    String customerRef,

    @NotBlank(message = "externalTxnId is required")
    @Size(max = 160)
    String externalTxnId,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.00", message = "amount cannot be negative")
    BigDecimal amount,

    @NotNull(message = "occurredAt is required")
    Instant occurredAt,

    @Email(message = "contactEmail must be a valid email address")
    @Size(max = 255)
    String contactEmail,

    @Size(max = 32)
    String contactPhone
) {
}
