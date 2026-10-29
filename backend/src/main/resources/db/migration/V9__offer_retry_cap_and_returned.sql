-- ----------------------------------------------------------------------------
-- Bounded delivery retries, and a terminal state for "they came back first".
--
-- Until now every FAILED offer was retried by every nightly scan, forever. For
-- a transient fault that is right. For a dead address it means mailing the same
-- bouncing mailbox every night, and repeated bounces are exactly what teaches a
-- mail provider to distrust the sending domain.
--
-- send_attempts counts delivery attempts actually made. The cap and the list of
-- failure codes that are never retried live in code (OfferRetryPolicy), not
-- here, so tuning them is not a migration.
--
-- SUPPRESSED_RETURNED: the customer came back (their flag resolved) while the
-- offer was still unsent or waiting on a retry. A "we've missed you" email to
-- someone who walked in yesterday is worse than none, so it is closed rather
-- than left PENDING. Like SUPPRESSED_BUDGET it carries no failure_code, because
-- nothing failed, and it is excluded from the offer budget for the same reason
-- NO_CONTACT is: it will never reach anyone.
-- ----------------------------------------------------------------------------
ALTER TABLE offers
    ADD COLUMN send_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (send_attempts >= 0);

-- An offer that is already SENT or FAILED has been attempted at least once.
UPDATE offers SET send_attempts = 1 WHERE status IN ('SENT', 'FAILED');

ALTER TABLE offers DROP CONSTRAINT offers_status_check;
ALTER TABLE offers ADD CONSTRAINT offers_status_check
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'NO_CONTACT',
                      'SUPPRESSED_BUDGET', 'SUPPRESSED_RETURNED'));

ALTER TABLE offers DROP CONSTRAINT chk_offer_failure_code_by_status;
ALTER TABLE offers ADD CONSTRAINT chk_offer_failure_code_by_status CHECK (
    (status IN ('PENDING', 'SENT', 'SUPPRESSED_BUDGET', 'SUPPRESSED_RETURNED')
        AND failure_code IS NULL) OR
    (status = 'FAILED'     AND failure_code IS NOT NULL) OR
    (status = 'NO_CONTACT' AND failure_code = 'MISSING_CONTACT_DETAILS')
);
