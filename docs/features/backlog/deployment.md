# Deployment: staging and production (backlog, gets its number when scheduled)

Infrastructure feature, deliberately last: the owner first finishes the product on the local machine and only then
moves to servers (decision of 20.10.2026). Before running it through `/feature-pipeline`, move it to
`docs/features/NNN-deployment.md` with the next free number. Requires ADR 0010 (read it first).

## 1. Goal

The platform can be run outside a developer machine: a staging server that follows `main`, a production server that
receives tagged releases after manual approval, nightly backups with a tested restore, monitoring, and a runbook the
owner can follow alone. No product feature changes.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/architecture.md`, ADRs 0001, 0002, 0003 and 0010, `infra/README.md`,
`infra/docker-compose.yml`, `.github/workflows/`, and `backend/src/main/resources/application.yml`.

Feature sequence: runs after the local product is complete (see `docs/roadmap.md`).

Changes to `.github/` and `.claude/` are proposed by agents and committed by the owner (CLAUDE.md); the PR description
lists them. Agents never connect to real servers and never hold real secrets: everything is verified locally with a
production-like compose stack and in CI.

## 2. Images

- `backend/Dockerfile`: multi-stage; build with the Maven wrapper, run on a slim JRE 25 image as a non-root user;
  exposes 8080; health check on `/actuator/health/readiness`. Layered jar for small updates.
- `web/Dockerfile` (or a Caddy image with the web build): builds with pnpm and serves the static files with Caddy; the
  API base URL and OIDC settings come from runtime configuration, not baked per environment (one image for staging and
  production).
- Images are pinned by digest or exact version for every base image; no `latest`.

## 3. Production-like compose (`infra/prod/`)

- `docker-compose.yml` with PostgreSQL + PostGIS (data on a named volume, no published port), Keycloak in production
  mode (`start`, not `start-dev`; its own database; hostname from environment; admin console not reachable from the
  internet), the backend, and Caddy (ports 80 and 443 only; automatic TLS; routes `/` → web, `/api` → backend,
  `/auth` → Keycloak public endpoints).
- `.env.example` lists every variable with a comment; no default passwords in production. The backend refuses to start
  in the `prod` profile when any secret still has a development default.
- A `prod` Spring profile: no demo data (today `profiles.default` is `dev`, which loads `db/demo`; production must set
  `prod` explicitly and the image must default to it), JSON logs without personal data, actuator limited to health.
- Database roles are created by an init script with passwords from the environment (same split as today:
  `nadel_owner` for Flyway, `nadel_app` without `BYPASSRLS`).
- Keycloak realm for production: a separate realm file without demo users; `tenant_id` mapping as in development;
  SMTP settings from the environment so users can set their password by email.
- `make prod-local` starts this stack on a developer machine with a self-signed certificate, for testing.

## 4. First cooperative and first chairman

There is no operator console yet (phase 7). Add an operations command, run on the server, that:

1. creates a cooperative (name, short code) and seeds its base roles;
2. creates the chairman member and the matching Keycloak user with the `tenant_id` attribute;
3. makes Keycloak send the "set your password" email.

It is idempotent (running it twice does nothing new), refuses to run without the `prod` or `staging` profile, and
writes an audit entry. Documented in the runbook.

## 5. CI/CD (`.github/workflows/`)

- On every push to `main` after the existing checks pass: build both images, push to GHCR tagged with the commit SHA,
  then deploy to the `staging` environment over SSH.
- On a `v*` tag: retag the images of that commit with the version and deploy to the `production` environment, which
  requires manual approval (GitHub environment protection).
- Deploy steps: copy the compose files, `docker compose pull`, take a database backup (production only),
  `docker compose up -d`, wait for the readiness endpoint, fail the job (and keep the previous containers if possible)
  when it does not become ready in time.
- Rollback: a manual workflow that deploys a given earlier tag.
- SSH keys and host fingerprints are GitHub environment secrets; the deploy user on the server can only run Docker
  Compose in the app directory.

## 6. Migrations policy

Add to `CLAUDE.md` (owner commits): migrations are expand/contract. A release may add columns, tables or nullable
fields; removing or renaming happens in a later release, after no deployed code uses the old shape. Every migration must
keep the previous release working.

## 7. Backups and restore

- Nightly `pg_dump` of the application and Keycloak databases, compressed and encrypted, uploaded to object storage or a
  storage box outside the server; retention 30 daily and 12 monthly copies; a failed backup sends an alert.
- `infra/prod/restore.sh` restores a chosen backup into a fresh stack. A scheduled CI or server job restores the latest
  production backup onto staging monthly and runs a smoke check (backup is proven readable).
- Production deploys take a backup right before migrating.

## 7a. Personal data master key

Feature 014 (ADR 0016) encrypts members' contacts and notes. Every cooperative's data keys are wrapped by one master
key, `NADEL_PD_MASTER_KEY`. Lost key means lost contacts: nothing else can open them.

- Where it lives: the server's secret store (a root-only env file with mode 0600, or the platform's secret mechanism),
  passed to the backend as an environment variable. Never in git, images, compose files, logs or backups.
- Generate: `openssl rand -base64 32` (32 random bytes in base64). Use different keys per environment (staging,
  production); a key copied between them defeats the separation.
- Copies: at least two offline copies, a password manager entry and a sealed paper copy, held apart from the database
  backups. A backup and its key stored together give no protection.
- The `prod` profile refuses to start without a valid key (missing or not 32 bytes of base64). Flyway must be enabled
  there: the key-check runs as a Flyway callback after the migrations, on every start. If the key is not the one the
  database was encrypted with, start-up fails with an ERROR log line containing `PD_MASTER_KEY_MISMATCH` (never a key or a
  value).
- Restore drills need the key. Each drill restores the database, boots the backend with the key and checks that
  contacts open. A second drill boots with a wrong key and expects start-up to fail with `PD_MASTER_KEY_MISMATCH`.
- Rotation:
  1. Generate a new key: `NADEL_PD_MASTER_KEY=<output of: openssl rand -base64 32>`.
  2. Deploy with `NADEL_PD_MASTER_KEY=<new key>` and `NADEL_PD_MASTER_KEY_PREVIOUS=<old key>`.
  3. At start-up the server re-wraps every cooperative's keys (one transaction per cooperative) and logs counts only:
     `Personal data keys: N checked, M re-wrapped, 0 left on the previous master key`. Any key wrapped with neither
     key stops the start-up with `PD_MASTER_KEY_MISMATCH` before anything is re-wrapped.
  4. Remove `NADEL_PD_MASTER_KEY_PREVIOUS` and redeploy.
  5. Keep the old key offline until no backup that predates the rotation is retained: such a backup still holds keys
     wrapped by the old master key.
- The data keys themselves are not rotated in this feature; only the master key is.

## 8. Monitoring

- External uptime check of the web and API health endpoints, alerting to Telegram.
- Error tracking (Sentry-compatible) for backend and web, with personal data scrubbing on.
- Disk, memory and certificate expiry alerts from the server.

## 9. Server hardening (documented in the runbook, scripted where reasonable)

SSH keys only, no root login, firewall open only on 22 (restricted), 80 and 443; automatic security updates; Docker
log rotation; time sync; the Keycloak admin console and the database only through an SSH tunnel.

## 10. Runbook (`docs/ops/runbook.md`)

Step by step for one person:
- creating the servers;
- the domain and DNS;
- first install;
- creating the first cooperative;
- releasing a version;
- rolling back;
- restoring from backup;
- rotating secrets;
- what to do when an alert fires.

Each step comes with the exact commands.

## 11. Out of scope

Kubernetes, multiple application instances, managed databases, the operator console and subscriptions (phase 7),
mobile store publishing (phase 5).

## 12. Acceptance criteria

- [ ] Both images build in CI, run as non-root, and pass a container smoke test (backend ready, web serves, API proxied).
- [ ] `make prod-local` brings up the production-like stack; sign-in works through Caddy and Keycloak in production mode;
      no demo data is present.
- [ ] The backend refuses to start in `prod` with a default secret (test).
- [ ] The first-cooperative command creates a cooperative, its base roles and a chairman who can sign in after setting a
      password; running it again changes nothing (integration test with Testcontainers Keycloak or a documented manual
      check).
- [ ] Staging and production workflows exist; production needs approval; rollback workflow exists.
- [ ] Backup and restore scripts work against the local production-like stack (scripted test in CI).
- [ ] `docs/ops/runbook.md` covers every item of section 10; `infra/README.md` points to it.
- [ ] `make check` green; existing E2E tests unaffected.
