# PRD Todos — AI Retention Layer, Phase 1 (MVP)

Traceable to `docs/lucklotter.md` (Technical PRD, Invonics Technologies, July 2026).
Every item is tagged with the FR/NFR ID it satisfies, or marked `(support)` for
PRD-adjacent work that isn't a requirement in its own right.

User story IDs are assigned here (the PRD lists stories unnumbered, §5):

| ID | Story |
|---|---|
| US-1 | Admin sets inactivity threshold (days) for their business |
| US-2 | Admin sees flagged customers and the offer sent to each |
| US-3 | Admin configures default deal type and value |
| US-4 | Customer receives a relevant offer after going quiet, with no opt-in |
| US-5 | Retention engine recomputes cadence and flags breaks on a schedule, safely re-runnable |

> **Scope reset — done (2026-07-28).** `backend/` had been scaffolded against the
> *previous* gamification PRD (`brands / app_users / campaigns / rewards /
> spin_tokens / spins / vouchers`), none of which appear in the revised Phase 1
> model (§9). Gamification is now Phase 2 (§4). The schema has been replaced and
> the Phase 1 domain + DTO layer built — see M0-0 and M0-4.

## Resolved PRD conflicts (2026-07-28)

Two conflicts in the PRD as written were resolved by decision; the todos below
reflect the resolution, and `docs/lucklotter.md` still carries the original
wording (§7 FR-3/FR-6, §9) and should be amended to match.

**1. Trigger is per-customer, not a flat per-business threshold (FR-2/FR-3/FR-6).**
`businesses.inactivity_threshold_days` is gone. Replaced by:

| Field | Default | Role |
|---|---|---|
| `sensitivity_multiplier` | 1.5 | multiplies the customer's own cadence |
| `min_threshold_days` | 3 | lower clamp |
| `max_threshold_days` | 60 | upper clamp |

Flag when `days_since_last_visit > clamp(avg_interval_days × sensitivity_multiplier, min_threshold_days, max_threshold_days)`.
`avg_interval_days` is only computed once a customer has `MIN_TRANSACTIONS = 3`
transactions (a fixed constant, not per-business config); below that the customer
is not flaggable — no exceptions in Phase 1.

**2. Customer contactability is explicit (FR-1/FR-4/FR-5).**
`customers.contact_email` and `customers.contact_phone` added, both nullable.
FR-1's ingestion payload gains both as optional fields. A fourth offer status
`NO_CONTACT` joins `PENDING/SENT/FAILED`: a customer with neither field still
gets an offer generated and logged, marked `NO_CONTACT` rather than `PENDING`, so
the coverage gap is visible instead of silently pending forever.

Also locked: static deal value per business (no escalation by inactivity
duration), logging-only sender for Phase 1 (no SMS gateway).

---

## M0 — Scaffold & foundation

### M0-0: Reset the scaffold to the revised Phase 1 scope — ✅ done
- [x] Decided: gamification schema **deleted**, not archived — it was SQL-only (no Java entities existed) and Phase 2 will re-derive it from the Phase 2 PRD. Recoverable from git history if needed (support)
- [x] Replaced `V1__init.sql` with the Phase 1 schema — no `spin_tokens` / `rewards` / `vouchers` tables (§9)
- [x] `brands` → `businesses`, `app_users` → `admin_users` per §9 naming (§9)
- [x] Dropped the local Postgres volume; migration verified applying clean from empty (support)
- [x] Removed the leftover `lucklotter.spin.*` config block from `application.yml`; retargeted `pom.xml` `<description>` (support)
- [ ] Amend `docs/lucklotter.md` §7/§9 to match the two resolved decisions above, so the PRD stops contradicting the schema (support)

### M0-1: Infrastructure
- [x] Docker Compose with Postgres 16 + backend service, healthcheck, named volume (§6)
- [x] Spring Boot project skeleton: web, data-jpa, security, validation, actuator, Flyway, JJWT, Testcontainers (§6)
- [ ] Add the Angular app as a third Compose service (frontend) (§6)
- [ ] Scaffold Angular workspace: standalone components, Router, RxJS, HTTP interceptor for JWT (§6)
- [ ] Move every env-specific value (DB creds, JWT secret, notification keys) to env vars with no hardcoded fallbacks in committed config (NFR-6)
- [ ] Replace the dev-only `JWT_SECRET` literal in `docker-compose.yml` with an `.env`-sourced value + committed `.env.example` (NFR-6)
- [ ] Write `README.md` with local run steps (`docker compose up`, seeded admin login) (support)

