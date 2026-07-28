package com.lucklotter.domain;

/** Fixed, non-configurable tuning constants for the retention engine. */
public final class RetentionConstants {

    /**
     * Transactions a customer must have before {@code avg_interval_days} is
     * computed and they become flaggable (FR-2, FR-3). Two visits give one
     * interval — not enough to call a rhythm. Deliberately a constant, not
     * per-business config, in Phase 1: no exceptions.
     */
    public static final int MIN_TRANSACTIONS = 3;

    private RetentionConstants() {
    }
}
