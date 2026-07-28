-- Phase 1 (MVP) schema — AI Retention Layer.
--
-- Replaces the previous gamification schema (brands / rewards / spin_tokens /
-- spins / vouchers), which is deferred to Phase 2 per PRD §4.
--
-- Encodes the resolved trigger semantics: there is no flat per-business
-- inactivity threshold. Each customer's threshold is derived from their own
-- learned cadence:
--
--   flag when days_since_last_visit
--            > clamp(avg_interval_days * sensitivity_multiplier,
--                    min_threshold_days, max_threshold_days)
--
-- and a customer is only flaggable once they have MIN_TRANSACTIONS (3)
-- transactions, so avg_interval_days is meaningful.

-- gen_random_uuid() is built into Postgres 13+ core.

-- ----------------------------------------------------------------------------
-- Tenants (§9). sensitivity_multiplier + clamps replace the flat
-- inactivity_threshold_days from the PRD draft (FR-3, FR-6).
-- ----------------------------------------------------------------------------
CREATE TABLE businesses (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(160)  NOT NULL,
    sensitivity_multiplier  NUMERIC(4,2)  NOT NULL DEFAULT 1.5,
    min_threshold_days      INTEGER       NOT NULL DEFAULT 3,
    max_threshold_days      INTEGER       NOT NULL DEFAULT 60,
    default_deal_type       VARCHAR(24)   NOT NULL DEFAULT 'PERCENT_OFF'
                            CHECK (default_deal_type IN ('PERCENT_OFF', 'FIXED_AMOUNT_OFF', 'FREE_ITEM')),
    default_deal_value      NUMERIC(12,2) NOT NULL CHECK (default_deal_value > 0),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_business_multiplier_positive CHECK (sensitivity_multiplier > 0),
    CONSTRAINT chk_business_threshold_clamp_order CHECK (min_threshold_days >= 1
                                                    AND min_threshold_days <= max_threshold_days)
);

-- ----------------------------------------------------------------------------
-- Admin accounts (FR-6, §9). Every admin belongs to exactly one business —
-- the JWT carries this business_id and it is the only tenant scope the
-- service layer trusts (NFR-1).
-- ----------------------------------------------------------------------------
CREATE TABLE admin_users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id    UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_users_business ON admin_users(business_id);

-- ----------------------------------------------------------------------------
-- Customers (FR-1, FR-2, §9).
--
-- contact_email / contact_phone are both nullable: POS systems vary in what
-- they expose, and a customer with neither is still tracked and still flagged —
-- their offer is marked NO_CONTACT so the coverage gap is visible rather than
-- silently swallowed (FR-4, FR-5).
--
-- transaction_count and avg_interval_days are denormalized cadence state,
-- recomputed on each ingested transaction (FR-2). avg_interval_days stays NULL
-- until transaction_count >= 3, and a NULL cadence means "not flaggable".
-- ----------------------------------------------------------------------------
CREATE TABLE customers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    external_ref       VARCHAR(160) NOT NULL,
    contact_email      VARCHAR(255),
    contact_phone      VARCHAR(32),
    transaction_count  INTEGER      NOT NULL DEFAULT 0 CHECK (transaction_count >= 0),
    avg_interval_days  NUMERIC(8,2) CHECK (avg_interval_days IS NULL OR avg_interval_days > 0),
    first_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_visit_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- a POS customer ref is only unique within its own business (multi-tenant)
    CONSTRAINT uq_customers_business_ref UNIQUE (business_id, external_ref)
);
-- drives the nightly at-risk scan (NFR-5)
CREATE INDEX idx_customers_business_last_visit ON customers(business_id, last_visit_at);

-- ----------------------------------------------------------------------------
-- Transactions (FR-1, NFR-3, §9). external_txn_id is the natural POS ID and
-- the idempotency anchor: replaying a transaction is a no-op, not a second
-- visit. Unique per business, since POS IDs can collide across tenants.
-- ----------------------------------------------------------------------------
CREATE TABLE transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id      UUID          NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_id      UUID          NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    external_txn_id  VARCHAR(160)  NOT NULL,
    amount           NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    occurred_at      TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_business_external UNIQUE (business_id, external_txn_id)
);
-- cadence recomputation must not table-scan at 10k customers/business (NFR-5)
CREATE INDEX idx_transactions_business_customer_occurred
    ON transactions(business_id, customer_id, occurred_at);

-- ----------------------------------------------------------------------------
-- Retention flags (FR-3, FR-8, FR-9, §9). At most one ACTIVE flag per
-- customer, enforced in the DB so a retried or overlapping batch run cannot
-- double-flag (NFR-2, NFR-3).
--
-- threshold_days_applied records the clamped threshold that fired this flag,
-- so a flag stays explainable after the business later retunes its multiplier.
-- ----------------------------------------------------------------------------
CREATE TABLE retention_flags (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id             UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_id             UUID         NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'RESOLVED')),
    threshold_days_applied  NUMERIC(8,2) NOT NULL CHECK (threshold_days_applied > 0),
    avg_interval_days_at_flag NUMERIC(8,2) NOT NULL CHECK (avg_interval_days_at_flag > 0),
    flagged_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    CONSTRAINT chk_flag_resolved_at CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL) OR
        (status = 'ACTIVE'   AND resolved_at IS NULL)
    )
);
-- FR-8: one open flag per customer, at the DB level not just in app code
CREATE UNIQUE INDEX uq_retention_flags_one_active_per_customer
    ON retention_flags(customer_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_retention_flags_business_flagged ON retention_flags(business_id, flagged_at DESC);

-- ----------------------------------------------------------------------------
-- Offers (FR-4, FR-5, §9). Deal type/value are snapshotted from the business
-- config at generation time so retuning the config later doesn't rewrite
-- history.
--
-- NO_CONTACT is a terminal status for a customer with neither contact_email
-- nor contact_phone: the offer is still generated and logged, but is never
-- eligible for sending. It is deliberately distinct from PENDING so the
-- contactability gap shows up in the dashboard.
-- ----------------------------------------------------------------------------
CREATE TABLE offers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id  UUID          NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    flag_id      UUID          NOT NULL UNIQUE REFERENCES retention_flags(id) ON DELETE CASCADE,
    deal_type    VARCHAR(24)   NOT NULL
                 CHECK (deal_type IN ('PERCENT_OFF', 'FIXED_AMOUNT_OFF', 'FREE_ITEM')),
    deal_value   NUMERIC(12,2) NOT NULL CHECK (deal_value > 0),
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'NO_CONTACT')),
    -- A bounded code set, never a provider error string: a freeform message is
    -- how a phone number or customer name ends up in a column that was never
    -- meant to carry PII (NFR-4, extended from logs to stored fields).
    failure_code VARCHAR(40)
                 CHECK (failure_code IS NULL OR failure_code IN (
                     'MISSING_CONTACT_DETAILS', 'INVALID_EMAIL_ADDRESS', 'INVALID_PHONE_NUMBER',
                     'SENDER_TIMEOUT', 'SENDER_UNAVAILABLE', 'SENDER_REJECTED', 'UNKNOWN_ERROR')),
    sent_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_offer_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    -- keeps status and failure_code from drifting out of agreement
    CONSTRAINT chk_offer_failure_code_by_status CHECK (
        (status IN ('PENDING', 'SENT') AND failure_code IS NULL) OR
        (status = 'FAILED'     AND failure_code IS NOT NULL) OR
        (status = 'NO_CONTACT' AND failure_code = 'MISSING_CONTACT_DETAILS')
    )
);
CREATE INDEX idx_offers_business_status ON offers(business_id, status);
