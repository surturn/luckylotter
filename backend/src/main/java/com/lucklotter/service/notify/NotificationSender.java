package com.lucklotter.service.notify;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.Offer;

/**
 * Delivers a win-back offer to a customer (FR-5, §6).
 *
 * <p>Phase 1 ships {@link LoggingNotificationSender} only; SMS or email
 * providers arrive behind this same interface once a pilot has budget.
 *
 * <p>Implementations must translate provider errors into a
 * {@link NotificationException} carrying an
 * {@link com.lucklotter.domain.OfferFailureCode} — the provider's own message
 * goes in the exception, which is logged, and never into the stored
 * {@code failure_code} column (NFR-4).
 */
public interface NotificationSender {

    /**
     * @param customer the recipient; guaranteed
     *                 {@link Customer#isContactable()} — a customer with no
     *                 contact details never reaches a sender, their offer is
     *                 {@code NO_CONTACT}
     * @throws NotificationException if delivery failed
     */
    void send(Offer offer, Customer customer) throws NotificationException;
}
