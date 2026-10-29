package com.lucklotter.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * When a failed delivery is worth trying again (FR-5).
 *
 * <p>The scan runs nightly, so a retry is a day after the failure: that is the
 * backoff, and three attempts is about three days of patience for a transient
 * fault such as a mail provider outage.
 *
 * <p>Some failures are never retried, because trying again with the same data
 * can only fail the same way. A bad address is the case that matters: a
 * mailbox that bounces tonight bounces tomorrow, and a sending domain that keeps
 * mailing bouncing addresses loses its reputation for everyone. Those offers
 * stay {@code FAILED}, visible on the dashboard, until the customer's contact
 * detail actually changes (see {@code Offer.reopenForRetry}).
 */
public final class OfferRetryPolicy {

    /** Total delivery attempts per offer, the first included. */
    public static final int MAX_SEND_ATTEMPTS = 3;

    /** Failures caused by the stored contact detail, which a retry cannot fix. */
    public static final Set<OfferFailureCode> PERMANENT = EnumSet.of(
            OfferFailureCode.INVALID_EMAIL_ADDRESS,
            OfferFailureCode.INVALID_PHONE_NUMBER);

    private OfferRetryPolicy() {
    }

    public static boolean isPermanent(OfferFailureCode code) {
        return code != null && PERMANENT.contains(code);
    }
}
