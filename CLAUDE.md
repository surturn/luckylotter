# LuckLotter (repo: surturn/luckylotter)

AI retention layer, Phase 1. Learns each customer's normal visit rhythm from POS transactions, flags regulars who go quiet, generates a win-back offer for each, with an admin dashboard to tune the trigger and see who was flagged.

Spec: `docs/lucklotter.md`. Progress: `PRD-TODOS.md` (update it as you finish items).

## Stack
- Backend (`backend/`): Spring Boot 3.3, Java 21, Postgres 16, Flyway, JWT.
- Frontend (`frontend/`): Angular 19 standalone components, served by nginx.
- `docker-compose.yml` runs everything. No local JDK, Maven or Node needed.

## Commands
- `cp .env.example .env` then `docker compose up --build`. `JWT_SECRET` has no fallback, the backend will not boot without it.
- Dashboard: http://localhost:4200.

## Rules
- The retention diagnostic is the product's headline output. Keep the demo data's customer base realistic, percentages are taken off it.
- The reporting period picker must not move the model, only the report window.
- Migrations only through Flyway, never edit an applied migration.

## Deploy
Not deployed yet, local only. Decided 2026-10-29: it runs as a managed service for about 10 businesses on a Contabo VPS with Docker Compose, not Railway. Businesses are provisioned by the operator CLI (see README), never over HTTP. The README's Coolify mention is outdated, update it when deploying.

## Session state (update at the end of each session)
- 2026-10-09: retention diagnostic built and shown on an operator page, reporting-period picker added, demo data expanded.
- 2026-10-29: operator provisioning and password lifecycle built (V8, CLI, change-password, forced change, session invalidation). 148 tests. Next: unsubscribe and opt-out (opted-out customers stay flagged as `SUPPRESSED_OPTOUT`), then the Contabo deploy. See `PRD-TODOS.md` "Service operation".
- Next: see unchecked items in `PRD-TODOS.md`.

## Verify (see global rule: drive it like a user, mobile 390x844 and desktop 1440x900, screenshots, fix until it works)
- `docker compose up --build`. Log in, open the dashboard and the retention diagnostic, change the reporting period (model must not move), check flagged customers and offers.
