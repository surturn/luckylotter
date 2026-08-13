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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);

    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;
    private final RedemptionCodeGenerator redemptionCodes;

    public FlagCreationService(BusinessRepository businesses,
                               CustomerRepository customers,
                               RetentionFlagRepository flags,
                               OfferRepository offers,
                               RedemptionCodeGenerator redemptionCodes) {
        this.businesses = businesses;
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
        this.redemptionCodes = redemptionCodes;
    }

    /**
     * Flags a customer and generates their offer.
     *
     * <p>Flag and offer commit together (FR-4, FR-5) — a flag with no offer
     * would show in the dashboard as a customer the system noticed and then did
     * nothing about.
     *
     * @return the new offer's ID, or empty if the customer must not be flagged
     *         right now — either they already carry an open flag from a
     *         concurrent or previous run (FR-8), or an offer reached them
     *         recently enough that they are still inside their cooldown (FR-4)
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

        // Locked for the rest of this transaction: the budget ceiling below is a
        // count-then-insert over a rolling window, and two overlapping scans
        // would otherwise both read a count under the cap and both insert.
        Business business = businesses.findByIdForUpdate(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));

        if (withinCooldown(business, customer)) {
            return Optional.empty();
        }

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
        offer.setRedemptionCode(redemptionCodes.generate());
        if (!customer.isContactable()) {
            // Still generated and still visible — just never sendable (FR-5).
            offer.markNoContact();
        } else if (budgetSpent(business)) {
            // The flag is still created: the customer really is lapsing, and
            // hiding that because the budget ran dry would read as healthy
            // retention rather than as exhausted spend (FR-4, FR-7).
            offer.markSuppressedBudget();
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

    /**
     * Whether this business has already committed its offer allowance for the
     * current rolling window (FR-4).
     *
     * <p>This is the bound that holds regardless of whether anyone has worked
     * out how the trigger fires. The cooldown limits what one customer can
     * extract; this limits what the programme can cost in total, which is the
     * number the business actually cares about.
     *
     * <p>Safe against overlapping scans only because the caller holds a write
     * lock on the business row — see {@code BusinessRepository.findByIdForUpdate}.
     */
    private boolean budgetSpent(Business business) {
        Instant windowStart = Instant.now()
                .minus(business.getOfferBudgetWindowDays(), ChronoUnit.DAYS);
        long committed = offers.countCommittedSince(business.getId(), windowStart);
        boolean spent = committed >= business.getOfferCapPerWindow();
        if (spent) {
            log.warn("Offer budget exhausted, suppressing offer: businessId={} "
                            + "committed={} cap={} windowDays={}",
                    business.getId(), committed, business.getOfferCapPerWindow(),
                    business.getOfferBudgetWindowDays());
        }
        return spent;
    }

    /**
     * Whether an offer reached this customer recently enough that they should be
     * left alone (FR-4).
     *
     * <p>This is what stops the loop closing on itself. A resolved flag makes a
     * customer immediately re-flaggable, so without a cooldown the cycle "go
     * quiet, collect a discount, return, go quiet again" repeats indefinitely —
     * training the regulars to space their visits out to harvest offers, which
     * is the opposite of what the product is for.
     *
     * <p>Deliberately silent: a customer inside their cooldown is not a failure
     * and not an anomaly, so this logs at debug and the scan counts them as
     * skipped alongside everyone who simply wasn't quiet enough.
     */
    private boolean withinCooldown(Business business, Customer customer) {
        Instant lastSent = offers.findLastSentAtForCustomer(customer.getId()).orElse(null);
        if (lastSent == null) {
            return false;
        }
        BigDecimal cooldownDays = business.cooldownDaysFor(customer.getAvgIntervalDays());
        Instant eligibleFrom = lastSent.plusSeconds(
                cooldownDays.multiply(SECONDS_PER_DAY).longValue());
        boolean suppressed = Instant.now().isBefore(eligibleFrom);
        if (suppressed) {
            log.debug("Customer within offer cooldown, not re-flagging: customerId={} "
                            + "cooldownDays={} eligibleFrom={}",
                    customer.getId(), cooldownDays, eligibleFrom);
        }
        return suppressed;
    }
}
