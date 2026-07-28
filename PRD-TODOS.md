
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

## Running the build

Moved to [`README.md`](README.md) — build commands, the demo walkthrough, and
the Testcontainers/Docker-API notes now live there.

---

## M0 — Scaffold & foundation

### M0-0: Reset the scaffold to the revised Phase 1 scope — ✅ done
- [x] Decided: gamification schema **deleted**, not archived — it was SQL-only (no Java entities existed) and Phase 2 will re-derive it from the Phase 2 PRD. Recoverable from git history if needed (support)
- [x] Replaced `V1__init.sql` with the Phase 1 schema — no `spin_tokens` / `rewards` / `vouchers` tables (§9)
- [x] `brands` → `businesses`, `app_users` → `admin_users` per §9 naming (§9)
- [x] Dropped the local Postgres volume; migration verified applying clean from empty (support)
- [x] Removed the leftover `lucklotter.spin.*` config block from `application.yml`; retargeted `pom.xml` `<description>` (support)
- [x] Amend `docs/lucklotter.md` to match the two resolved decisions, so the PRD stops contradicting the schema: FR-1..FR-6 rewritten, §9 data model replaced with the real schema + the constraints that carry design decisions, §5 US-1 story reworded off "days of inactivity", §10 config row, §12 split into still-open vs. resolved (support)

### M0-1: Infrastructure
- [x] Docker Compose with Postgres 16 + backend service, healthcheck, named volume (§6)
- [x] Spring Boot project skeleton: web, data-jpa, security, validation, actuator, Flyway, JJWT, Testcontainers (§6)
- [x] Add the Angular app as a third Compose service (frontend) (§6)
- [x] Scaffold Angular workspace: standalone components, Router, RxJS, HTTP interceptor for JWT (§6)
- [x] Move every env-specific value (DB creds, JWT secret, notification keys) to env vars with no hardcoded fallbacks in committed config (NFR-6)
- [x] Replace the dev-only `JWT_SECRET` literal in `docker-compose.yml` with an `.env`-sourced value + committed `.env.example` (NFR-6)
- [x] Write `README.md` with local run steps (`docker compose up`, seeded admin login) (support)

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
- [x] Add `getOffer()` traversal coverage — `RetentionFlagOfferMappingTest`, 5 cases: plain `findById`, the fetch-join-free `findByIdAndBusinessId` behind `GET /v1/flags/{id}`, the back-reference, the no-offer-yet null case, and a `NO_CONTACT` offer (support)
- [x] First test infrastructure: `AbstractPostgresTest` — real Postgres 16 + Flyway, never H2, because the partial unique index and the offer `CHECK` constraints are exactly what an in-memory substitute wouldn't enforce (support)
- [ ] **Inverse `@OneToOne` is not actually lazy.** The test SQL shows `RetentionFlag.getOffer()` firing a secondary `SELECT ... FROM offers WHERE flag_id = ?` on every flag load, despite `fetch = LAZY` — Hibernate can't proxy a *nullable* inverse to-one without bytecode enhancement. Harmless for flag detail (one extra query); an N+1 on any future list query that forgets the `LEFT JOIN FETCH`. Decide: enable the Hibernate bytecode-enhancement plugin, or treat the fetch join as mandatory on every list path and assert it (NFR-5)

### M0-2: Layering guardrails
- [x] Create package structure enforcing Controller → Service → Repository (§6)
- [x] Define explicit request/response DTOs; assert no JPA entity is returned from any controller (§6)
- [ ] Add an ArchUnit (or equivalent) test: services must not reference `@RestController` classes (§6)

### M0-3: Structured logging & correlation IDs
- [ ] Configure JSON log encoder for app + scheduled job output (NFR-4)
- [x] Add a servlet filter that assigns/propagates a correlation ID into MDC per request (NFR-4)
- [x] Propagate a run ID through the scheduled job's log lines (NFR-4)
- [ ] Audit log statements so no customer name, email, or phone is ever logged — internal IDs only (NFR-4)
- [ ] Test asserting a PII-bearing field never reaches the log output (NFR-4)

---

## US-1 / US-3: Admin login and business config

