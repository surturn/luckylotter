package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.repo.BucketCount;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CadenceSpreadRow;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.PageResponse;
import com.lucklotter.web.dto.RetentionDiagnosticResponse;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.CadenceSegment;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.CadenceSpread;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.CustomerBehaviour;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.Definitions;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.ExecutiveSummary;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.InterventionPerformance;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.Opportunity;
import com.lucklotter.web.dto.RetentionDiagnosticResponse.PurchaseBand;
import com.lucklotter.web.dto.RetentionImpactResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the retention diagnostic (§7) for one business (NFR-1).
 *
 * <p>Nothing here computes a figure the rest of the product computes. Customers,
 * flags and money come through the repositories and services the dashboard
 * already reads; this class adds the two things the dashboard has no use for —
 * the distribution of the customer base, and a projection of what the currently
 * drifting customers are worth — and lays everything out in the order a reader
 * wants it.
 *
 * <p>The projection is the only number in the product that is about the future,
 * and it is fenced accordingly: it appears only when there is a real cohort
 * behind it, it carries its inputs on its face, and it is described as what the
 * business's own record predicts rather than as money waiting to be collected.
 */
@Service
public class RetentionDiagnosticService {

    /**
     * Delivered offers the recovery rate must be built on before it is used to
     * project anything.
     *
     * <p>A rate is a division, and a division over four offers produces a
     * percentage with the same authority as one over four hundred. Ten is the
     * point below which the number stops being a measurement of the business and
     * starts being a measurement of the sample; it is deliberately low, because
     * the pilot's whole first month happens under it.
     */
    static final int MIN_COHORT = 10;

    /** How many customers the report names. The full list is its own screen. */
    static final int AT_RISK_SHOWN = 10;

    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final FlagQueryService flagQuery;
    private final RetentionImpactService retentionImpact;

    public RetentionDiagnosticService(BusinessRepository businesses,
                                      CustomerRepository customers,
                                      RetentionFlagRepository flags,
                                      FlagQueryService flagQuery,
                                      RetentionImpactService retentionImpact) {
        this.businesses = businesses;
        this.customers = customers;
        this.flags = flags;
        this.flagQuery = flagQuery;
        this.retentionImpact = retentionImpact;
    }

    @Transactional(readOnly = true)
    public RetentionDiagnosticResponse forBusiness(UUID businessId) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        // Monitored and below-threshold partition the base, so this is the whole
        // customer count and not a fourth query that could disagree with it.
        long monitored = customers.countByBusinessIdAndAvgIntervalDaysIsNotNull(businessId);
        long belowThreshold = customers.countByBusinessIdAndAvgIntervalDaysIsNull(businessId);

        long drifting = flags.countByBusinessIdAndStatus(businessId, FlagStatus.ACTIVE);

        RetentionImpactResponse impact = retentionImpact.forBusiness(businessId);
        Opportunity opportunity = opportunity(drifting, impact);

        PageResponse<FlagSummaryResponse> atRisk = flagQuery.list(
                businessId, FlagStatus.ACTIVE, FlagSort.PRIORITY, 0, AT_RISK_SHOWN);

