package com.lucklotter.domain;

/**
 * Delivery state of a generated offer (FR-5).
 *
 * <p>{@link #NO_CONTACT} exists so a customer with no email and no phone is
 * visibly un-contactable rather than sitting in {@link #PENDING} forever. The
 * offer is still generated and logged — the gap is surfaced, not papered over.
 */
public enum OfferStatus {
    /** Generated, contactable, awaiting a send attempt. */
    PENDING,
    /** Handed to the {@code NotificationSender} successfully. */
    SENT,
    /** A send was attempted and threw. Retriable. */
    FAILED,
    /** Terminal: the customer has neither contact_email nor contact_phone. */
    NO_CONTACT
}