### Auth
- [x] `businesses` and `admin_users` tables with FKs and `NOT NULL` constraints (NFR-2, §9)
- [x] `AdminUser` entity + repository; `BCryptPasswordEncoder` bean already wired in `SecurityConfig` (FR-6)
- [x] Password hashing actually applied on user creation + a seed/bootstrap admin (FR-6)
- [x] `POST /v1/auth/login` — validate credentials, return JWT carrying `business_id` + user ID (FR-6, §10)
- [x] JWT auth filter + `SecurityConfig` locking down every route except login and `/actuator/health` (NFR-1)
- [x] Service-layer tenant guard: resolve `business_id` from the authenticated principal, never from a request param (NFR-1)
- [ ] Tests: expired token, missing token, and token for business A cannot read business B's data (NFR-1)

### Business config
- [x] Config DTOs: `BusinessConfigResponse` / `BusinessConfigUpdateRequest` with bounds on multiplier and clamps (FR-6)
- [x] `GET /v1/businesses/me/config` — returns multiplier, min/max clamps, default deal type + value, and read-only `minTransactions` (FR-6, §10)
- [x] `PUT /v1/businesses/me/config` — validated update; enforce the cross-field rule `minThresholdDays <= maxThresholdDays` in the service (FR-6, §10)
- [x] Angular config screen: form bound to the config endpoints, with validation + save feedback (FR-6, US-1, US-3)
- [x] Config screen must explain the multiplier in plain language (e.g. "flag at 1.5× a customer's usual gap") — a raw decimal is not admin-legible (US-1, US-3)
- [ ] Tests: config update is scoped to the caller's business only (FR-6, NFR-1)

---

## US-5: Ingestion and cadence calculation

### M1-1: Transaction ingestion
- [x] `customers` and `transactions` tables; FKs, `NOT NULL` on business/customer refs, `contact_email`/`contact_phone` nullable (NFR-2, §9)
- [x] `external_txn_id` unique **per business** — POS IDs can collide across tenants, so the PRD's bare `UNIQUE` was widened to `(business_id, external_txn_id)` (NFR-3, §9)
- [x] Composite index on `(business_id, customer_id, occurred_at)` (NFR-5)
- [x] `TransactionIngestRequest` with optional `contactEmail`/`contactPhone` (FR-1)
- [x] `POST /v1/transactions` — controller + service, DTO validation for business ID, customer ref, timestamp, amount (FR-1, §10)
- [x] Upsert-by-`external_ref` customer resolution: create the customer on first sighting (FR-1)
- [x] Update contact fields on ingest when the payload supplies them — a later transaction should be able to fill a previously-missing email/phone (FR-1)
- [x] Idempotency: accept `Idempotency-Key` header or natural POS txn ID; duplicate submission is a no-op, not a second visit (NFR-3)
- [ ] Test: replaying the same transaction twice leaves one row and does not shift the cadence (NFR-3)

### M1-2: Cadence calculation
- [x] Minimum transaction count fixed at `MIN_TRANSACTIONS = 3` and encoded as a constant (FR-2)
- [x] `customers.transaction_count` + `avg_interval_days` columns; null cadence = not flaggable (FR-2)
- [x] Compute average visit interval per customer from transaction history (FR-2)
- [x] Update `last_visit_at`, `transaction_count`, and `avg_interval_days` on each ingested transaction (FR-2)
- [x] Handle out-of-order ingestion: a backdated transaction must not push `last_visit_at` backwards (FR-2)
- [ ] Performance test: cadence recompute across 10,000 customers stays inside the batch window with no seq scans — verify via `EXPLAIN` (NFR-5)

### M1-3: Flag auto-resolution
- [x] On ingestion, flip that customer's `ACTIVE` flag to `RESOLVED` with `resolved_at` set (FR-9)
- [ ] Test: ingesting a transaction for a flagged customer resolves exactly that one flag (FR-9)

---

## US-4 / US-5: Trigger engine and offer generation

