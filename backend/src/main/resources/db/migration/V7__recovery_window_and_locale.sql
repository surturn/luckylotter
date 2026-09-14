-- ----------------------------------------------------------------------------
-- Recovery measurement window, and the business's own money/locale.
--
-- Until this migration the recovery rate counted a resolved flag as a return
-- however long the customer took, and whether or not anyone ever reached them:
-- the denominator was every flag the business had ever raised, including the
-- ones whose offer landed at NO_CONTACT, FAILED or SUPPRESSED_BUDGET. A flag
-- nobody was contacted about cannot have been recovered by the intervention,
-- and counting it as a miss understates the programme while making the number
-- impossible to explain.
--
-- So a return is now a transaction inside a defined window measured from the
-- moment the offer actually reached the customer. Everything the recovery rate
-- and the revenue figures are made of is then a thing that really happened.
--
--   return  <=>  exists a transaction for this customer with
--                occurred_at in [offer.sent_at, offer.sent_at + window]
--
-- The window is per-business because it is a commercial judgement about how
-- long a win-back is still the win-back's doing. Fourteen days is the default
-- the sales conversation is written around; a business with a quarterly cadence
-- will want longer.
--
-- Anchored on sent_at rather than flagged_at deliberately: between the two sits
-- the cooldown check, the budget check, contactability and delivery, and a
-- customer who comes back while their offer is still sitting unsent came back
-- for their own reasons.
-- ----------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN recovery_window_days INTEGER NOT NULL DEFAULT 14
        CHECK (recovery_window_days > 0);

-- ----------------------------------------------------------------------------
-- Currency and timezone.
--
-- The product is not built for one country, so no amount may be rendered with a
-- hard-coded symbol. Amounts stay plain NUMERIC in the transaction currency and
-- are labelled from here; no conversion happens anywhere, and no figure crosses
-- two currencies because a business has exactly one.
--
-- The default exists for rows that predate the column, not as a recommendation:
-- the demo seeder sets the currency its own fixture is actually denominated in.
-- ----------------------------------------------------------------------------
ALTER TABLE businesses
    ADD COLUMN currency_code VARCHAR(3) NOT NULL DEFAULT 'USD'
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