        return new RetentionDiagnosticResponse(
                Instant.now(),
                new ExecutiveSummary(
                        monitored + belowThreshold,
                        monitored,
                        drifting,
                        flags.countHighValueActiveFlags(businessId,
                                OverviewStatsService.HIGH_VALUE_MULTIPLE),
                        customers.countReturnedAndNotCurrentlyFlagged(businessId),
                        opportunity.available() ? opportunity.estimatedRevenue() : null),
                new CustomerBehaviour(
                        spread(businessId),
                        cadenceSegments(businessId, monitored),
                        purchaseBands(businessId, monitored + belowThreshold)),
                atRisk.items(),
                atRisk.totalItems(),
                new InterventionPerformance(
                        impact.currencyCode(), impact.flaggedNeverContacted(), impact),
                opportunity,
                definitions(business.getRecoveryWindowDays()));
    }

    /**
     * The quartiles of the customers' own rhythms, rounded to a tenth of a day.
     *
     * <p>Rounding stops at a tenth because the figures are the same days/week or
     * days/fortnight a person already thinks in; carrying PostgreSQL's full
     * floating-point expansion onto the page would suggest a precision the
     * underlying cadence — itself a mean of observed gaps — does not have.
     */
    private CadenceSpread spread(UUID businessId) {
        CadenceSpreadRow raw = customers.cadenceSpread(businessId);
        if (raw == null || raw.getMedianDays() == null) {
            return null;
        }
        return new CadenceSpread(
                round(raw.getP25Days()), round(raw.getMedianDays()),
                round(raw.getP75Days()), round(raw.getMeanDays()));
    }

    /**
     * The rhythm bands, in order, with the bands nobody is in still present.
     *
     * <p>Zero rows are kept for the same reason the overview's chart keeps its
     * quiet buckets: a distribution that omits empty bands moves its own
     * boundaries, and the reader comparing two months would be comparing two
     * different charts.
     */
    private List<CadenceSegment> cadenceSegments(UUID businessId, long monitored) {
        Map<String, Long> counts = byBucket(customers.countCustomersByCadenceSegment(businessId));
        List<CadenceSegment> segments = new ArrayList<>(CADENCE_SEGMENTS.size());
        for (CadenceSegmentShape shape : CADENCE_SEGMENTS) {
            long count = counts.getOrDefault(shape.key(), 0L);
            segments.add(new CadenceSegment(shape.key(), shape.label(), shape.range(),
                    shape.visitsPerMonth(), count, share(count, monitored)));
        }
        return segments;
    }

    private List<PurchaseBand> purchaseBands(UUID businessId, long total) {
        Map<String, Long> counts =
                byBucket(customers.countCustomersByPurchaseCountBand(businessId));
        List<PurchaseBand> bands = new ArrayList<>(PURCHASE_BANDS.size());
        for (PurchaseBandShape shape : PURCHASE_BANDS) {
            long count = counts.getOrDefault(shape.key(), 0L);
            bands.add(new PurchaseBand(shape.key(), shape.label(), count, share(count, total)));
        }
        return bands;
    }

    /**
     * What the drifting customers are plausibly worth, or why that cannot be said.
     *
     * <p>Three observed quantities, multiplied:
     *
     * <ol>
     *   <li>how many customers are drifting now;</li>
     *   <li>what share of contacted customers have returned historically;</li>
     *   <li>what a customer who returned spent inside their window.</li>
     * </ol>
     *
     * <p>The product is what this business's own record predicts if it contacts
     * today's drifting customers the way it has contacted everyone else. Every
     * input is on the response so a reader can substitute their own judgement for
     * any of them — which is the difference between a projection and a claim.
     *
     * <p>Returns are floored, not rounded. Half a customer who comes back is not
     * a thing, and rounding up would have the report's own arithmetic add a
     * person to the total it then prices.
     */
    private Opportunity opportunity(long drifting, RetentionImpactResponse impact) {
        String unavailable = unavailableReason(drifting, impact);
        if (unavailable != null) {
            return new Opportunity(false, unavailable, drifting, impact.contacted(),
                    impact.recoveryPercent(), impact.averageRecoveredCustomerValue(),
                    0, null, null, null, method());
        }

        BigDecimal rate = impact.recoveryPercent();
        BigDecimal averageValue = impact.averageRecoveredCustomerValue();
        long expectedReturns = BigDecimal.valueOf(drifting)
                .multiply(rate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                .longValueExact();

        BigDecimal revenue = averageValue.multiply(BigDecimal.valueOf(expectedReturns))
                .setScale(2, RoundingMode.HALF_UP);

        // Costed against the whole scope, not against the expected returns: an
        // offer has to go to every drifting customer to produce any returns at
        // all, and charging only the seven who come back would price the
        // opportunity at a third of what acting on it costs.
        //
        // The per-offer figure is what the programme has historically spent per
        // delivered offer, which for a percentage deal is taken as the discount
        // on the whole basket — an upper bound, and named as one below.
        BigDecimal costPerOffer = impact.interventionCost() == null
                ? null
                : impact.interventionCost()
                        .divide(BigDecimal.valueOf(impact.contacted()), 2, RoundingMode.HALF_UP);
        BigDecimal cost = costPerOffer == null
                ? null
                : costPerOffer.multiply(BigDecimal.valueOf(drifting))
                        .setScale(2, RoundingMode.HALF_UP);

        return new Opportunity(true, null, drifting, impact.contacted(), rate, averageValue,
                expectedReturns, revenue, cost,
                cost == null ? null : revenue.subtract(cost), method());
    }

    /**
     * @return why no estimate can be made, or null when one can. Checked in the
     *         order the reader would ask: is anyone drifting at all, is there a
     *         rate, is the rate built on enough, has anyone ever actually come
     *         back.
     */
    private static String unavailableReason(long drifting, RetentionImpactResponse impact) {
        if (drifting == 0) {
            return "No customers are drifting right now, so there is nothing to project.";
        }
        if (impact.contacted() < MIN_COHORT) {
            return "Only " + impact.contacted() + " offers have been delivered so far. A return "
                    + "rate needs at least " + MIN_COHORT + " before it describes the business "
                    + "rather than the sample.";
        }
        if (impact.returned() == 0) {
            return "No customer has returned after an offer yet, so there is no observed value to "
                    + "project from.";
        }
        return null;
    }

    private static BigDecimal share(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }

    private static Map<String, Long> byBucket(List<BucketCount> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (BucketCount row : rows) {
            counts.merge(row.getBucket(), row.getTotal(), Long::sum);
        }
        return counts;
    }

    /** A band as declared, so the page renders the same bands whether or not they are empty. */
    private record CadenceSegmentShape(String key, String label, String range,
                                       String visitsPerMonth) {
    }

    private record PurchaseBandShape(String key, String label) {
    }

    /**
     * The rhythm bands, on the customer's own interval.
     *
     * <p>The boundaries are the round calendar intervals a business plans in —
     * weekly, fortnightly, monthly, quarterly — not the trigger's thresholds,
     * which carry the business's sensitivity and clamp and would segment the
     * customers by the settings instead. A month is 45 days and a quarter 120
     * because a band is a home for the customers near an interval, and a
     * customer who comes every five weeks is a monthly customer to everyone
     * except a query.
     */
    private static final List<CadenceSegmentShape> CADENCE_SEGMENTS = List.of(
            new CadenceSegmentShape("WEEKLY", "Weekly", "up to 10 days", "3+ visits a month"),
            new CadenceSegmentShape("FORTNIGHTLY", "Fortnightly", "11 to 21 days", "about 2 a month"),
            new CadenceSegmentShape("MONTHLY", "Monthly", "22 to 45 days", "about 1 a month"),
            new CadenceSegmentShape("QUARTERLY", "Quarterly", "46 to 120 days", "3 to 8 a year"),
            new CadenceSegmentShape("RARER", "Less often", "over 120 days", "under 3 a year"));

    /**
     * The purchase-count bands, on purchases rather than visits, matching
     * {@code customer.transaction_count}. The first band is the population the
     * engine cannot judge.
     */
    private static final List<PurchaseBandShape> PURCHASE_BANDS = List.of(
            new PurchaseBandShape("ONE_OR_TWO", "Fewer than 3 purchases — no rhythm yet"),
            new PurchaseBandShape("THREE_TO_FIVE", "3 to 5 purchases"),
            new PurchaseBandShape("SIX_TO_TEN", "6 to 10 purchases"),
            new PurchaseBandShape("ELEVEN_TO_TWENTY", "11 to 20 purchases"),
            new PurchaseBandShape("TWENTY_ONE_PLUS", "21 or more purchases"));

    /**
     * The method behind the projected figure, written out.
     *
     * <p>Sent with the number rather than kept in a document, because this is the
     * one figure in the product that is not a count of something that happened.
     * §7 is explicit that the methodology must be visible, and a projection whose
     * method lives in a slide deck is an assumption wearing a currency symbol.
     */
    private static String method() {
        return "Customers drifting today, multiplied by the share of contacted customers who "
                + "have returned within the recovery window, multiplied by what a returning "
                + "customer has spent in that window. All three come from this business's own "
                + "record — no industry benchmark and no assumed conversion rate is used "
                + "anywhere. Returns are rounded down. Cost is what the programme has spent per "
                + "delivered offer, charged against every drifting customer rather than only the "
                + "ones expected back, because an offer has to go to all of them. For a "
                + "percentage deal that cost is taken from the whole returning basket, so it is "
                + "the most the discount could have cost rather than the least. This is the "
                + "value of the returns the programme has historically produced, not revenue the "
                + "offers cause: nothing here distinguishes a customer who returned because of "
                + "an offer from one who was coming back anyway, and separating the two needs a "
                + "control group.";
    }

    private Definitions definitions(int recoveryWindowDays) {
        return new Definitions(
                "Every customer on record, including the ones there is not enough history to "
                        + "judge.",
                "Customers with at least three purchases, so a personal rhythm exists. Only "
                        + "these can be flagged.",
                "Customers currently past their own threshold — the same population as the open "
                        + "flags on the dashboard.",
                "Drifting customers whose total spend is at least twice the average customer's.",
                "Customers who were flagged before, came back, and are not drifting now. A "
                        + "customer who returned and has since gone quiet again is counted as "
                        + "drifting instead, because that is the state that needs acting on.",
                "How long this business's customers go between visits. The middle value is the "
                        + "typical customer; a quarter visit more often than the first figure and "
                        + "a quarter less often than the last. A mean far from the middle means "
                        + "the customer base is lopsided.",
                "Customers grouped by how often they visit, counting only those with enough "
                        + "history for a rhythm. The bands include ones nobody is in, so two "
                        + "reports can be compared band for band.",
                "Customers grouped by how many purchases are on file. The first band cannot be "
                        + "flagged at all — there is no rhythm yet to drift from — so it is a "
                        + "data gap rather than a retention problem.",
                "What the currently drifting customers are plausibly worth if contacted the way "
                        + "this business has always contacted people, built from its own recovery "
                        + "rate and its own returning-customer spend. Not recoverable revenue, "
                        + "and withheld entirely when the cohort behind the rate is too small to "
                        + "carry it.");
    }

}
