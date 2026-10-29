# Pipeline log — 017 delivery by e-mail and Telegram

- Status: running
- Phase: 3 — implement, Slice 1
- Branch: 017-delivery-email-telegram (planned) · Base: main @ 1fef5d0
- Requirement: docs/features/017-delivery-email-telegram.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

- Base branch: main (origin/HEAD not set; local main == origin/main @ 1fef5d0, no local-only commits). Working tree clean.
- Pipeline tooling present on main (skill, review loop, slice-reviewer, AWOS wrappers + core files).
- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`.
- One test: `cd backend && ./mvnw test -Dtest='<Class or package.**>'`; web `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check` (contract, format, lint, backend verify with Testcontainers + JaCoCo floor, client tests); then `make e2e`.
- Specialists: spring-backend, postgres-database, react-frontend, expo-mobile, devops-infra; testing agent: testing-expert; reviewer: slice-reviewer.
- UI: yes (web); browser tool: claude-in-chrome available.
- Safe local run mode: `make up` (local docker Postgres+Keycloak, backend dev profile, web :5173); no external services by default.
- Git rules: Conventional Commits, feature branch only, no AI attribution in commits/PRs (CLAUDE.md — overrides harness attribution reminder), never push main.
- Publishing: gh logged in (Philatelist), origin github.com/Philatelist/nadel-platform; CI jobs: Backend, Clients, Security, SAST (Semgrep), End-to-end (Playwright, smoke set).
- Settings: autonomy minimal, publish pr+checks, reviewRun on, notify true.
- Next spec number: 017.

## Phase 1

- Agent A (git history): no prior 017 work on any branch, stash or worktree. Only the requirement doc on main (5287657). Existing members `DeliveryChannel` / `telegram_username` (002) are prerequisites. Verdict: new.
- Agent B (code/spec): nothing of 017 implemented. Missing preconditions: JobRunr dependency (ADR 0007 defers it to 017), Mailpit, mail starter; no payment-recorded/reversed event (only PaymentsChanged with plot ids); no public meter-reading port and no reading-decided event; no cross-tenant list of cooperatives (tenant has RLS); documents print is per plot filtered by PAPER (017 needs per recipient + link QR); invoiceForToday has no one-version-per-day rule; no members write port for the channel; 002 rule TELEGRAM needs telegram_username conflicts with linking; no streets endpoint; settings page has no tabs; latest migration V28 → next V29. Reuse: MemberDirectory.contacts/deliveryChannels, PersonalDataCipher (+ new IndexKind TELEGRAM_CHAT), LedgerReadModel.debtsAsOf/payments/cashReceipt, DocumentsFacade (latestInvoice, invoicePdf, invoiceForToday, receiptPdf, printPdf, qrLink), PlotCatalog.idsOnStreet/findActive, PlotOwners.currentOwnerIds, OwnedPlotsSource, AuditEvent, listener pattern with TenantContext.runAs.

## Requirements brief

**Requirement:** docs/features/017-delivery-email-telegram.md. Cooperatives send invoices, payment messages (with cash receipts) and board announcements to members by e-mail (cooperative's own SMTP) and Telegram (cooperative's own bot); each send is recorded as a delivery; the bot answers «Борг», «Рахунок», takes meter readings for approval. Users: chairman (settings, announcements), treasurer (sending, status screen), members (linking, channel choice, bot).

**Prior work:** none on any branch. Verdict: **new**, extends 002 (channel), 010 (readings), 015 (payments), 016 (documents).

**Scope:** sections 2–13 of the requirement. Out of scope: section 14 (Viber, SMS, photos, push, bounces, scheduled reminders, replies, shared bot), mobile app changes.

**Acceptance criteria:** section 15 of the requirement (the owner's real bot/mailbox check is manual → `[?]`).

**Approach:** new Spring Modulith module `notifications` (channels, links, deliveries, bot poller, announcements); V29 migration with 6 RLS tables + notify_payments setting + ANNOUNCEMENTS_SEND permission; JobRunr for delivery jobs; Jakarta Mail (spring-boot-starter-mail) per-cooperative JavaMailSender; JDK HttpClient Telegram Bot API client with configurable base URL; small public ports/events added in members, meters, ledger, documents, plots; web screens (settings section «Розсилка», account «Сповіщення», deliveries screen, announcements, member card flag); Mailpit + fake Bot API in infra; ADR 0018; READMEs; guides uk/en; leaflet PDF.

**Verification:** backend module tests with Testcontainers (+ Mailpit via Testcontainers GenericContainer, in-JVM fake Bot API), client typecheck/lint/test, Playwright specs (4 new flows), full `make check` + `make e2e` in Phase 4; UI checks in Chrome via `make up`.

**Risks:** very large feature (backend + 4 cross-module ports + web + infra + guides); cross-tenant poller discovery touches the RLS model (ADR); JobRunr new dependency; e2e needs a fake Bot API reachable from the packaged jar.

**Open questions (none blocking; all take the recommended answer under `minimal`):** see AUTO entries below.

## Phase 1 decisions

- Blocking questions: none (no prior work, base OK, tree clean, requirement concrete). No AskUserQuestion asked.
- AUTO (questions: blocking): TELEGRAM channel no longer requires `telegram_username`; the username stays an optional informational field; 002 rule and the 008 import rule are relaxed accordingly.
- AUTO: API paths use `{month}` (YYYY-MM) as existing 016 billing-month endpoints, not `{id}`.
- AUTO: the poller finds cooperatives with a bot token through a narrow `SECURITY DEFINER` SQL function returning tenant ids only (no data); documented in ADR 0018.
- AUTO: single poller per cooperative = a lease in `bot_poll_state` (owner instance id + lease expiry, conditional UPDATE), not a session advisory lock (no pooled-connection pinning).
- AUTO: JobRunr added (planned by ADR 0007), plus spring-boot-starter-mail; noted in the PR.
- AUTO: the fake Bot API exists twice: an in-JVM fake in backend test sources for tests, and a tiny standalone fake (infra service in docker-compose) used by `make up`/e2e; the Bot API base URL is configurable (`NADEL_TELEGRAM_API_URL`), default the real Telegram in prod.
- AUTO: «Рахунок» one-version-per-day rule implemented in the documents facade (new method), not in notifications.
- AUTO: per-recipient paper copies with link QR via an SPI in documents implemented by notifications; `telegramLinked` on the member card via an SPI in members implemented by notifications; channel change via a new members public port.
- AUTO: new ledger events `PaymentRecorded`/`PaymentReversed` (ids only) and meters `ReadingDecided` + public `MemberReadings` port.
- AUTO: announcement streets come from a new read endpoint listing distinct streets of active plots.
- AUTO: message/e-mail texts in Ukrainian from backend message bundles (ADR 0012 pattern); the server never stores message texts.
- AUTO: the owner's real-bot check stays a manual `[?]` criterion for the PR.

## Phase 2

- Spec folder `context/spec/017-delivery-email-telegram`, branch `017-delivery-email-telegram` cut from main @ 1fef5d0.
- Baseline (`make check` on 1fef5d0): backend 3650 tests green; web vitest 3 timeouts (RunPreviewDialog ×1, RecalculationStep ×2), likely load; recorded in review-log.md.
- functional-spec.md written (Creation Mode, no markers; Assumptions & Decisions section).
- technical-considerations.md written from spring-backend, postgres-database, react-frontend and devops-infra design input (Explore step reused the Phase 1 Agent B report).
- AUTO (tech): notify_payments and e-mail rate in delivery_settings, not cooperative_settings.
- AUTO (tech): delivery-row worker with lane lease; JobRunr only schedules/wakes (OSS JobRunr has no cross-instance rate limiting).
- AUTO (tech): JobRunr 9.0.1 (newer releases < 2 weeks old), tables via Flyway V30 (nadel_app has no CREATE), skip-create; fallback @Scheduled if it does not start on Boot 4.1.
- AUTO (tech): fake Telegram for make up/e2e = Node script in infra (port 8091); backend tests use in-JVM fake; Mailpit 1025/8025.
- AUTO (tech): bot dialogue state in memory on the lease holder; paper link codes created when the print PDF is rendered; announcement text unencrypted user content; leaflet Ukrainian only.
- tasks.md written: 7 implementation slices + Feature Testing & Regression (testing-expert). Staffing gap: guides/leaflet task → general-purpose (accepted per pipeline rule; for the final report). `<!-- not-user-reviewed -->` marker removed (Phase 1 stands in for the plan review).
- Follow-up for the owner: CLAUDE.md `make infra` line (Mailpit, fake Telegram) — protected file, proposed edit.
