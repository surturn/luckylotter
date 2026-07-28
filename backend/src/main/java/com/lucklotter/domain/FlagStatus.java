package com.lucklotter.domain;

/**
 * Lifecycle of a retention flag (FR-8, FR-9). A customer may hold at most one
 * {@link #ACTIVE} flag at a time — enforced by a partial unique index, not just
 * here.
 */
public enum FlagStatus {
    /** Open: the customer's cadence has broken and has not resumed. */
    ACTIVE,
    /** Closed by the customer's next ingested transaction (FR-9). */
    RESOLVED
}