### M0-4: Domain & DTO layer — ✅ done
- [x] Enums: `DealType`, `FlagStatus`, `OfferStatus` (incl. `NO_CONTACT`), `OfferFailureCode` (FR-4, FR-5, FR-8)
- [x] `offers.failure_code` is a bounded code set (CHECK-constrained + enum), not a freeform message, so provider errors can't smuggle PII into the column (NFR-4)
- [x] `RetentionConstants.MIN_TRANSACTIONS = 3` as a fixed constant (FR-2)
- [x] Entities: `Business`, `AdminUser`, `Customer`, `PosTransaction`, `RetentionFlag`, `Offer` (§9)
- [x] `Business.thresholdDaysFor(avgIntervalDays)` — the clamped per-customer threshold (FR-3)
- [x] `Customer.hasEstablishedCadence()` / `isContactable()` as the flaggability and contactability predicates (FR-2, FR-5)
- [x] Repositories for all six entities, incl. `findFlagCandidates` and the fetch-joined dashboard query (FR-3, FR-7, NFR-5)
- [x] DTOs: login, business config get/update, transaction ingest req/resp, flag summary/detail (§10)
- [x] Verified: Flyway applies clean, Hibernate `ddl-auto: validate` passes, all `@Query` JPQL parses at boot
- [x] Verified by hand in psql: the partial unique index rejects a second `ACTIVE` flag, and resolving one frees the slot (FR-8)
- [ ] Add `getOffer()` traversal coverage — the `RetentionFlag.offer` inverse mapping is only exercised by the dashboard query so far (support)

### M0-2: Layering guardrails
- [ ] Create package structure enforcing Controller → Service → Repository (§6)
- [ ] Define explicit request/response DTOs; assert no JPA entity is returned from any controller (§6)
- [ ] Add an ArchUnit (or equivalent) test: services must not reference `@RestController` classes (§6)

### M0-3: Structured logging & correlation IDs
- [ ] Configure JSON log encoder for app + scheduled job output (NFR-4)
- [ ] Add a servlet filter that assigns/propagates a correlation ID into MDC per request (NFR-4)
- [ ] Propagate a run ID through the scheduled job's log lines (NFR-4)
- [ ] Audit log statements so no customer name, email, or phone is ever logged — internal IDs only (NFR-4)
- [ ] Test asserting a PII-bearing field never reaches the log output (NFR-4)

---

## US-1 / US-3: Admin login and business config

### Auth
- [x] `businesses` and `admin_users` tables with FKs and `NOT NULL` constraints (NFR-2, §9)
- [x] `AdminUser` entity + repository; `BCryptPasswordEncoder` bean already wired in `SecurityConfig` (FR-6)
- [ ] Password hashing actually applied on user creation + a seed/bootstrap admin (FR-6)
- [ ] `POST /v1/auth/login` — validate credentials, return JWT carrying `business_id` + user ID (FR-6, §10)
- [ ] JWT auth filter + `SecurityConfig` locking down every route except login and `/actuator/health` (NFR-1)
- [ ] Service-layer tenant guard: resolve `business_id` from the authenticated principal, never from a request param (NFR-1)
- [ ] Tests: expired token, missing token, and token for business A cannot read business B's data (NFR-1)

### Business config
- [x] Config DTOs: `BusinessConfigResponse` / `BusinessConfigUpdateRequest` with bounds on multiplier and clamps (FR-6)
- [ ] `GET /v1/businesses/me/config` — returns multiplier, min/max clamps, default deal type + value, and read-only `minTransactions` (FR-6, §10)
- [ ] `PUT /v1/businesses/me/config` — validated update; enforce the cross-field rule `minThresholdDays <= maxThresholdDays` in the service (FR-6, §10)
- [ ] Angular config screen: form bound to the config endpoints, with validation + save feedback (FR-6, US-1, US-3)
- [ ] Config screen must explain the multiplier in plain language (e.g. "flag at 1.5× a customer's usual gap") — a raw decimal is not admin-legible (US-1, US-3)
- [ ] Tests: config update is scoped to the caller's business only (FR-6, NFR-1)

