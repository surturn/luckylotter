# Technical PRD — AI Retention Layer, Phase 1 (MVP)
**Invonics Technologies** · Prepared by Sydney Kamau Kings · July 2026

---

## 1. Title & Summary

A pilot-ready system that ingests POS transaction data from a partner business, learns each customer's normal visit cadence, and automatically triggers a personalized win-back deal when a regular customer's pattern breaks — delivered through a business-facing Angular admin dashboard backed by a Spring Boot REST API and a Postgres database running in Docker. Phase 1 excludes the gamification layer (games, challenges, surprise reveals), which is scoped for Phase 2.

## 2. Problem Statement

Traditional loyalty programs are redemption-passive: a business has no signal that a specific regular customer has gone quiet until revenue has already been lost. There is currently no automated mechanism that (a) learns an individual customer's visit rhythm from existing transaction data, and (b) intervenes automatically the moment that rhythm breaks. Phase 1 exists to prove this trigger-and-deal mechanism works end-to-end on real POS data before any gamification is layered on top.

## 3. Goals

- G1: Automatically detect when a customer's visit cadence breaks and flag them as at-risk.
- G2: Automatically generate and log a personalized win-back offer for each flagged customer.
- G3: Give a business admin a simple dashboard to configure deal parameters and see who's been flagged and contacted.
- G4: Prove the system can run against real or realistic POS transaction data with no changes to the business's existing POS.
- G5: Ship a stack simple enough for a small team to build, run, and demo on zero infrastructure budget.

## 4. Non-Goals / Out of Scope (Phase 1)

- Games, streaks, challenges, or surprise-reveal mechanics — **Phase 2**.
- Native mobile app for business admins — web dashboard only.
- Direct, certified integrations with specific POS vendors — Phase 1 ingests data via a generic webhook/CSV import (see §12, Open Questions).
- Multi-language / localization beyond English.
- Payment processing of any kind (deals are discount codes, not transactions).
- Customer-facing self-serve app — customers only ever receive an outbound message (SMS/email), they don't log into anything in Phase 1.

## 5. User Personas & Stories

**Business Admin** (franchise/branch manager or owner)
- As a business admin, I want to tune how sensitive the "at risk" trigger is for my business, so it matches how often my customers actually visit — without me having to guess a single day count that fits a daily regular and a monthly one equally badly.
- As a business admin, I want to see a list of customers who've been flagged and what offer was sent to them, so I can track whether the system is working.
- As a business admin, I want to configure the default deal type and value (e.g. 30% off), so offers match my margins.

**End Customer** (indirect actor, receives output only)
- As a regular customer, I want to receive a relevant offer if I haven't visited in a while, so I have a reason to come back — without having to opt into anything extra.

**System (background)**
- As the retention engine, I need to reliably recompute visit patterns and flag breaks on a schedule, even if a previous run failed partway, so no customer is missed or double-flagged.

## 6. System Architecture

