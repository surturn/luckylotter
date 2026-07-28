-- Redemption codes on offers.
--
-- A win-back message with no code is just an announcement: the customer has
-- nothing to present and the business has nothing to recognise. The code makes
-- the offer a concrete thing the message can carry.
--
-- Nullable, because offers generated before this migration have no code and
-- backfilling one would invent a code that was never sent to anybody.
--
-- Unique per business, not globally: two businesses independently issuing
-- "K7Q4M2" is not a collision anyone cares about, and a global unique index
-- would make one tenant's code space contend with another's.
--
-- Phase 1 generates and displays codes. It does NOT record redemption — there
-- is no redemption table, no staff verification endpoint, and nothing marks a
-- code as used. Tracking that is Phase 2 work; until then the code is a
-- reference the business honours manually.
ALTER TABLE offers
    ADD COLUMN redemption_code VARCHAR(16);

CREATE UNIQUE INDEX uq_offers_business_redemption_code
    ON offers(business_id, redemption_code)
    WHERE redemption_code IS NOT NULL;