---

## US-5: Ingestion and cadence calculation

### M1-1: Transaction ingestion
- [x] `customers` and `transactions` tables; FKs, `NOT NULL` on business/customer refs, `contact_email`/`contact_phone` nullable (NFR-2, §9)
- [x] `external_txn_id` unique **per business** — POS IDs can collide across tenants, so the PRD's bare `UNIQUE` was widened to `(business_id, external_txn_id)` (NFR-3, §9)
- [x] Composite index on `(business_id, customer_id, occurred_at)` (NFR-5)
- [x] `TransactionIngestRequest` with optional `contactEmail`/`contactPhone` (FR-1)
- [ ] `POST /v1/transactions` — controller + service, DTO validation for business ID, customer ref, timestamp, amount (FR-1, §10)
- [ ] Upsert-by-`external_ref` customer resolution: create the customer on first sighting (FR-1)
- [ ] Update contact fields on ingest when the payload supplies them — a later transaction should be able to fill a previously-missing email/phone (FR-1)
- [ ] Idempotency: accept `Idempotency-Key` header or natural POS txn ID; duplicate submission is a no-op, not a second visit (NFR-3)
- [ ] Test: replaying the same transaction twice leaves one row and does not shift the cadence (NFR-3)

### M1-2: Cadence calculation
- [x] Minimum transaction count fixed at `MIN_TRANSACTIONS = 3` and encoded as a constant (FR-2)
- [x] `customers.transaction_count` + `avg_interval_days` columns; null cadence = not flaggable (FR-2)
- [ ] Compute average visit interval per customer from transaction history (FR-2)
- [ ] Update `last_visit_at`, `transaction_count`, and `avg_interval_days` on each ingested transaction (FR-2)
- [ ] Handle out-of-order ingestion: a backdated transaction must not push `last_visit_at` backwards (FR-2)
- [ ] Performance test: cadence recompute across 10,000 customers stays inside the batch window with no seq scans — verify via `EXPLAIN` (NFR-5)

### M1-3: Flag auto-resolution
- [ ] On ingestion, flip that customer's `ACTIVE` flag to `RESOLVED` with `resolved_at` set (FR-9)
- [ ] Test: ingesting a transaction for a flagged customer resolves exactly that one flag (FR-9)

---

## US-4 / US-5: Trigger engine and offer generation

### M2-1: Scheduled trigger job
- [x] `retention_flags` table with `status[ACTIVE|RESOLVED]`, `flagged_at`, `resolved_at` (§9)
- [x] Partial unique index `UNIQUE (customer_id) WHERE status = 'ACTIVE'` — verified rejecting a second active flag (FR-8, NFR-2, §9)
- [x] `threshold_days_applied` + `avg_interval_days_at_flag` snapshot columns, so a flag stays explainable after the business retunes (FR-3, §11 precision audit)
- [x] `CustomerRepository.findFlagCandidates` — index-friendly pre-filter excluding customers below `MIN_TRANSACTIONS` or already flagged (FR-3, FR-8, NFR-5)
- [ ] Spring `@Scheduled` daily job (cron in `lucklotter.retention.scan-cron`) driving the scan (FR-3)
- [ ] Apply the clamped per-customer threshold via `Business.thresholdDaysFor(...)` — the coarse SQL pre-filter is not the decision (FR-3, FR-6)
- [ ] Create an `ACTIVE` flag per newly-detected customer, handling the unique-constraint violation as "already flagged, skip" (FR-8)
- [ ] Test: two concurrent/overlapping job runs produce exactly one flag and one offer per customer (FR-8, NFR-3)
- [ ] Test: a customer with an existing `ACTIVE` flag is not re-flagged on the next run (FR-8)
- [ ] Job run summary log line: customers scanned, flagged, skipped, errored (NFR-4)

