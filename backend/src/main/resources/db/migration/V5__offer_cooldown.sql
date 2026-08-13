-- ----------------------------------------------------------------------------
-- Offer cooldown: how long a customer is left alone after one is delivered.
--
-- Without this, a resolved flag makes a customer immediately re-flaggable, and
-- the loop closes on itself: go quiet, receive a discount, come back, go quiet
-- again. That teaches the customers who like the business most to space their
-- visits out in order to harvest offers — the exact behaviour the product
-- exists to reverse. It is also the whole payoff of reverse-engineering the
-- trigger, so capping it removes most of the reason to bother.
--
-- Measured against the customer's own rhythm rather than a flat number, for the
-- same reason the flag threshold is (FR-3): thirty days of silence is a lapse
-- for a weekly regular and unremarkable for someone who comes twice a year.
--
--   cooldown_days = max(offer_cooldown_days,
--                       avg_interval_days * offer_cooldown_multiplier)
--
-- The floor is what a customer with no cadence on file falls back to, and stops
-- a very frequent visitor from being eligible again within days.
--
-- Deliberately not the business's whole defence: this bounds what one customer
-- can extract, not what the programme costs in total. Aggregate spend is a
-- separate ceiling.
-- ----------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN offer_cooldown_days INTEGER NOT NULL DEFAULT 30
        CHECK (offer_cooldown_days >= 0),
    ADD COLUMN offer_cooldown_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 3.00
        CHECK (offer_cooldown_multiplier >= 0);

-- The cooldown lookup is "the most recent offer actually delivered to this
-- customer", which reaches offers through that customer's flags — all of them,
-- not just the open one. The existing partial unique index covers only
-- status = 'ACTIVE', so it cannot serve this; without a plain index on the
-- column, every candidate the scan considers costs a sequential scan of
-- retention_flags. The offers side needs nothing: offers.flag_id is already
-- unique (NFR-5).
CREATE INDEX idx_retention_flags_customer ON retention_flags(customer_id);