### M2-1: Scheduled trigger job
- [x] `retention_flags` table with `status[ACTIVE|RESOLVED]`, `flagged_at`, `resolved_at` (§9)
- [x] Partial unique index `UNIQUE (customer_id) WHERE status = 'ACTIVE'` — verified rejecting a second active flag (FR-8, NFR-2, §9)
- [x] `threshold_days_applied` + `avg_interval_days_at_flag` snapshot columns, so a flag stays explainable after the business retunes (FR-3, §11 precision audit)
- [x] `CustomerRepository.findFlagCandidates` — index-friendly pre-filter excluding customers below `MIN_TRANSACTIONS` or already flagged (FR-3, FR-8, NFR-5)
- [x] Spring `@Scheduled` daily job (cron in `lucklotter.retention.scan-cron`) driving the scan (FR-3)
- [x] Apply the clamped per-customer threshold via `Business.thresholdDaysFor(...)` — the coarse SQL pre-filter is not the decision (FR-3, FR-6)
- [x] Create an `ACTIVE` flag per newly-detected customer, handling the unique-constraint violation as "already flagged, skip" (FR-8)
- [ ] Test: two concurrent/overlapping job runs produce exactly one flag and one offer per customer (FR-8, NFR-3)
- [ ] Test: a customer with an existing `ACTIVE` flag is not re-flagged on the next run (FR-8)
- [x] Job run summary log line: customers scanned, flagged, skipped, errored (NFR-4)

### M2-2: Offer generation
- [x] `offers` table: unique `flag_id` FK, `deal_type`, `deal_value`, `status[PENDING|SENT|FAILED|NO_CONTACT]`, `failure_reason`, `sent_at` (§9)
- [x] `Offer.markSent/markFailed/markNoContact` status transitions (FR-5)
- [x] Generate one offer per newly flagged customer, snapshotting the business's default deal type + value (FR-4)
- [x] Route on contactability: `PENDING` if the customer has an email or phone, else `NO_CONTACT` — never silently pending (FR-5)
- [ ] Test: a customer with neither contact field gets an offer at `NO_CONTACT`, and the stub sender never picks it up (FR-5)
- [ ] Test: flag creation and offer creation commit in the same transaction — no flag without an offer (FR-4, FR-5)

### M2-3: Notification delivery
- [x] Define the `NotificationSender` interface (§6)
- [x] Implement the logging/stub sender as the Phase 1 default (§6, §12)
- [x] Transition offer status `PENDING` → `SENT` on success, → `FAILED` on exception, recording `sent_at` (FR-5)
- [x] Sender queries `PENDING`/`FAILED` only — `NO_CONTACT` is terminal and must never be retried (FR-5)
- [x] Senders map provider errors onto the `OfferFailureCode` enum; the provider's own message goes to the correlated log, never into the column (NFR-4)
- [ ] Test: a sender that throws with a phone number in its exception message stores only a code — no PII reaches `offers.failure_code` (NFR-4)
- [x] Ensure a send failure does not roll back the flag/offer records (FR-5)
- [ ] Test with a deliberately failing sender: offer lands at `FAILED`, flag stays `ACTIVE` (FR-5)

---

## US-2: Admin dashboard

### M3-1: Flag list API
- [x] `FlagSummaryResponse` / `FlagDetailResponse` DTOs, incl. the cadence evidence behind each trigger (FR-7, §11)
- [x] Fetch-joined `findForDashboard` / `findForDashboardByStatus` queries to avoid N+1 on customer + offer (FR-7, NFR-5)
- [x] `GET /v1/flags` — paginated list, optional `status` filter (FR-7, §10)
- [x] `GET /v1/flags/{id}` — flag detail with its offer (FR-7, §10)
- [x] Scope both endpoints to the caller's business at the service layer (NFR-1)
- [ ] Index/query tuning so the paginated list returns in <1.5s at 10k customers (NFR-5, §11)

### M3-2: Angular views
- [x] Login screen + JWT storage and auth guard on routes (NFR-1)
- [x] Flagged-customer list view: paginated table with last visit date and offer status (FR-7, US-2)
- [x] Surface `NO_CONTACT` distinctly in the list (not as a generic failure) — exposing the contactability gap is the whole point of the status (FR-5, US-2)
- [x] Show a count of un-contactable flagged customers so the admin can see how much of their POS data lacks contact details (FR-5)
- [x] Flag detail view (FR-7)
- [x] Dashboard shell: nav between config and flag list, logout (support)
- [x] Loading/empty/error states on both data views (support)

---

## M4 — Pilot readiness

### 🚧 Hard gates — M4 cannot be declared done while any of these is open

