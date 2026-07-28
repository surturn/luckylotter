package com.lucklotter.service.notify;

import com.lucklotter.domain.OfferFailureCode;

/**
 * A delivery attempt failed.
 *
 * <p>Carries a bounded {@link OfferFailureCode} for storage and a free-text
 * message for the log. The message may quote a provider verbatim and so may
 * contain an email address or phone number — it must never be written to
 * {@code offers.failure_code} (NFR-4).
 */
public class NotificationException extends RuntimeException {

    private final transient OfferFailureCode code;

    public NotificationException(OfferFailureCode code, String message) {
        super(message);
        this.code = code;
    }

    public NotificationException(OfferFailureCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public OfferFailureCode getCode() {
        return code;
    }
}
