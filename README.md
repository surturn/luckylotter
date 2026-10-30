# LuckLotter — AI Retention Layer (Phase 1)

Learns each customer's normal visit rhythm from POS transaction data, flags the
regulars who go quiet, and generates a win-back offer for each one — with an
admin dashboard to tune the trigger and see who was flagged.

Spec: [`docs/lucklotter.md`](docs/lucklotter.md) · Progress:
[`PRD-TODOS.md`](PRD-TODOS.md)

| | |
|---|---|
| Backend | Spring Boot 3.3 / Java 21, Postgres 16, Flyway, JWT |
| Frontend | Angular 19 (standalone components), nginx |
| Infra | Docker Compose; Coolify on a single VPS as the deploy target |

## Run it

Requires Docker. No local JDK, Maven, or Node needed.

```bash
cp .env.example .env      # JWT_SECRET has no fallback — the backend won't boot without it
docker compose up --build
```

- Dashboard — <http://localhost:4200>
- API — <http://localhost:8080> (nginx also proxies `/v1` from port 4200, so the
  browser only ever talks to one origin and the API needs no CORS config)
- **Offer inbox** — <http://localhost:8025>

Offer emails are really sent, over SMTP, to a Mailpit container. The template,
the transport, the `PENDING → SENT / FAILED` transitions and the failure-code
mapping are all exercised — but nothing can reach an actual person, which is
what makes it safe to point at seeded customers. Switching to a live provider
(Brevo, SES) is `MAIL_HOST` / `MAIL_PORT` / `MAIL_AUTH` and credentials.

Set `EMAIL_ENABLED=false` to fall back to the logging stub and run with no mail
server at all.

### Demo data

Set `SEED_ENABLED=true` in `.env` before the first start. This seeds a business,
an admin login, and 11 customers whose visit histories are built to exercise
every path — customers who should flag, customers still on cadence, customers
with no contact details, and customers with too little history to judge.

Seeding finishes by running the real cadence-break scan, so the flags are raised
and the offers are **already waiting in the Mailpit inbox** by the time the stack
finishes booting — nobody presses anything to make the notification path run. It
is the same method the 02:00 schedule calls, not a shortcut past it. **Run scan
now** is still there and now demonstrates something better: a second run flags
nobody.

Sign in with `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` (defaults
`admin@lucklotter.test` / `demo-password-123`).

Seeding is skipped entirely if any business already exists, so it can't touch
real data.

## Demo walkthrough

1. **Sign in.** Everything except login and `/actuator/health` is authenticated.
2. **Flagged customers.** Each row shows the evidence behind its own trigger —
   the customer's usual gap, and the number of quiet days that crossed it. The
   amber banner counts customers who were flagged but have no email or phone;
   that gap is the point of the `NO_CONTACT` status, not an error.
3. **Open a flag.** Plain-language explanation of why this customer was flagged,
   the offer generated, and its delivery state.
4. **Trigger settings.** Sensitivity is presented as named choices with a worked
   example that updates live, because `1.5` means nothing to a café owner.
5. **Open <http://localhost:8025>.** The offers are already there: the scan that
   raised these flags ran at startup and dispatched them over SMTP on its own.
   Each mail carries the redemption code shown on the flag detail page. Nine
   arrive, one customer fails with a retriable code because the till recorded a
   phone number and no email, and two never leave at all — those are the
   `NO_CONTACT` pair. Then press **Run scan now**: it flags nobody, because one
   open flag per customer is enforced by a partial unique index.
6. **Import a POS export.** `docs/sample-pos-export.csv` is a 25-row file using
   a different till vendor's column names — `Receipt No`, `Cust ID`, `Sale
   Date`, `Total`. The importer detects all six and pre-fills the mapping form,
   with a live sample value under each field so you can confirm rather than
   trust it. Import it twice: the second run reports 25 duplicates and imports
   nothing.
6. **Ingest a transaction** for a flagged customer and their flag closes itself:

   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"admin@lucklotter.test","password":"demo-password-123"}' \
     | python -c "import sys,json;print(json.load(sys.stdin)['token'])")

   curl -X POST http://localhost:8080/v1/transactions \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"businessId":"<from the login response>","customerRef":"POS-1001",
          "externalTxnId":"DEMO-1","amount":450.00,"occurredAt":"2026-07-28T09:00:00Z"}'
   ```

   Send it twice: the second call returns `"duplicate": true` and does not count
   as a second visit.

## How the trigger works

There is no fixed "inactive after N days" setting. Each customer is measured
against their own rhythm:

```
flag when  days_since_last_visit
             > clamp(avg_interval_days × sensitivity_multiplier,
                     min_threshold_days, max_threshold_days)
```

`avg_interval_days` is only computed once a customer has **3** visits (a fixed
constant, not per-business config). Below that they have no rhythm to break and
are never flagged.

