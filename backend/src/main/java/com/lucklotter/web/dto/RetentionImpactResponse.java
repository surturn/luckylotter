package com.lucklotter.web.dto;

import java.math.BigDecimal;

/**
 * What the win-back programme has been worth, over the whole history of the
 * business (FR-7).
 *
 * <p>Every figure here comes from one query, so they describe the same cohort —
 * see {@code RetentionFlagRepository#retentionImpact}. The definitions travel
 * with the numbers rather than living in documentation, because a dashboard
 * whose recovery rate means something slightly different from the reader's
 * assumption is worse than one that shows no rate at all.
 *
 * @param currencyCode ISO 4217 code every amount in this response is in. Sent
 *                     once at the top rather than repeated per figure, and never
 *                     converted: a business records in exactly one currency.
 * @param contacted            flags whose offer actually reached the customer
 * @param returned             of those, how many bought again inside the window
 * @param recoveryPercent      null when nothing has been contacted yet — no rate
 *                             yet, which is not a rate of 0%
 * @param revenueFromReturnedCustomers
 *                             what returning customers spent inside their own
 *                             windows. Deliberately named for the customers, not
 *                             for the intervention: nothing here proves the
 *                             purchase would not have happened anyway, and that
 *                             claim needs a control group
 * @param interventionCost     null when any delivered offer was a free item — see
 *                             {@link #freeItemOffers}
 * @param netValue             revenue minus cost; null whenever the cost is
 *                             unknowable, because a net figure built on a
 *                             partial cost is a number that flatters itself
 * @param averageRecoveredCustomerValue null when nobody has returned yet
 * @param freeItemOffers       delivered offers whose value cannot be expressed in
 *                             currency, since a free item carries no price in
 *                             the transaction data
 * @param flaggedNeverContacted flags raised that no offer ever reached
 */
public record RetentionImpactResponse(
        String currencyCode,
        int recoveryWindowDays,
        long contacted,
        long returned,
        BigDecimal recoveryPercent,
        BigDecimal revenueFromReturnedCustomers,
        BigDecimal interventionCost,
        BigDecimal netValue,
        BigDecimal averageRecoveredCustomerValue,
        long freeItemOffers,
        long flaggedNeverContacted,
        Definitions definitions
) {

    /**
     * The plain-language version of each figure above, rendered next to it in the
     * UI so an operator can explain the number without asking an engineer what
     * the denominator was.
     */
    public record Definitions(
            String contacted,
            String returned,
            String revenueFromReturnedCustomers,
            String interventionCost,
            String netValue
    ) {
    }
}
