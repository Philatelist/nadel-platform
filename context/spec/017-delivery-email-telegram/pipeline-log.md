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

## Phase 3

- S1 infra task done (devops-infra): Mailpit axllent/mailpit:v1.27.8, fake-telegram node:22.12.0-alpine3.21 + infra/fake-telegram/server.mjs, infra/README, Makefile. Verified healthy, curl checks quoted. Not done: `.env.example` (`NADEL_TELEGRAM_API_URL`) — the permission system denied the agent reading/editing it; not bypassed by the orchestrator; surfaced to the owner as a follow-up.
- S1 migration task done (postgres-database): V29 with 7 tables, guards, notifications_bot_tenants(), ANNOUNCEMENTS_SEND (Java enum + openapi enum + catalog + 4 existing tests updated for 28 permissions). Orchestrator re-ran 7 classes: 93 tests, 0 failures.
- User (2026-10-29): `.env.example` stays with the owner (adds the line on the PR branch before merging). Code must not depend on it: `NADEL_TELEGRAM_API_URL` defaults to `http://localhost:8091` in the dev profile and `https://api.telegram.org` otherwise. Mention the variable in the owner manual (Slice 7 guides task updated) and in the PR description (Follow-ups).
- S1 backend task done (spring-backend): notifications module, delivery settings GET/PUT + test-email + test-telegram, MailSenderFactory, TelegramClient, FakeBotApi + Mailpit test helpers, ADR 0018 draft. Verify 151 tests green, typecheck green; RED validation on 4 tests. Deviations accepted (recorded for the PR): angus-mail in compile scope (Boot-managed, already transitive); mail sender cache keyed on all mailbox values; test-email result adds failureCode; TELEGRAM_UNAVAILABLE code; missing token → DELIVERY_NOT_CONFIGURED; NADEL_TELEGRAM_API_URL defaults as the owner asked (application.yml:76, :110).
- S1 web task done (react-frontend): DeliverySettingsPanel/SmtpForm/TelegramBotForm, audit «змінено» for password/botToken, i18n deliverySettings.* and deliveries.reason.*; api-client index export added. uk label of ANNOUNCEMENTS_SEND kept «Надсилання оголошень» (noun style of neighbours). Guides deferred to Slice 7.
- S1 Verify (orchestrator): backend 124 tests green; pnpm typecheck, lint, test green (web 875).
- Slice 1 commit 515ec7b (backend 124 + clients green); review round 1 started (b91832e..515ec7b).
- Slice 1 review round 1: PASS (3 minor notes in review-log.md; SSRF note to surface in the final report).
- S2 backend task done (spring-backend): 671 tests in its Verify set green; orchestrator re-ran 8 classes: 68 green. Deviations: new migration V29_1 relaxing `member_channel_contact_ck` (V23 also required the username for TELEGRAM); controller renamed NotificationsController (one generated interface per tag); bot classes in notifications/internal; update offset taken before answering (at most once); 401 stop kept in memory; scheduled bot path not covered by integration tests (bot-enabled=false in tests; e2e covers it); TELEGRAM_NOT_CONFIGURED also when @name unknown and getMe fails.
- S2 web task done (react-frontend): NotificationsPanel, MemberDetails Telegram line, auditValue endReason, i18n; MemberForm needed no change (no client rule existed). S2 Verify (orchestrator): backend 624 green (1 skipped, existing), clients green (web 883).
- Slice 2 commit b811a37; review round 1 started (515ec7b..b811a37).
- Slice 2 review round 1: PASS (minor notes in review-log.md).
- S3 backend task done (spring-backend): JobRunr 9.0.1 starts on Boot 4.1.1 with Jackson 3 (no fallback); V30__jobrunr.sql from the jar; Verify set 464 green; RED on 6 tests. Deviations: second cross-tenant SECURITY DEFINER function `notifications_due_lanes(timestamptz)` in V30_1 (sweep runs without a tenant) — to record in ADR 0018 (Slice 7); retry 409 also for queued rows; Telegram error → code mapping (401 CHANNEL_NOT_CONFIGURED, other 4xx NO_CONTACT, 429 no attempt counted); scope ALL print stays per plot; print transaction now read-write (link codes); QR only when bot_username known; dispatcher handles INVOICE kind only until Slice 4; JobRunr uses the DataSource directly for its own tables; slice-1 test DocumentsAppendOnlyTests adjusted (`truncate invoice, delivery`).
- S3 web task done (react-frontend): SendInvoicesDialog, InvoiceSendButton, DeliveriesPage + deliveries components, nav, i18n; extra «У черзі» column; send disabled when the preview creates nothing. S3 Verify (orchestrator): backend 377 green; clients green (web 894).
- Slice 3 commit 346a693; review round 1 started (b811a37..346a693).
- Slice 3 review round 1: MAJOR_ISSUES (M1 dispatcher loop on unexpected exceptions). The fix touches DeliveryDispatcher, which the Slice 4 builder is editing → per the review loop, finish and commit Slice 4 first, then the fix as a separate commit.
- S4 done (spring-backend): PaymentRecorded/PaymentReversed (ids only) from PaymentService.book and PaymentCorrections.reverse; LedgerReadModel.paymentsByIds; PaymentMessages listener; dispatcher handles PAYMENT/REVERSAL; DeliveryMessage without PDF. Verify set 864 green; orchestrator re-run 64 green. Choices: one message per plot per owner (a payment row covers one plot); delivery month = payment's billing month; payment rows show no plot number on the delivery screen (DeliveryRegister resolves plots via invoices — note for verify); missing payment → NO_CONTACT; PaymentAppendOnlyTests adjusted (`truncate payment, allocation, delivery`).
- Slice 4 commit 3be8723; review round 1 started (346a693..3be8723).
- git: auto-gc failed on stale metadata of the removed review-017-s3-r1 worktree; ran `git worktree prune` (also dropped a stale entry of another session's run-012 whose directory no longer existed) and removed the stale .git/gc.log as git asks. No objects or branches touched.
- Slice 4 review round 1: PASS (minor notes in review-log.md).
- Slice 3 review fix round 1 (spring-backend): unexpected exceptions → transient CONNECTION_FAILED through the retry path; new test with RED validation; 37 tests green.
- Slice 3 review fix commit 053feb8; review round 2 started.
- Slice 3 review round 2: PASS (M1 resolved).
- S5 done (spring-backend): MemberReadings port + ReadingDecided, DocumentsFacade.invoiceForDay, BotCommands («Борг», «Рахунок», «Показання»), ReadingMessages. Verify set 395 green + 64 neighbours; orchestrator re-run 28 green. Choices: reading decision messages sent directly via TelegramClient (no delivery row, no migration), duplicates prevented in memory (a crash between send and outbox completion could repeat one); port tests named MemberReadingsPortTests; "today's version" counts any version issued today; «Скасувати» button added; meter question always asked; numbers without spaces; DocumentFormats.number added.
- Slice 5 commit 46121a6; review round 1 started (053feb8..46121a6).
- git: background auto-gc raced again with a removed review worktree (bad object worktrees/review-017-s3-r2/HEAD); pruned, removed .git/gc.log, `git fsck --connectivity-only` clean (only dangling objects). From now on pipeline commits use `git -c gc.auto=0 commit`.
- Slice 5 review round 1: PASS (minor notes in review-log.md).
- S6 backend task done (spring-backend): GET /plots/streets, announcements preview/send/list/detail, dispatcher ANNOUNCEMENT, audit ANNOUNCEMENT_SENT (no text). Verify (widened) 496 green; RED on 3 tests. Choices: contract maxLength 4000 UTF-16 units, server checks 2000 code points → ANNOUNCEMENT_TOO_LONG; announcement channel rule (paper/Viber always paper; preferred channel first, then the other chosen one that reaches the member); paper members get PAPER/TO_PRINT rows (not marked by month «Роздруковано»); DeliveryRow.announcementId added; streets from active non-common plots, case-insensitive distinct, uk-x-icu sort; new MemberDirectory.activeIds(); MailChannel keeps line breaks as <br>.
- S6 web task done (react-frontend): Announcements list/form/detail pages, nav, DeliveryTable link column, i18n. S6 Verify (orchestrator): backend 463 green; clients green (web 906).