Both the threshold and the cadence are snapshotted onto each flag, so a flag
stays explainable after the business later retunes its sensitivity.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/auth/login` | Admin login, returns a JWT |
| POST | `/v1/transactions` | Ingest a POS transaction (idempotent) |
| POST | `/v1/transactions/import/preview` | Read a CSV's columns and sample rows |
| POST | `/v1/transactions/import` | Import a CSV using a confirmed column mapping |
| GET | `/v1/stats/overview` | Dashboard aggregates and the 8-week series |
| GET | `/v1/flags/{id}/visits` | A flagged customer's recent visit timestamps |
| GET/PUT | `/v1/businesses/me/config` | Trigger tuning and deal defaults |
| GET | `/v1/flags` | Flagged customers, paginated, optional `status` |
| GET | `/v1/flags/{id}` | Flag detail with its offer |
| POST | `/v1/admin/retention/run` | Run the scan on demand |

Tenant scope always comes from the JWT, never from a path, query, or body
value — `/v1/transactions` treats the `businessId` in its payload as an
assertion to check and refuses a mismatch. Another business's flag ID reads as
404, not 403, so the API never confirms that someone else's row exists.

## Running it as a service (operator commands)

Businesses and their first admin are created by the operator on the server,
never over HTTP. A command is a short-lived run of the same image under the
`operator` profile (no scheduled scan, no seed, ephemeral port):

```bash
# Create a business and its first admin. Prints a temporary password once.
docker compose run --rm --no-deps backend --spring.profiles.active=operator \
  create-business --name="Kaldi Coffee" --type=cafe-restaurant \
  --admin-email=owner@example.com --currency=KES --timezone=Africa/Nairobi \
  --deal-value=15 \
  [--deal-type=PERCENT_OFF|FIXED_AMOUNT_OFF|FREE_ITEM] [--reply-to=hello@example.com]

# New temporary password; ends every live session for that admin.
... reset-password --email=owner@example.com

# Locks the admin out on their very next request.
... deactivate-admin --email=owner@example.com
```

The temporary password goes to stdout only, never the log. The admin is held on
the change-password screen until they choose their own (the API answers 403 to
everything else), and a change or reset invalidates every older token.

## Business types

The engine is the same for every business: each customer is judged against
their own rhythm. What differs is how long a rhythm is, so `--type` (required)
picks the starting settings and the wording. Everything it sets stays editable
in Trigger settings.

| `--type` | Typical rhythm | Flag threshold clamp | Cooldown | Return window | Words |
|---|---|---|---|---|---|
| `cafe-restaurant` | weekly | 3 to 60 days | 30 days | 14 days | customer, visit |
| `barbershop` | 4 weeks | 7 to 120 days | 45 days | 21 days | client, visit |
| `hair-salon` | 7 weeks | 14 to 180 days | 60 days | 28 days | client, appointment |
| `spa-wellness` | 9 weeks | 21 to 240 days | 90 days | 35 days | client, appointment |
| `other` | monthly | 7 to 120 days | 45 days | 21 days | customer, visit |

The ceiling matters most: a 60-day cap on a client who comes every nine weeks
would flag them before they were ever due back. The offer email's wording
follows the type too ("your usual silk press", "mention this code when you
book").

### Importing a booking system's export

`docs/sample-booking-export.csv` shows the shape. In the mapping step:

- Map **Status** if the export has one. Cancelled, no-show, void and refunded
  rows are left out, not counted as visits, and the result says how many.
- Rows dated in the **future** are left out too (bookings are not visits yet),
  and counted.
- Dates with no time zone are read in the business's timezone.
- A year of history is worth importing at onboarding: a client needs three
  visits before they can be flagged, which is about six months for a spa.

The demo can be started as any type: set `SEED_TYPE` to `barbershop`,
`hair-salon` or `spa-wellness` (default `cafe-restaurant`).

## Development

Both builds run in containers, so neither a JDK nor Node is required locally.

```bash
# Backend compile
docker run --rm -v "$PWD/backend:/app" -v lucklotter_m2:/root/.m2 \
  -w /app maven:3.9-eclipse-temurin-21 mvn -B -DskipTests compile

# Backend tests — Testcontainers needs the Docker socket passed through
docker run --rm -v "$PWD/backend:/app" -v lucklotter_m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /app maven:3.9-eclipse-temurin-21 mvn -B test
```

`TESTCONTAINERS_HOST_OVERRIDE` is needed because the Postgres container is a
*sibling* of the Maven container, not a child — its published port lands on the
Docker host, not on the Maven container's localhost.

Docker discovery also depends on
`backend/src/test/resources/docker-java.properties` pinning `api.version=1.44`.
Docker Engine 29 dropped API versions below 1.44, and Testcontainers 1.20.4
forces v1.32 when it can't determine one, so without the pin the daemon rejects
`/info` with HTTP 400 and Testcontainers reports "Could not find a valid Docker
environment" — which reads like a missing daemon rather than a rejected API
version. The pin can go once Testcontainers is upgraded to a version that
negotiates.

Frontend, with a JDK-free dev server proxying `/v1` to `localhost:8080`:

```bash
cd frontend && npm install && npm start
```

## Before a pilot

Tracked as hard gates in `PRD-TODOS.md`:

- Structured JSON logging, and a no-PII audit against real ingestion payloads —
  the first data that actually carries emails and phone numbers. `Redact.scrub`
  keeps third-party error text out of the logs today; what it has never been
  run against is a live POS feed.
- A live mail provider. The SMTP path itself is real — template, transport,
  `PENDING → SENT / FAILED` and the failure-code mapping all execute — but it
  points at Mailpit, so nothing reaches an actual person. Switching over is
  `MAIL_HOST` / `MAIL_PORT` / `MAIL_AUTH` plus credentials.
- SMS. A phone-only customer lands at `FAILED` with a retriable code rather
  than a false `SENT`, so a sender added later picks those offers up.
- Redemption tracking. Offers carry a code; nothing marks one as used.
