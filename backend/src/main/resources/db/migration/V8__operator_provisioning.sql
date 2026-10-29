-- ----------------------------------------------------------------------------
-- Operator-run provisioning and password lifecycle.
--
-- The product is run as a service: the operator creates each business and its
-- first admin, hands over a temporary password, and the admin must replace it
-- before doing anything else.
--
-- password_changed_at is what makes a change or reset actually end old
-- sessions. JWTs are stateless, so the only way to refuse a token issued
-- before a password changed is to compare its issue time with this column.
-- Existing rows default to now(), which leaves already-issued tokens valid
-- until they expire on their own.
-- ----------------------------------------------------------------------------
ALTER TABLE admin_users
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN password_changed_at  TIMESTAMPTZ NOT NULL DEFAULT now();

-- Where a customer's reply to an offer email goes. Offers are sent from one
-- authenticated domain the operator controls, with the business's name as the
-- display name; replies should reach the business, not the operator. Nullable:
-- a business without one simply sends with no Reply-To.
ALTER TABLE businesses
    ADD COLUMN reply_to_email VARCHAR(255);