### M2-2: Offer generation
- [x] `offers` table: unique `flag_id` FK, `deal_type`, `deal_value`, `status[PENDING|SENT|FAILED|NO_CONTACT]`, `failure_reason`, `sent_at` (§9)
- [x] `Offer.markSent/markFailed/markNoContact` status transitions (FR-5)
- [ ] Generate one offer per newly flagged customer, snapshotting the business's default deal type + value (FR-4)
- [ ] Route on contactability: `PENDING` if the customer has an email or phone, else `NO_CONTACT` — never silently pending (FR-5)
- [ ] Test: a customer with neither contact field gets an offer at `NO_CONTACT`, and the stub sender never picks it up (FR-5)
- [ ] Test: flag creation and offer creation commit in the same transaction — no flag without an offer (FR-4, FR-5)

### M2-3: Notification delivery
- [ ] Define the `NotificationSender` interface (§6)
- [ ] Implement the logging/stub sender as the Phase 1 default (§6, §12)
- [ ] Transition offer status `PENDING` → `SENT` on success, → `FAILED` on exception, recording `sent_at` (FR-5)
- [ ] Sender queries `PENDING`/`FAILED` only — `NO_CONTACT` is terminal and must never be retried (FR-5)
- [ ] Senders map provider errors onto the `OfferFailureCode` enum; the provider's own message goes to the correlated log, never into the column (NFR-4)
- [ ] Test: a sender that throws with a phone number in its exception message stores only a code — no PII reaches `offers.failure_code` (NFR-4)
- [ ] Ensure a send failure does not roll back the flag/offer records (FR-5)
- [ ] Test with a deliberately failing sender: offer lands at `FAILED`, flag stays `ACTIVE` (FR-5)

---

## US-2: Admin dashboard

### M3-1: Flag list API
- [x] `FlagSummaryResponse` / `FlagDetailResponse` DTOs, incl. the cadence evidence behind each trigger (FR-7, §11)
- [x] Fetch-joined `findForDashboard` / `findForDashboardByStatus` queries to avoid N+1 on customer + offer (FR-7, NFR-5)
- [ ] `GET /v1/flags` — paginated list, optional `status` filter (FR-7, §10)
- [ ] `GET /v1/flags/{id}` — flag detail with its offer (FR-7, §10)
- [ ] Scope both endpoints to the caller's business at the service layer (NFR-1)
- [ ] Index/query tuning so the paginated list returns in <1.5s at 10k customers (NFR-5, §11)

### M3-2: Angular views
- [ ] Login screen + JWT storage and auth guard on routes (NFR-1)
- [ ] Flagged-customer list view: paginated table with last visit date and offer status (FR-7, US-2)
- [ ] Surface `NO_CONTACT` distinctly in the list (not as a generic failure) — exposing the contactability gap is the whole point of the status (FR-5, US-2)
- [ ] Show a count of un-contactable flagged customers so the admin can see how much of their POS data lacks contact details (FR-5)
- [ ] Flag detail view (FR-7)
- [ ] Dashboard shell: nav between config and flag list, logout (support)
- [ ] Loading/empty/error states on both data views (support)

---

## M4 — Pilot readiness

### 🚧 Hard gates — M4 cannot be declared done while any of these is open

- [ ] **`SecurityConfig` still ends in `anyRequest().permitAll()`** behind a `TODO(FR-4)` comment. Every endpoint is currently public. This must become `authenticated()` with the JWT filter in place before any pilot data exists (NFR-1). A code comment is not a tracking mechanism — this line is the gate.
- [ ] **`JWT_SECRET` dev literal** in `docker-compose.yml` must be replaced by an env-sourced value; a pilot deployment must not boot with the committed default (NFR-6)
- [ ] **No-PII log audit** completed against real ingestion payloads, which are the first thing to actually carry emails and phone numbers (NFR-4)

### Pilot tasks

- [ ] CSV import path for a POS transaction export, reusing the ingestion service (FR-1, §12)
- [ ] Seed script: sample business, admin user, and a realistic transaction history that produces flags on first job run (G4, §13)
- [ ] Manual-run trigger for the batch job (admin endpoint or CLI) so a demo doesn't wait for the daily schedule (support)
- [ ] Precision check: audit a sample of flags against a manual review, record the % correctly flagged (§11, target ≥90%)
- [ ] Measure and record cadence-break → offer-generated latency (§11, target ≤24h)
- [ ] Confirm zero duplicate flags/offers over a full pilot period (§11)
- [ ] Demo walkthrough script (§13)
- [ ] Coolify deployment of the Compose stack to a single VPS (§6)

