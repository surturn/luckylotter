package com.lucklotter.repo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one customer has been worth so far, over their whole history (FR-7).
 *
 * <p>Whole history rather than a reporting window: "value" here answers what the
 * customer is worth to the business, which is the question that decides whether
 * an offer is worth sending. Bounding it to a window would make the same
 * customer's value change depending on which screen they were viewed from.
 */
public interface CustomerValueRow {

    UUID getCustomerId();

    BigDecimal getTotalSpend();

    /**
     * Counts rows, not visits — a split bill is one visit and several
     * transactions. Not a visit count, and not labelled as one anywhere.
     */
    long getTransactionCount();

    BigDecimal getAverageTransactionValue();
}
