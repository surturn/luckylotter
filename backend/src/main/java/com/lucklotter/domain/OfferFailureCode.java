package com.lucklotter.domain;

/**
 * Why an offer could not be delivered — a closed set of codes, never a
 * provider-supplied message.
 *
 * <p>Extends NFR-4's no-PII rule from logs to stored columns: a freeform error
 * string is how a phone number or customer name ends up sitting in a field that
 * was never meant to carry PII. Provider detail may be logged at DEBUG against
 * the offer ID, but only the code is persisted.
 *
 * <p>Codes describe the *class* of failure, so adding a real SMS gateway later
 * shouldn't need new values for routine cases.
 */
public enum OfferFailureCode {

    /** Customer has neither contact_email nor contact_phone. Pairs with {@link OfferStatus#NO_CONTACT}. */
    MISSING_CONTACT_DETAILS,

    /**
     * The stored email address is malformed or was refused by the recipient's
     * server with a permanent answer. Never retried against the same address;
     * see {@link OfferRetryPolicy}.
     */
    INVALID_EMAIL_ADDRESS,

    /** The stored phone number was rejected as malformed or unroutable. Never retried. */
    INVALID_PHONE_NUMBER,

    /** The sender did not respond in time. Retried, within the attempt cap. */
    SENDER_TIMEOUT,

    /** The sender is down, unconfigured, or out of credit. Retried, within the attempt cap. */
    SENDER_UNAVAILABLE,

    /** The sender refused the message or our credentials. Retried within the cap, in case the cause was fixed. */
    SENDER_REJECTED,

    /** Anything not covered above. Investigate via the correlated log, not this column. */
    UNKNOWN_ERROR
}