| Layer | Choice | Notes |
|---|---|---|
| Frontend | **Angular** (standalone components, Angular Router, RxJS) | Admin dashboard only — auth-gated |
| Backend | **Spring Boot** (Java, REST API) | Layered: Controller → Service → Repository. No business logic in controllers. |
| Database | **PostgreSQL**, containerized via **Docker Compose** | Single instance for Phase 1 (no read replicas needed at pilot scale) |
| Scheduling | Spring `@Scheduled` batch job | Runs the cadence/trigger engine daily; no separate queue/broker needed at this scale |
| Auth | JWT (Spring Security) | Admin-only login; no customer-facing auth in Phase 1 |
| Notification delivery | Pluggable `NotificationSender` interface | Start with a logging/email stub; SMS gateway (e.g. Africa's Talking) wired in behind the same interface once a pilot has budget for it |
| Deployment target | Docker Compose (frontend, backend, Postgres) on a single VPS via Coolify | Matches existing Invonics deployment pattern |

**Backend layering (enforced):**
```
Controller (REST, DTO validation only)
   ↓
Service (business logic: cadence calc, trigger evaluation, offer generation)
   ↓
Repository (Spring Data JPA — no raw SQL string-building)
```
Domain services never depend on `@RestController` classes. DTOs are explicit — JPA entities are never returned directly from an endpoint.

**Trigger job idempotency:** the daily batch job is safe to re-run. Each customer has at most one *open* flag at a time (enforced via a unique partial constraint on `customer_id` where `status = 'ACTIVE'`), so a retried or overlapping run cannot double-flag or double-send an offer.

## 7. Functional Requirements

| ID | Requirement |
|---|---|
| FR-1 | The system must accept POS transaction records via a REST ingestion endpoint (`POST /v1/transactions`), containing at minimum: business ID, customer identifier, transaction timestamp, amount — plus optional `contactEmail` / `contactPhone`, which fill in a customer's missing contact details on any later transaction. |
| FR-2 | The system must compute each customer's average visit interval from their transaction history, updated as new transactions arrive. The unit is the **visit**, not the transaction row: transactions less than `MIN_VISIT_GAP = 6h` apart are one trip to the counter rung up more than once (split bill, items paid for separately, a till that exports line items) and are collapsed before the interval is measured. Without that, extra rows shorten the learned cadence without lengthening the history they span, dragging the customer's threshold down toward `min_threshold_days` — which happens by accident on ordinary POS data and is the cheapest way to game the trigger deliberately. A cadence is only computed once the customer has at least `MIN_TRANSACTIONS = 3` **visits** (a fixed system constant, not per-business config); below that the customer has no cadence and is not flaggable. |
| FR-3 | The system must run a scheduled job (default: daily) that flags any customer whose time-since-last-visit exceeds **that customer's own** threshold, derived from their learned cadence: `days_since_last_visit > clamp(avg_interval_days × sensitivity_multiplier, min_threshold_days, max_threshold_days)`. There is no flat per-business inactivity threshold. |
| FR-4 | The system must generate a personalized win-back offer (deal type + value, pulled from the business's configuration) for each newly flagged customer. Deal value is static per business in Phase 1 — it does not escalate with inactivity duration. |
| FR-5 | The system must record every generated offer with its delivery status (`PENDING`, `SENT`, `FAILED`, `NO_CONTACT`) — sending is delegated to a pluggable notification sender. A customer with neither `contact_email` nor `contact_phone` still gets an offer generated and logged, marked `NO_CONTACT` (terminal, never retried) rather than left indefinitely `PENDING`, so the contactability gap is visible. |
| FR-6 | A business admin must be able to log in and configure, per business: `sensitivity_multiplier` (default 1.5), `min_threshold_days` (default 3), `max_threshold_days` (default 60), default deal type, and default deal value. `MIN_TRANSACTIONS` is exposed read-only. The service layer enforces `min_threshold_days <= max_threshold_days`. |
| FR-7 | A business admin must be able to view a list of flagged customers, their last visit date, and the offer sent to them. |
| FR-8 | The system must not re-flag a customer who already has an active, unresolved flag/offer. |
| FR-9 | A flag is auto-resolved (status → `RESOLVED`) the next time that customer's transaction is ingested. |

## 8. Non-Functional Requirements

- **NFR-1 (Security):** All admin endpoints require a valid JWT; authorization checks scope every request to the admin's own business ID at the service layer, not just the route.
- **NFR-2 (Data integrity):** Business ID and customer identifier are enforced via foreign keys and `NOT NULL` constraints; the "one active flag per customer" rule is enforced at the DB level, not just in application code.
- **NFR-3 (Idempotency):** The transaction ingestion endpoint accepts an `Idempotency-Key` (or a natural POS transaction ID) to prevent duplicate transactions from double-counting a visit.
- **NFR-4 (Observability):** All requests and the scheduled job emit structured (JSON) logs with a correlation/request ID; no PII (customer names, phone numbers) is written to logs — only internal IDs.
- **NFR-5 (Performance):** Cadence recomputation must handle at least 10,000 customers per business within the nightly batch window without table scans — indexed on `(business_id, customer_id, transaction_timestamp)`.
- **NFR-6 (Config):** All environment-specific values (DB credentials, JWT secret, notification provider keys) come from environment variables — never hardcoded.

## 9. Data Model (Postgres — Phase 1)

Authoritative definition lives in `backend/src/main/resources/db/migration/V1__init.sql`;
this is the summary view.

```
businesses       (id, name, sensitivity_multiplier, min_threshold_days, max_threshold_days,
                  default_deal_type, default_deal_value, created_at, updated_at)
admin_users      (id, business_id FK, email UNIQUE, password_hash, active, created_at, updated_at)
customers        (id, business_id FK, external_ref, contact_email?, contact_phone?,
                  transaction_count, avg_interval_days?, first_seen_at, last_visit_at?,
                  created_at, updated_at)
transactions     (id, business_id FK, customer_id FK, external_txn_id, amount, occurred_at, created_at)
retention_flags  (id, business_id FK, customer_id FK, status[ACTIVE|RESOLVED],
                  threshold_days_applied, avg_interval_days_at_flag, flagged_at, resolved_at?)
offers           (id, business_id FK, flag_id FK UNIQUE, deal_type, deal_value,
                  status[PENDING|SENT|FAILED|NO_CONTACT], failure_code?, sent_at?,
                  created_at, updated_at)
```
`?` marks a nullable column.

Constraints that carry design decisions rather than mere hygiene:

- `UNIQUE (customer_id) WHERE status = 'ACTIVE'` on `retention_flags` — one open flag per customer, enforced in the DB, not in application code (FR-8, NFR-2).
- `UNIQUE (business_id, external_txn_id)` on `transactions` — POS transaction IDs are only unique within their own POS, so they can collide across tenants; a bare `UNIQUE` would let one business's ingestion silently reject another's row (NFR-3).
- `UNIQUE (business_id, external_ref)` on `customers` — same reasoning for POS customer refs.
- `customers.avg_interval_days` is `NULL` until the customer has `MIN_TRANSACTIONS` distinct visits; a `NULL` cadence means "not flaggable" (FR-2). Note this is *not* `transaction_count >= MIN_TRANSACTIONS`: rows over-count visits, so a customer whose only trip was rung up three times has three transactions and still no cadence.
- `customers.avg_interval_days > 0` (`CHECK`) — upheld by the collapse rule rather than by luck: two counted visits are always at least `MIN_VISIT_GAP` apart, so the average can never round to zero. Before collapsing, three receipts minutes apart produced `0.00` and the ingest that wrote it failed on this constraint (FR-2).
- `offers.failure_code` is a **bounded code set** (`CHECK` constraint + `OfferFailureCode` enum), never a provider error string. A freeform message is how a phone number ends up in a column that was never meant to carry PII — this extends NFR-4's no-PII rule from logs to stored columns. The provider's own message goes to the correlated log line only.
- `retention_flags.threshold_days_applied` / `avg_interval_days_at_flag` snapshot the numbers that fired the flag, so it stays explainable after the business retunes its sensitivity (§11 precision audit).
- `offers.deal_type` / `deal_value` are likewise snapshotted from business config at generation time, so retuning config doesn't rewrite offer history.

## 10. API Surface (Spring Boot, `/v1`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/auth/login` | Admin login, returns JWT |
| POST | `/v1/transactions` | POS ingests a transaction (idempotent) |
| GET | `/v1/businesses/me/config` | Get current business's deal config |
| PUT | `/v1/businesses/me/config` | Update sensitivity multiplier / clamps / deal defaults |
| GET | `/v1/flags` | List flagged customers + offer status (paginated) |
| GET | `/v1/flags/{id}` | Flag detail |

## 11. Success Metrics

- % of at-risk customers correctly flagged vs. a manually-audited sample (target: ≥90% precision in pilot).
- Time from cadence break to offer generated (target: within one scheduled run cycle, i.e. ≤24h).
- Admin dashboard load time for flag list (target: <1.5s at 10k customers, paginated).
- Zero duplicate flags/offers per customer during pilot (validates idempotency design).

## 12. Open Questions / Risks

### Still open

- **POS data source:** real integration will vary per POS vendor. Phase 1 assumes a generic webhook/CSV import — needs confirmation of which pilot business's POS (or POS export format) will be used for the demo. Blocks the CSV importer's column mapping (M4).
- **Multi-tenancy:** schema is multi-tenant (`business_id` on every table) regardless; the open question is only whether *concurrent* multi-business pilots need testing in Phase 1, or can wait.
- **Offer expiry:** `offers` has no expiry column and no FR covers deal validity period. Intentional for Phase 1, or an omission?
- **Cadence definition:** "average visit interval" is implemented as the mean gap across *all* visits. A customer whose rhythm changed (weekly for a year, then monthly for three months) has a misleading mean. A trailing window (say, the last 10 visits) would track better. Revisit if the §11 precision audit comes in under 90%.
- **Multiplier legibility:** `sensitivity_multiplier = 1.5` is precise but not something a café owner will reason about. The config UI may need to present coarse choices ("sensitive / balanced / relaxed") mapping to multipliers underneath. UX task, not a blocker.

### Resolved (2026-07-28)

- ~~**Threshold semantics.**~~ Per-customer, not a flat per-business day count — see FR-3. `inactivity_threshold_days` is gone from the schema.
- ~~**Cadence minimum sample.**~~ `MIN_TRANSACTIONS = 3`, a fixed constant rather than per-business config.
- ~~**Customer contact details.**~~ Nullable `contact_email` / `contact_phone` on `customers`, optional on ingest, with a `NO_CONTACT` offer status making the gap visible — see FR-5.
- ~~**Notification channel & cost.**~~ Phase 1 ships the logging stub only. SMS (e.g. Africa's Talking) has a per-message cost and is a post-pilot upgrade behind the same `NotificationSender` interface.
- ~~**Deal value logic.**~~ Static default per business; no escalation by inactivity duration in Phase 1.

## 13. Timeline / Milestones (proposed)

| Milestone | Scope |
|---|---|
| M0 — Scaffold | Docker Compose (Postgres + Spring Boot + Angular), auth, business config CRUD |
| M1 — Ingestion | Transaction ingestion endpoint, cadence calculation, DB constraints |
| M2 — Trigger engine | Scheduled job, flag creation, offer generation (stubbed delivery) |
| M3 — Admin dashboard | Angular views: config, flagged customer list, flag detail |
| M4 — Pilot-ready | Seed/import script for a real or sample POS dataset, demo walkthrough |
