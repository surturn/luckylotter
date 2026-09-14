package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.repo.RetentionImpactRow;
import com.lucklotter.web.dto.RetentionImpactResponse;
import com.lucklotter.web.dto.RetentionImpactResponse.Definitions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * The economic half of the dashboard (FR-7).
 *
 * <p>The rest of the overview answers "did customers move?". This answers "was
 * it worth the discount?", which is the question the buyer is actually asking.
 *
 * <p>Two things it deliberately does not do. It does not call any of this
 * incremental revenue — without a control group, what these customers spent
 * after an offer is what they spent, not what the offer caused, and the wording
 * throughout says so. And it does not invent a cost for a free-item deal, since
 * the transaction data carries no item prices; it reports how many offers are
 * unaccounted for and leaves the total null rather than understating it.
 */
@Service
public class RetentionImpactService {

    private final BusinessRepository businesses;
    private final RetentionFlagRepository flags;

    public RetentionImpactService(BusinessRepository businesses, RetentionFlagRepository flags) {
        this.businesses = businesses;
        this.flags = flags;
    }

    /**
     * The recovery rate over a window of <em>offer delivery</em>, for any caller
     * that needs one — the overview's hero card and both halves of its period
     * comparison go through here rather than each computing their own.
     *
     * @param from inclusive lower bound on {@code offers.sent_at}
     * @param to   exclusive upper bound, so an offer sent exactly on a boundary
     *             is not counted in two adjacent periods
     */
    @Transactional(readOnly = true)
    public RecoveryCohort cohort(UUID businessId, Instant from, Instant to) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        return RecoveryCohort.of(flags.retentionImpact(businessId, from, to),
                business.getRecoveryWindowDays());
    }

    @Transactional(readOnly = true)
    public RetentionImpactResponse forBusiness(UUID businessId) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        Instant now = Instant.now();
        RetentionImpactRow raw = flags.retentionImpact(businessId, Instant.EPOCH, now);
        int windowDays = business.getRecoveryWindowDays();

        BigDecimal revenue = scale(raw.getRevenueFromReturned());
        // A free item has no price anywhere in the data, so a cost total that
        // excludes it would be a smaller number presented as the whole. Null
        // instead, with the count of what is missing beside it.
        BigDecimal cost = raw.getFreeItemOffers() > 0 ? null : scale(raw.getInterventionCost());
        BigDecimal net = cost == null ? null : revenue.subtract(cost);

        return new RetentionImpactResponse(
                business.getCurrencyCode(),
                windowDays,
                raw.getContacted(),
                raw.getReturned(),
                RecoveryCohort.of(raw, windowDays).percent(),
                revenue,
                cost,
                net,
                raw.getReturned() == 0
                        ? null
                        : revenue.divide(BigDecimal.valueOf(raw.getReturned()), 2, RoundingMode.HALF_UP),
                raw.getFreeItemOffers(),
                flags.countFlagsWithoutDeliveredOffer(businessId),
                definitions(windowDays, raw.getFreeItemOffers() > 0));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Definitions definitions(int windowDays, boolean costIncomplete) {
        return new Definitions(
                "Flags whose offer was actually delivered to the customer. Offers that could not be "
                        + "sent — no contact details, a delivery failure, or a spent budget — are "
                        + "counted separately below, not as misses here.",
                "Customers who bought again within " + windowDays + " days of their offer being sent.",
                "Everything those customers spent in that window, in the business's own currency. "
                        + "This is revenue from customers who returned after an intervention — not "
                        + "revenue the intervention caused, which would need a control group.",
                costIncomplete
                        ? "Cannot be totalled: at least one delivered offer was a free item, and the "
                        + "transaction data carries no item prices to value it with."
                        : "Percentage offers are costed on the returning basket, so this is the most "
                        + "they could have cost; fixed-amount offers are costed at face value.",
                costIncomplete
                        ? "Not shown while any offer's cost is unknown — a net figure built on a "
                        + "partial cost would overstate the result."
                        : "Revenue from returned customers minus the recorded intervention cost.");
    }
}
