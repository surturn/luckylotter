package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.support.Redact;
import com.lucklotter.service.notify.NotificationException;
import com.lucklotter.service.notify.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * A single delivery attempt, in its own transaction (FR-5).
 *
 * <p>A separate bean from {@link OfferDispatchService} because Spring's
 * transactional proxy only applies to calls arriving from outside the bean — a
 * self-call from the dispatch loop would silently run in the caller's
 * transaction (or none), and one failed send would then take the whole batch
 * with it.
 */
@Service
public class OfferSendService {

    private static final Logger log = LoggerFactory.getLogger(OfferSendService.class);

    private final OfferRepository offers;
    private final NotificationSender sender;

    public OfferSendService(OfferRepository offers, NotificationSender sender) {
        this.offers = offers;
        this.sender = sender;
    }

    /**
     * Attempts one offer. A failure is recorded on the offer and swallowed — it
     * must not roll back the flag and offer that were just created (FR-5).
     *
     * @return true if the offer is now {@code SENT}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean send(UUID offerId) {
        Offer offer = offers.findById(offerId)
                .orElseThrow(() -> new NotFoundException("Offer not found"));
        // Queued, or failed with attempts left and a cause a retry could cure.
        // NO_CONTACT and the suppressed states are terminal (FR-5).
        if (!offer.isSendable()) {
            return false;
        }
        // Re-checked here as well as in the backlog query: the customer can come
        // back between the two, and the flag is the only record of it.
        if (offer.getFlag().getStatus() != FlagStatus.ACTIVE) {
            log.info("Offer not sent, customer already returned: offerId={}", offerId);
            return false;
        }
        Customer customer = offer.getFlag().getCustomer();

        try {
            sender.send(offer, customer);
            offer.markSent(Instant.now());
            log.info("Offer sent: offerId={} customerId={}", offerId, customer.getId());
            return true;
        } catch (NotificationException e) {
            // The code is stored; the provider's diagnosis goes only to the log,
            // correlated by offer ID. It is scrubbed first — an SMTP rejection
            // routinely quotes the recipient back at us (NFR-4).
            offer.markFailed(e.getCode());
            log.warn("Offer delivery failed: offerId={} customerId={} code={} attempt={} willRetry={} detail={}",
                    offerId, customer.getId(), e.getCode(), offer.getSendAttempts(),
                    offer.isRetriable(), Redact.scrub(e.getMessage()));
            return false;
        } catch (RuntimeException e) {
            offer.markFailed(OfferFailureCode.UNKNOWN_ERROR);
            // Rendered and scrubbed rather than handed to the logger, which
            // would print the message verbatim above the frames (NFR-4).
            log.error("Offer delivery threw an unmapped error: offerId={} customerId={} attempt={} cause={}",
                    offerId, customer.getId(), offer.getSendAttempts(), Redact.scrubStackTrace(e));
            return false;
        }
    }
}
