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
    /**
     * A send was attempted and threw. Retried by later scans, but only within
     * {@link OfferRetryPolicy}: a bounded number of attempts, and never for a
     * failure the stored contact detail caused.
     */
    FAILED,
    /** Terminal: the customer has neither contact_email nor contact_phone. */
    NO_CONTACT,
    /**
     * Terminal: the business's offer cap for the current window was already
     * spent when this offer was generated (FR-4).
     *
     * <p>The flag is still created and the customer still appears on the
     * dashboard. Skipping the flag would tell the admin their retention is
     * healthy when what actually ran out was their budget, and the count of
     * offers in this state is the evidence for raising the cap.
     *
     * <p>Never retried: the window it was refused in has passed by the time
     * anything would look again, and quietly sending a month-old win-back is
     * worse than not sending it.
     */
    SUPPRESSED_BUDGET,
    /**
     * Terminal: the customer came back, and their flag resolved, while this offer
     * was still waiting to be sent or to be retried.
     *
     * <p>Closed rather than left {@link #PENDING}: a win-back email to someone who
     * walked in yesterday is worse than none. Carries no failure code, because
     * nothing failed, and never counts against the budget, because it will
     * never reach anyone.
     */
    SUPPRESSED_RETURNED
}
