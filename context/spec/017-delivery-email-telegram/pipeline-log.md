# Pipeline log — 017 delivery by e-mail and Telegram

- Status: finished
- Phase: 6 — review run (done)
- Branch: 017-delivery-email-telegram · Base: main @ 1fef5d0
- Requirement: docs/features/017-delivery-email-telegram.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/61 · Checks: green (run 40973703903)
- Review run: running at http://localhost:5173 (PIDs in scratchpad/review-run.pids)

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
- Slice 6 commit 1a3a928; review round 1 started (46121a6..1a3a928).
- Slice 6 review round 1: PASS (minor notes in review-log.md).
- S7 demo/docs task done (spring-backend): DemoNotifications (Mailpit mailbox, demo bot, links for member@demo.local …0003 (EMAIL channel, linked so the owner can try the bot) and …0008, 19 delivery rows for 2026-09 written as SENT/TO_PRINT, nothing dispatched), DemoNotificationsTests, R__demo_data.sql wipes the 7 017 tables (re-run resets the demo cooperative's delivery settings; R__ checksum changes → demo data rebuilt on next start), DemoDocuments @Order; ADR 0018 accepted with all decisions incl. both SECURITY DEFINER functions and the SSRF note; ADR 0007 note; architecture.md; READMEs. Verify 25 green; dev run on :8081 with a scratch DB: demo runner OK, bot answered «Борг» via the fake server, fake server down → retry, app stays UP.
- Ports 8080 (java PID 35570, started 14:00 from this checkout) and 5173 (node PID 35435) are held by processes the pipeline did not start; Keycloak redirects and the vite proxy are fixed to 5173/8080, so guide screenshots, Phase 4 UI checks and the Phase 6 review run need them. Not killed; asking the owner.
- Owner (2026-10-29): stop PIDs 35570 and 35435 → stopped with plain kill; ports 8080/5173 free. The pipeline starts the 017 branch with `make up` and leaves it running at the end (review run).
- S7 guides task done (general-purpose — no guides specialist): board guide uk/en new chapter 15 + Part II chapter 27 (Part II chapters renumbered 16–28), owner manual uk/en (Mailpit, fake Telegram, NADEL_TELEGRAM_API_URL, JobRunr, lease, functions, troubleshooting), leaflet telegram-leaflet.uk (1 page), capture-screens entries (+ monthLabel fix after the first capture). Guide text says 20 messages a second per bot (ADR/README) where the FS says at most 25.
- Orchestrator: `make up` on the 017 branch (pids in scratchpad/review-run.pids), `make guide-shots` (all captured on the second run), `make guide` (5 PDFs, leaflet 1 page). S7 Verify: 25 tests green; prettier docs/guide clean. Screenshot notes: the month picker shows «September 2026» (native month input follows the browser locale); the send dialog shows 0/0/0 because demo invoices are already sent.
- Slice 7 commit a2ac7bf; review round 1 started (1a3a928..a2ac7bf).
- Slice 7 review round 1: PASS. All slice reviews 1–7 PASS → Slice 8 (Feature Testing & Regression) may start.
- S8 done (testing-expert): acceptance-coverage.md maps 54 criteria (51 proved by tests, 2 documentation-only FS 2.9, 1 manual FS 2.10; no gaps); DeliveryAcceptanceTests (10 journeys); e2e specs delivery-invoices (@smoke), telegram-link (@smoke), delivery-payment-message, delivery-announcement, delivery-demo + support/fakeTelegram.ts, support/mailpit.ts. Own specs 5 passed; --grep @smoke 20 passed (scratch DB). RED: backend (announcement limit) and e2e (TelegramChannel mutation) failed as expected; delivery-demo not RED-validated. Orchestrator re-run: DeliveryAcceptanceTests 10 green, typecheck and lint green. e2e specs use the English UI like existing specs; they never reset the fake Telegram server (unique chat ids instead).
- Slice 8 commit ff8ae11; review round 1 started (a2ac7bf..ff8ae11).
- Slice 8 review round 1: PASS. Every slice review PASS. Phase 4 started; full suite (make check) running in the background.
- Phase 4 full suite (make check on ff8ae11): gen, format-check, lint green; backend 3839 tests, 1 failure + 2 errors → stopped before the client tests. Regressions (none in the baseline): `InvoiceDeliveryTests.noAddressAndNoMailboxFailAtOnceWithTheirCodes` (QUEUED instead of FAILED — passes in slice runs, fails in the full suite); `SealImportPlansMigrationTests` and `EncryptMemberContactsMigrationTests` (context at an old migration target: JobRunr storage provider "Not all required tables are available"). Regression fix attempt 1 → spring-backend.
- Regression fix attempt 1 (spring-backend), test setup only: migration tests that boot at an old Flyway target run JobRunr with in-memory storage (`jobrunr.database.type=mem` unless target is latest); `@DirtiesContext` on DeliveryJobTests so its background job server stops (its cached context kept dispatching other tests' jobs and held the lane lease → the QUEUED failure). 5 targeted + 752 module tests green.
- Also found: `make gen` (part of make check) changed `packages/api-client/src/generated/plots/plots.ts` — the committed client was stale against an edited description in api/openapi.yaml (Slice 6). CI's "Generated API client is up to date" step would have failed; the regenerated file goes into the fix commit.
- Regression fix commit 573158f; fix review round 1 started; full suite re-run started.
- Regression fix review round 1: PASS.
- Full suite re-run (573158f): all 3839 backend tests green, but the JaCoCo floor fails: line coverage 0.973 < coverage.minimum 0.977 (~84 lines short). notifications.internal has 155 missed lines (BotWorker 29, BotCommands 26, DeliveryDispatcher 15, BotSupervisor 10, DemoNotifications 10, TelegramClient 8). The floor is never lowered (CLAUDE.md) → regression fix attempt 2: tests for the uncovered 017 code.
- Regression fix attempt 2 (spring-backend): tests only, +117 covered lines; `./mvnw verify` 3887 tests green, JaCoCo 0.97825 ≥ 0.977 (orchestrator confirmed from jacoco.csv); RED on 8 key tests; no production change; floor not raised (margin ~33 lines — owner may raise it).
- Coverage fix commit 35b7ced; fix review started; full make check re-run started.
- Coverage fix review round 1: PASS.
- Full suite (make check on 35b7ced): green — contract/gen, format, lint, backend 3887 tests 0 failures, coverage met, clients typecheck + tests (web 906). No regressions vs baseline; the 3 baseline web timeouts (RunPreviewDialog, RecalculationStep ×2) now pass.
- /awos:verify: 53 criteria [x] (tests per acceptance-coverage.md + Chrome render of the key screens, 5 screenshots in docs/screenshots/017-delivery-email-telegram-*.png), 1 [?] (FS 2.10, the owner's real bot and mailbox check). Status: Implemented — pending manual check (functional spec and technical considerations). Product docs: architecture.md already updated in Slice 7; no /awos:product or /awos:architecture command needed.
- Clean-up: only the main worktree remains. The 017 app is running from `make up` (pid file scratchpad/review-run.pids) for the review run.

## Final report (Phase 4)

- Branch `017-delivery-email-telegram` from main @ 1fef5d0. Commits: b91832e specs · 515ec7b S1 · b811a37 S2 · 346a693 S3 · 3be8723 S4 · 053feb8 S3 review fix · 46121a6 S5 · 1a3a928 S6 · a2ac7bf S7 · ff8ae11 S8 · 573158f regression fix · 35b7ced coverage fix · (this commit) verify.
- Acceptance criteria: 53 of 54 verified; [?] FS 2.10 (owner's real bot and mailbox check, manual by design).
- Reviews: S1 PASS r1 · S2 PASS r1 · S3 MAJOR r1 (dispatcher loop) → PASS r2 · S4–S8 PASS r1 · regression fix PASS · coverage fix PASS. Minor notes in review-log.md.
- Full suite: make check green on 35b7ced (backend 3887 tests, JaCoCo 0.978 ≥ 0.977, clients green); baseline had 3 web timeouts that now pass; regressions found on the way (2 migration tests, 1 order-dependent test, coverage floor, stale generated client) fixed.
- Decided automatically: see every `AUTO:` entry above (Phase 1 and tech decisions) plus the builder choices logged per slice.
- Staffing gap: guides/leaflet ran under general-purpose (no guides specialist). Suggested: `/awos:hire cover 017-delivery-email-telegram: need Telegram Bot API, SMTP/Jakarta Mail, JobRunr`.
- Owner follow-ups: add `NADEL_TELEGRAM_API_URL` to `.env.example` (owner decision); CLAUDE.md `make infra` line (Mailpit, fake Telegram) — protected file; the real bot/mailbox check (FS 2.10) in the PR; optionally raise `coverage.minimum` (now 0.978); SSRF note (SMTP host free text) for a later restriction.
- Phase 5: pushed 017-delivery-email-telegram; PR #61 opened (feat(notifications): delivery by e-mail and Telegram, the cooperative's bot). Waiting for checks.
- CI run 40971589846: Clients, Security, End-to-end green; Backend red — 3 InvoiceDeliveryTests compare an Instant with nanoseconds (Linux runner clock) against the value read back from Postgres (microseconds): `expected …486526872Z but was …486527Z`; macOS clocks give microseconds, so it did not reproduce locally. SAST red — Semgrep `java.lang.security.audit.crypto.unencrypted-socket` (blocking) on `new ServerSocket(0)` used to find a free port in DeliveryTestSupport.java:330, TestEmailTests.java, TelegramClientTests.java. Both are branch failures → CI fix attempt 1 (spring-backend).
- CI fix attempt 1 (spring-backend), test code only: MovableClock starts at a microsecond-truncated instant (RED reproduced on macOS with +789 ns); shared test helper ClosedPort (ServerSocketChannel on loopback) replaces three new ServerSocket(0); local Semgrep 1.178.0: 0 findings on backend/src (3 blocking on the old files); 54 tests green.
- CI fix review round 1: PASS. Pushing.

## Final report addendum (Phase 5 and 6)

- PR #61 "feat(notifications): delivery by e-mail and Telegram, the cooperative's bot": https://github.com/Philatelist/nadel-platform/pull/61
- Checks: run 40971589846 red (Backend: nanosecond vs microsecond timestamps in 3 InvoiceDeliveryTests on Linux; SAST: Semgrep unencrypted-socket on `new ServerSocket(0)` in tests) → CI fix f6d2445 (test code only, fix review PASS) → run 40973703903 all 5 checks green (Backend, Clients, Security, SAST, End-to-end). No re-runs of infrastructure failures were needed.
- Left for the owner: review and merge; `.env.example` line for NADEL_TELEGRAM_API_URL; CLAUDE.md `make infra` line; FS 2.10 real bot/mailbox check in the PR.
- Review run: the spec branch runs locally from `make up` (no runtime code changed since it started at 35b7ced): web http://localhost:5173, API http://localhost:8080, Keycloak http://localhost:8180, Mailpit http://localhost:8025, fake Telegram http://localhost:8091. Logs: scratchpad/review-run.log. Stop: `kill 62851 63013 63122` (scratchpad/review-run.pids), infra `make down`.