- [x] **~~`SecurityConfig` ends in `anyRequest().permitAll()`~~** — closed 2026-07-28. Now `anyRequest().authenticated()` with the JWT filter in place and an explicit three-entry public list; verified by hand that an unauthenticated `/v1/flags` returns 401 (NFR-1)
- [x] **~~`JWT_SECRET` dev literal~~** — closed 2026-07-28. `docker-compose.yml` now uses `${JWT_SECRET:?...}` with no fallback, so a deployment that forgot to set it fails to start rather than booting on a secret that is public in git. `.env.example` committed, `.env` gitignored (NFR-6)
- [x] **No-PII log audit** — run 2026-07-29 against a full seed + scan + email dispatch + CSV import, i.e. logs produced while 161 payloads carrying emails and phone numbers passed through. `docker compose logs backend | grep -icE "@example\.test|\+2547"` returns **0**. Re-run this grep after adding any log statement that touches a customer (NFR-4)
- [ ] Automate that audit as a test, so it fails a build rather than depending on someone remembering to grep (NFR-4)
- [ ] **Service-layer test coverage.** Everything under M1–M3 was verified by hand against the running stack, not by tests — only the persistence mapping is covered. The individually-listed tests below are the gate; a pilot must not depend on manual verification holding

### Pilot tasks

- [x] CSV import path for a POS transaction export, reusing the ingestion service — plus an `/import/preview` step so the admin maps their own column names, since no two till vendors agree on them. `docs/sample-pos-export.csv` is a 25-row demo export (FR-1, §12)
- [ ] Decide whether a **backdated** transaction should resolve an open flag. Today any newly ingested transaction resolves it (FR-9 as written), which is right for live POS traffic but wrong for a historical CSV backfill — importing old data would close flags the customer never actually answered. Blocks the CSV importer above (FR-9, FR-1)
- [x] Seed script: sample business, admin user, and a realistic transaction history that produces flags on first job run (G4, §13)
- [x] Manual-run trigger for the batch job (admin endpoint or CLI) so a demo doesn't wait for the daily schedule (support)
- [ ] Precision check: audit a sample of flags against a manual review, record the % correctly flagged (§11, target ≥90%)
- [ ] Measure and record cadence-break → offer-generated latency (§11, target ≤24h)
- [ ] Confirm zero duplicate flags/offers over a full pilot period (§11)
- [x] Demo walkthrough script — `README.md` § "Demo walkthrough": sign in, read a flag's evidence, run the scan twice to show idempotency, ingest a transaction to watch a flag close (§13)
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

- **Multi-tenancy (§12)** — schema is multi-tenant regardless; the open question is only whether concurrent multi-business pilots need testing in Phase 1.
- **Deal value logic (§12)** — static default per business, or escalating by inactivity duration? Affects M2-2. Assumed static for now.
- **Offer expiry** — the `offers` table has no expiry column and no FR covers offer/deal validity period. Intentional for Phase 1?
- **Flag list date filtering** — status filtering is now in scope (see M3-1); a `flagged_at` date-range filter is still unspecified in §10.
- **Cadence definition** — "average visit interval" is implemented as the mean gap across all visits. A customer whose rhythm *changed* (weekly for a year, then monthly for three months) has a misleading mean. A trailing window (say, last 10 visits) would track better. Worth revisiting if the §11 precision audit comes in under 90%.
- **Multiplier legibility** — `sensitivity_multiplier = 1.5` is precise but not something a café owner will reason about. The config UI may need to present it as coarse choices ("sensitive / balanced / relaxed") mapping to multipliers underneath. Flagged as a UX task in the config section, not a blocker.

### Resolved (2026-07-28)

- ~~Notification channel~~ → Phase 1 now **really sends email** over SMTP
  (`EmailNotificationSender`, `@Primary`, behind `EMAIL_ENABLED`), caught by a
  Mailpit container in Compose. The logging stub remains the default so a
  deployment without mail configured still runs. SMS stays post-pilot; a
  phone-only customer currently lands at `FAILED` with a retriable code rather
  than a false `SENT`, so an SMS sender added later picks those offers up.
- ~~Offer redemption codes~~ → issued per offer (V2 migration), unique per
  business, shown in the email and on the flag detail. Redemption **tracking**
  is Phase 2: nothing marks a code as used.

- ~~Cadence minimum sample~~ → `MIN_TRANSACTIONS = 3`, fixed constant.
- ~~Threshold semantics~~ → per-customer, multiplier × cadence, clamped. See Resolved PRD conflicts above.
- ~~Customer contact details~~ → nullable `contact_email` / `contact_phone` on `customers`, optional on ingest, `NO_CONTACT` offer status.
