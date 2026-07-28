package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates a flag and its offer as one unit (FR-4, FR-8).
 *
 * <p>Separate from {@link RetentionScanService} on purpose: each customer is
 * flagged in its <em>own</em> transaction, so one customer hitting the "already
 * flagged" unique index doesn't roll back the whole scan. That is also why this
 * is a distinct bean — a self-call would bypass the transactional proxy and
 * quietly run in the caller's transaction instead.
 */
@Service
public class FlagCreationService {

    private static final Logger log = LoggerFactory.getLogger(FlagCreationService.class);

    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;

    public FlagCreationService(BusinessRepository businesses,
                               CustomerRepository customers,
                               RetentionFlagRepository flags,
                               OfferRepository offers) {
        this.businesses = businesses;
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
    }

    /**
     * Flags a customer and generates their offer.
     *
     * <p>Flag and offer commit together (FR-4, FR-5) — a flag with no offer
     * would show in the dashboard as a customer the system noticed and then did
     * nothing about.
     *
     * @return the new offer's ID, or empty if the customer was already flagged
     *         by a concurrent or previous run (FR-8)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> flagAndGenerateOffer(UUID customerId,
                                               UUID businessId,
                                               BigDecimal thresholdDaysApplied) {
        // Cheap pre-check for the common case; the unique index below is what
        // actually makes this safe under concurrency.
        if (flags.existsByCustomerIdAndStatus(customerId, FlagStatus.ACTIVE)) {
            return Optional.empty();
        }

        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(thresholdDaysApplied);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        // Snapshot, not a reference: retuning the config later must not rewrite
        // what was already offered (FR-4).
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        if (!customer.isContactable()) {
            // Still generated and still visible — just never sendable (FR-5).
            offer.markNoContact();
        }

        try {
            flags.save(flag);
            offers.saveAndFlush(offer);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index fired: another run flagged this customer
            // between the pre-check and here. That is the constraint doing its
            // job, not an error (FR-8, NFR-3).
            log.debug("Customer already flagged concurrently: customerId={}", customerId);
            return Optional.empty();
        }

        log.info("Customer flagged: businessId={} customerId={} flagId={} offerId={} "
                        + "thresholdDaysApplied={} avgIntervalDaysAtFlag={} offerStatus={}",
                businessId, customerId, flag.getId(), offer.getId(),
                thresholdDaysApplied, flag.getAvgIntervalDaysAtFlag(), offer.getStatus());
        return Optional.of(offer.getId());
    }
}
