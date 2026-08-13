-- ----------------------------------------------------------------------------
-- Offer budget ceiling: a bound on what the programme can cost in total.
--
-- The cooldown bounds what one customer can extract. This bounds the aggregate,
-- and unlike the cooldown it holds whether or not anyone has worked out how the
-- trigger fires — which is why it, not the unpredictability measures, is the
-- real protection.
--
-- Counted in offers, not currency, because currency is not knowable here:
-- deal_value is a percentage, an absolute amount or a count of free items
-- depending on deal_type, so summing it is meaningless, and nothing records
-- redemption yet so an unredeemed offer costs nothing anyway. That is not a
-- weaker cap than a monetary one: Phase 1 pins a single static deal per
-- business, so every offer it issues carries the same value and
-- cap * deal_value is the exposure exactly.
--
-- Rolling window rather than a calendar month. A monthly reset is a cliff:
-- spend arrives in a burst on the 1st, and anyone who works out the reset date
-- learns to time their return to it.
-- ----------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN offer_cap_per_window INTEGER NOT NULL DEFAULT 100
        CHECK (offer_cap_per_window >= 0),
    ADD COLUMN offer_budget_window_days INTEGER NOT NULL DEFAULT 30
        CHECK (offer_budget_window_days > 0);

-- A fourth terminal status. The customer is still flagged and still visible:
-- hiding a lapse because the budget is dry would tell the admin their retention
-- is fine when it is their spend that ran out, and the gap between "lapsing"
-- and "contacted" is the number that justifies raising the cap.
-- SUPPRESSED_BUDGET is 17 characters and the column was sized VARCHAR(16) for
-- the four statuses that existed in V1, so it has to be widened before the
-- value can be stored at all.
ALTER TABLE offers ALTER COLUMN status TYPE VARCHAR(24);

-- Not IF EXISTS: this is Postgres's auto-generated name for the inline CHECK in
-- V1, and if that assumption is ever wrong the migration must fail here rather
-- than quietly leave the old four-value constraint in place and reject the new
-- status at runtime.
ALTER TABLE offers DROP CONSTRAINT offers_status_check;
ALTER TABLE offers ADD CONSTRAINT offers_status_check
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'NO_CONTACT', 'SUPPRESSED_BUDGET'));

-- SUPPRESSED_BUDGET carries no failure_code: nothing failed. The send was never
-- attempted, which is a business decision rather than a delivery outcome.
ALTER TABLE offers DROP CONSTRAINT chk_offer_failure_code_by_status;
ALTER TABLE offers ADD CONSTRAINT chk_offer_failure_code_by_status CHECK (
    (status IN ('PENDING', 'SENT', 'SUPPRESSED_BUDGET') AND failure_code IS NULL) OR
    (status = 'FAILED'     AND failure_code IS NOT NULL) OR
    (status = 'NO_CONTACT' AND failure_code = 'MISSING_CONTACT_DETAILS')
);

-- The ceiling check counts a business's offers inside the rolling window on
-- every candidate the scan considers, so it needs to be an index range scan
-- rather than a filter over the business's whole offer history (NFR-5).
CREATE INDEX idx_offers_business_created ON offers (business_id, created_at DESC);
