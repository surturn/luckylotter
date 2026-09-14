package com.lucklotter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The retention diagnostic (§7) — the dashboard's evidence, assembled into one
 * document an operator can read top to bottom or hand to someone else.
 *
 * <p>Why this exists as a separate output rather than one more screen of cards:
 * the dashboard answers "what should I do this morning", and this answers "what
 * is going on with my customers". Those want different things from the same
 * data. The dashboard leads with the newest and most urgent; the diagnostic
 * leads with the shape of the whole base, because the question it serves is
 * whether the retention problem is getting better or worse.
 *
 * <p>It is assembled, not recomputed. Intervention performance is
 * {@link RetentionImpactResponse} and at-risk customers are the ranked flag
 * rows, both fetched through the same services the dashboard uses. A report that
 * calculated its own recovery rate would eventually disagree with the dashboard
 * about it, and the first person to notice would be the one deciding whether to
 * keep paying for this.
 *
 * <p>Every section is stated in counts of things that were observed. The single
 * projection in here — {@link Opportunity} — is marked as one, carries its own
 * inputs, and is withheld entirely rather than estimated loosely.
 */
public record RetentionDiagnosticResponse(
        Instant generatedAt,

        ExecutiveSummary executiveSummary,

        CustomerBehaviour customerBehaviour,

        /**
         * The customers to act on, in the flag list's own priority order and cut
         * to the same definition of "active". Truncated — the report is a
         * summary, and the full list is a page of its own.
         */
        List<FlagSummaryResponse> atRiskCustomers,
        /** How many active flags exist in total, so the truncation is visible. */
        long atRiskTotal,

        InterventionPerformance interventionPerformance,

        Opportunity opportunity,

        Definitions definitions
) {

    /**
     * The six figures that answer "how is retention going", before any of the
     * evidence behind them.
     *
     * <p>Deliberately counts only, except the last. A summary that contains
     * derived numbers is a summary whose readers argue about the arithmetic
     * instead of the business.
     */
    public record ExecutiveSummary(
            /** Every customer on record, whether or not they can be judged. */
            long customersAnalyzed,
            /** Those with enough history for a rhythm to be measured. */
            long customersWithSufficientHistory,
            /** Currently past their own threshold — the open flags. */
            long customersShowingCadenceDisruption,
            /** Of those, the ones whose spend is well above the business's average. */
            long highValueCustomersAtRisk,
            /** Customers who returned after a previous intervention. */
            long customersReturnedAfterIntervention,
            /**
             * Null whenever {@link Opportunity#available()} is false. Copied
             * from the opportunity section rather than recomputed, so the
             * headline here and the working below cannot disagree.
             */
            BigDecimal estimatedRetentionOpportunity
    ) {
    }

    /** What this business's customers actually do (§7). */
    public record CustomerBehaviour(
            /**
             * The quartiles and mean of how long this business's customers go
             * between visits. Nulls when nobody has a measurable rhythm.
             */
            CadenceSpread typicalPurchaseIntervals,
            /** Customers per rhythm band, summing to the monitored population. */
            List<CadenceSegment> cadenceSegments,
            /** Customers per purchase-count band, summing to the whole base. */
            List<PurchaseBand> purchaseCountDistribution
    ) {
    }

    /**
     * @param p25Days  a quarter of customers visit more often than this
     * @param medianDays the middle customer — half more often, half less
     * @param p75Days  a quarter visit less often than this
     * @param meanDays null whenever the quartiles are; returned beside them
     *                 because a mean far from the median says the base is
     *                 lopsided, which no single figure would reveal
     */
    public record CadenceSpread(BigDecimal p25Days,
                                BigDecimal medianDays,
                                BigDecimal p75Days,
                                BigDecimal meanDays) {
    }

    /**
     * One rhythm band.
     *
     * @param key             {@code WEEKLY}, {@code FORTNIGHTLY} and so on
     * @param intervalRange   the band in days, in the operator's words, so the
     *                        boundaries are on the page rather than implied
     * @param visitsPerMonth  what the band means in visits a month — the same
     *                        fact restated in the unit a business plans in
     * @param customers       customers in the band
     * @param sharePercent    null when there are no monitored customers at all:
     *                        there is no share of nothing, and 0% would read as
     *                        a finding
     */
    public record CadenceSegment(String key,
                                 String label,
                                 String intervalRange,
                                 String visitsPerMonth,
                                 long customers,
                                 BigDecimal sharePercent) {
    }

    /**
     * One purchase-count band.
     *
     * <p>The first band is the population that can never be flagged — under
     * {@code MIN_TRANSACTIONS} visits there is no rhythm to drift from — which is
     * why the report shows it: an operator reading "12 customers have not bought
     * enough times to be judged" is looking at a data gap, not a retention
     * problem, and the two need different responses.
     */
    public record PurchaseBand(String key,
                               String label,
                               long customers,
                               BigDecimal sharePercent) {
    }

    /**
     * The intervention record, embedded from {@link RetentionImpactResponse}
     * rather than restated.
     *
     * <p>Same figures, same definitions, same query. The alternative is a report
     * that reconciles with the dashboard only until one of them is changed.
     */
    public record InterventionPerformance(
            String currencyCode,
            long flaggedNeverContacted,
            RetentionImpactResponse impact
    ) {
    }

    /**
     * What the currently drifting customers are plausibly worth (§7).
     *
     * <p>Computed from three observed quantities and no invention:
     * how many customers are drifting now, what share of contacted customers
     * have historically returned, and what a returning customer has historically
     * spent. Multiply them and the answer is what this business's own record
     * predicts if it contacts the same way it always has.
     *
     * <p>It is not recoverable revenue, and the wording never says it is.
     * Nothing in the data separates a customer who returned because of an offer
     * from one who was coming back anyway; establishing that needs a control
     * group (§15), and until there is one this is the value of the returns, not
     * the value of the intervening.
     *
     * <p>{@link #available()} is false and every figure null when the evidence
     * is too thin to carry the number: fewer than {@code MIN_COHORT} delivered
     * offers, or nobody has ever returned. A rate over a handful of offers is
     * arithmetic on noise, and a report that publishes one teaches its reader to
     * trust the next report it publishes.
     */
    public record Opportunity(
            boolean available,
            /** Why not, in the operator's words. Null when available. */
            String unavailableReason,
            /** Customers drifting right now — the population any estimate is about. */
            long customersInScope,
            /** Delivered offers the historical rate is measured over. */
            long cohortContacted,
            /** The rate itself, as a percentage. */
            BigDecimal historicalRecoveryPercent,
            /** What a customer who returned has historically spent. */
            BigDecimal averageReturningCustomerValue,
            /** Expected returns: scope multiplied by the rate, rounded down. */
            long expectedReturns,
            BigDecimal estimatedRevenue,
            /** Null when any delivered offer was a free item and the cost is unknowable. */
            BigDecimal estimatedCost,
            /** Revenue less cost; null on the same condition as the cost. */
            BigDecimal estimatedNet,
            /** The method in full, rendered with the section it explains. */
            String method
    ) {
    }

    /** Plain-language definitions, rendered beside the figures they explain. */
    public record Definitions(String customersAnalyzed,
                              String customersWithSufficientHistory,
                              String customersShowingCadenceDisruption,
                              String highValueCustomersAtRisk,
                              String customersReturnedAfterIntervention,
                              String typicalPurchaseIntervals,
                              String cadenceSegments,
                              String purchaseCountDistribution,
                              String opportunity) {
    }
}
