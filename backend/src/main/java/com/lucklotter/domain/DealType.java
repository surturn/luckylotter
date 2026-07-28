package com.lucklotter.domain;

/**
 * Kind of win-back deal a business offers (FR-4, FR-6). Phase 1 uses a single
 * static default per business — no escalation by inactivity duration.
 */
public enum DealType {
    /** {@code deal_value} is a percentage, e.g. 30 => 30% off. */
    PERCENT_OFF,
    /** {@code deal_value} is an absolute currency amount off. */
    FIXED_AMOUNT_OFF,
    /** {@code deal_value} is the count of free items granted. */
    FREE_ITEM
}
