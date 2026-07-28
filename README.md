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

### Demo data

Set `SEED_ENABLED=true` in `.env` before the first start. This seeds a business,
an admin login, and 11 customers whose visit histories are built to exercise
every path — customers who should flag, customers still on cadence, customers
with no contact details, and customers with too little history to judge.

Sign in with `SEED_ADMIN_EMAIL` / `SEED_ADMIN_PASSWORD` (defaults
`admin@lucklotter.test` / `demo-password-123`), then hit **Run scan now** rather
than waiting for the 02:00 job.

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
5. **Run scan now**, twice. The second run flags nobody — one open flag per
   customer is enforced by a partial unique index, so re-running is safe.
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
| GET/PUT | `/v1/businesses/me/config` | Trigger tuning and deal defaults |
| GET | `/v1/flags` | Flagged customers, paginated, optional `status` |
| GET | `/v1/flags/{id}` | Flag detail with its offer |
| GET | `/v1/flags/stats` | Count of un-contactable offers |
| POST | `/v1/admin/retention/run` | Run the scan on demand |

Tenant scope always comes from the JWT, never from a path, query, or body
value — `/v1/transactions` treats the `businessId` in its payload as an
assertion to check and refuses a mismatch. Another business's flag ID reads as
404, not 403, so the API never confirms that someone else's row exists.

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
  the first data that actually carries emails and phone numbers.
- Test coverage for the service layer; only the persistence mapping is covered
  today.
- A real `NotificationSender`. Phase 1 ships a logging stub: offers reach `SENT`
  without anything being delivered.
