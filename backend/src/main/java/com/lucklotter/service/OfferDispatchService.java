package com.lucklotter.service;

import com.lucklotter.domain.Offer;
import com.lucklotter.repo.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Walks a business's sendable offer backlog (FR-5).
 *
 * <p>Holds no transaction of its own — each attempt is committed independently
 * by {@link OfferSendService}, so one customer's failed delivery leaves the
 * others sent.
 */
@Service
public class OfferDispatchService {

    private static final Logger log = LoggerFactory.getLogger(OfferDispatchService.class);

    private final OfferRepository offers;
    private final OfferSendService offerSend;

    public OfferDispatchService(OfferRepository offers, OfferSendService offerSend) {
        this.offers = offers;
        this.offerSend = offerSend;
    }

    public void dispatchSendable(UUID businessId) {
        List<Offer> backlog = offers.findByBusinessIdAndStatusIn(businessId, OfferSendService.SENDABLE);
        if (backlog.isEmpty()) {
            return;
        }
        int sent = 0;
        for (Offer offer : backlog) {
            if (offerSend.send(offer.getId())) {
                sent++;
            }
        }
        log.info("Offer dispatch: businessId={} attempted={} sent={} failed={}",
                businessId, backlog.size(), sent, backlog.size() - sent);
    }
}
