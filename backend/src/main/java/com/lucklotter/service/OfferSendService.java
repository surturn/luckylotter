package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.service.notify.NotificationException;
import com.lucklotter.service.notify.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    /**
     * {@code NO_CONTACT} is absent by design: it is terminal, and retrying it
     * would mean attempting to send to a customer with nothing to send to
     * (FR-5).
     */
    static final List<OfferStatus> SENDABLE = List.of(OfferStatus.PENDING, OfferStatus.FAILED);

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
        if (!SENDABLE.contains(offer.getStatus())) {
            return false;
        }
        Customer customer = offer.getFlag().getCustomer();

        try {
            sender.send(offer, customer);
            offer.markSent(Instant.now());
            log.info("Offer sent: offerId={} customerId={}", offerId, customer.getId());
            return true;
        } catch (NotificationException e) {
            // The code is stored; the provider's message — which may quote an
            // email address or phone number back at us — goes only to the log,
            // correlated by offer ID (NFR-4).
            offer.markFailed(e.getCode());
            log.warn("Offer delivery failed: offerId={} customerId={} code={} detail={}",
                    offerId, customer.getId(), e.getCode(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            offer.markFailed(OfferFailureCode.UNKNOWN_ERROR);
            log.error("Offer delivery threw an unmapped error: offerId={} customerId={}",
                    offerId, customer.getId(), e);
            return false;
        }
    }
}