---

## Coverage check

| ID | Covered? | Where |
|---|---|---|
| FR-1 | ✅ | M1-1, M4 (CSV import) |
| FR-2 | ✅ | M1-2 |
| FR-3 | ✅ | M2-1 |
| FR-4 | ✅ | M2-2 |
| FR-5 | ✅ | M2-2, M2-3 |
| FR-6 | ✅ | US-1/US-3 (auth + business config) |
| FR-7 | ✅ | M3-1, M3-2 |
| FR-8 | ✅ | M2-1 |
| FR-9 | ✅ | M1-3 |
| NFR-1 (security) | ✅ | Auth, business config, M3-1 |
| NFR-2 (data integrity) | ✅ | US-1 tables, M1-1, M2-1 |
| NFR-3 (idempotency) | ✅ | M1-1, M2-1 |
| NFR-4 (observability) | ✅ | M0-3, M2-1 |
| NFR-5 (performance) | ✅ | M1-1, M1-2, M3-1 |
| NFR-6 (config) | ✅ | M0-1 |
| US-1 | ✅ | Business config |
| US-2 | ✅ | M3-1, M3-2 |
| US-3 | ✅ | Business config |
| US-4 | ✅ | M2-2, M2-3 |
| US-5 | ✅ | M1-2, M2-1 |
| G1–G5 (goals) | ✅ | Emergent from the above; G4/G5 verified at M4 |
| §11 success metrics | ✅ | M4 |

Nothing in the PRD is currently without a todo.

**Items added after the list was first written (2026-07-28)**, from the two
resolved conflicts rather than from new PRD text: the contactability routing and
`NO_CONTACT` items under M2-2/M2-3/M3-2, the clamp/multiplier items under
business config and M2-1, the out-of-order-ingestion and contact-update items
under M1-1/M1-2, and the M0-0 task to amend the PRD itself. The list has evolved;
it was not complete on day one.

---

## Unclear / needs clarification

Carried from PRD §12 plus items surfaced while deriving tasks. None are blocking
M0, but the starred ones block the tasks named.

- **POS data source (§12)** — which pilot business's POS or export format? Blocks the CSV importer's column mapping in M4.
- **Notification channel (§12)** — confirmed that Phase 1 ships the logging/email stub only? SMS (Africa's Talking) is treated here as post-pilot.
- **Multi-tenancy (§12)** — schema is multi-tenant regardless; the open question is only whether concurrent multi-business pilots need testing in Phase 1.
- **Deal value logic (§12)** — static default per business, or escalating by inactivity duration? Affects M2-2. Assumed static for now.
- **Offer expiry** — the `offers` table has no expiry column and no FR covers offer/deal validity period. Intentional for Phase 1?
- **Flag list date filtering** — status filtering is now in scope (see M3-1); a `flagged_at` date-range filter is still unspecified in §10.
- **Cadence definition** — "average visit interval" is implemented as the mean gap across all visits. A customer whose rhythm *changed* (weekly for a year, then monthly for three months) has a misleading mean. A trailing window (say, last 10 visits) would track better. Worth revisiting if the §11 precision audit comes in under 90%.
- **Multiplier legibility** — `sensitivity_multiplier = 1.5` is precise but not something a café owner will reason about. The config UI may need to present it as coarse choices ("sensitive / balanced / relaxed") mapping to multipliers underneath. Flagged as a UX task in the config section, not a blocker.

### Resolved (2026-07-28)

- ~~Cadence minimum sample~~ → `MIN_TRANSACTIONS = 3`, fixed constant.
- ~~Threshold semantics~~ → per-customer, multiplier × cadence, clamped. See Resolved PRD conflicts above.
- ~~Customer contact details~~ → nullable `contact_email` / `contact_phone` on `customers`, optional on ingest, `NO_CONTACT` offer status.
