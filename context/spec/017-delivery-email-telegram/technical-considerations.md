# Technical Specification: Delivery by e-mail and Telegram, the cooperative's bot

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Implemented — pending manual check
- **Author(s):** Alex Usov
- **Inputs:** `docs/features/017-delivery-email-telegram.md`, ADR 0002, 0007, 0009, 0012, 0016, 0017; design input from
  the spring-backend, postgres-database, react-frontend and devops-infra specialists (pipeline run, 2026-10-29).

---

## 1. High-Level Technical Approach

A new Spring Modulith module **`notifications`** owns delivery settings, Telegram links and link codes, delivery rows,
the sending worker, the bot (long polling) and announcements. It exposes no API for other modules: it **listens** to
domain events (payments, reading decisions) and **implements SPIs** owned by `members` (Telegram link status on the
member card) and `documents` (per-recipient paper copies with a link QR). It reads other modules only through their
public facades (`MemberDirectory`, `DocumentsFacade`, `LedgerReadModel`, `PlotCatalog`, `PlotOwners`,
`OwnedPlotsSource`, the new `meters.MemberReadings`).

- **E-mail:** Spring Boot mail (`spring-boot-starter-mail`, Jakarta Mail); one `JavaMailSenderImpl` per cooperative
  built from its stored settings.
- **Telegram:** a small client on the JDK `HttpClient` against a configurable Bot API base URL; no third-party Telegram
  library.
- **Background work:** JobRunr (planned for 017 by ADR 0007) with PostgreSQL storage, driving a **delivery-row worker**
  per (cooperative, channel). The delivery rows themselves hold attempts and the next attempt time.
- **Bot updates:** long polling (`getUpdates`), one worker per cooperative across all application instances, guarded by
  a lease row.
- **Secrets of the cooperative** (SMTP password, bot token) are sealed with the cooperative's data key through
  `privacy.PersonalDataCipher` (ADR 0016). **ADR 0018** records this exception to "secrets only from environment", the
  long-polling design and the one cross-tenant discovery function.
- **Contract first:** a new `notifications` tag in `api/openapi.yaml`, then `pnpm gen:api`.
- **Web:** a «Розсилка» panel in Settings, a «Сповіщення» panel in the account, a deliveries screen, a send-invoices
  dialog, announcement pages, the Telegram flag on the member card.
- **Local/e2e:** Mailpit and a dependency-free fake Telegram Bot API (Node) join `infra/docker-compose.yml`.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module layout (`backend/src/main/java/app/nadel/notifications`)

| Package / class | Responsibility |
|---|---|
| `package-info.java`, `README.md` | module declaration; allowed dependencies: `members`, `documents`, `ledger`, `meters`, `plots`, `identity`, `privacy`, `audit`, `tenancy`, `tariffs`, `money`, `settings` (read only if needed) |
| `internal/DeliverySettingsService` (+ controller) | GET/PUT settings, secrets sealed as `FieldRef("delivery_settings","smtp_password_enc",tenantId)` / `("delivery_settings","bot_token_enc",tenantId)`; an empty secret on PUT keeps the stored one; responses carry `passwordSet` and `tokenLast4`; `settings_version` bumped when the token changes; audit `DELIVERY_SETTINGS_CHANGED` with `sealed=true` for secret fields |
| `internal/ChannelResolver` | pure function `resolve(preferred, hasEmail, telegramLinked, mailboxReady, botReady) → Resolution(channel EMAIL/TELEGRAM/PAPER, note VIBER_UNSUPPORTED?)`; one unit test per row of functional spec 2.3 |
| `internal/LinkCodes` | 128-bit `SecureRandom`, base64url (22 chars), SHA-256 hash stored; purpose `ACCOUNT` (30 min) or `PAPER` (90 days); redeemed by a conditional update (`used_at is null and expires_at > now()`) |
| `internal/TelegramLinks` | link/unlink/move; chat id sealed `FieldRef("telegram_link","chat_id_enc",linkId)` plus `blindIndex(TELEGRAM_CHAT, chatId)`; audit `TELEGRAM_LINKED` / `TELEGRAM_UNLINKED` (no chat id) |
| `internal/DeliveryPlanner` | builds delivery rows (invoices of a month or one invoice, payment/reversal messages, announcements) and the preview counts |
| `internal/DeliveryDispatcher` | JobRunr job `DispatchDeliveries(tenantId, channel)` (see 2.4) |
| `internal/mail/MailSenderFactory`, `MailChannel` | per-cooperative `JavaMailSenderImpl`, cached by `(tenant, settings_version)`; HTML + plain text, PDF attachment; SMTP error → failure code |
| `internal/telegram/TelegramClient`, `TelegramChannel` | `getMe`, `getUpdates`, `sendMessage` (with reply keyboard or inline URL button «Оплатити»), `sendDocument` (hand-built multipart); token never logged, URL redacted in exceptions; 403 → `TELEGRAM_BLOCKED` + unlink; 429 → wait `retry_after` |
| `internal/bot/BotSupervisor`, `BotWorker`, `BotDialogue`, `BotCommands` | lease, polling loop, dialogue state machine, command handlers (2.5) |
| `internal/RateLimiters` | token buckets: inbound 20/min per chat; outbound 1/s per chat and ~20/s per bot; e-mail per cooperative `email_rate_per_second` |
| `internal/Announcements` (+ controller) | preview, send, list, detail |
| `internal/NotificationTexts` | Ukrainian texts from `backend/src/main/resources/notifications/messages_uk.properties` (pattern of `documents/messages_uk.properties`, ADR 0012) |
| `internal/PaperCopiesProvider` | implements `documents.PaperRecipients` |
| `internal/TelegramLinkStatusProvider` | implements `members.TelegramLinkStatus` |
| `internal/PaymentMessages`, `internal/ReadingMessages` | `@Async @TransactionalEventListener` for `PaymentRecorded`, `PaymentReversed`, `ReadingDecided`, binding `TenantContext.runAs` before a `TransactionTemplate` (ADR 0007 pattern); idempotent via unique keys |

### 2.2 Ports and events added to other modules

| Module | Addition | Notes |
|---|---|---|
| members | `MemberChannels.setPreferredChannel(UUID memberId, DeliveryChannel channel, AccessProfile actor)` | validates EMAIL against the member's e-mail; audit as a member change |
| members | SPI `TelegramLinkStatus { Set<UUID> linkedAmong(Collection<UUID> memberIds); }` | used through `ObjectProvider`, default "none linked"; `Member.telegramLinked` (boolean) on the member card |
| members, imports | drop the TELEGRAM-needs-username rule (`DeliveryChannel.check`, `MemberData.from`, import preview) | username stays optional; 002 spec gets a Change Log entry |
| identity | `AccessProfile.ownerOnly(tenantId, memberId)` | a profile with no permissions, for bot actions as the member |
| meters | `MemberReadings.metersOf(plotId, AccessProfile) → List<MemberMeter(meterId, resource, serial)>`; `submit(plotId, meterId, String value, AccessProfile) → SubmitResult` (sealed: `Accepted(readingId, value, serial)` / `Rejected(List<Problem(code, params)>)`); `decision(readingId) → Optional<ReadingDecisionView(value, serial, status, reason)>` | wraps the existing `ReadingCommands.submit` and the `MeterAccess.ownsNow` rule; same validation codes as the account |
| meters | event `ReadingDecided(tenantId, readingId, plotId, submitterMemberId, status)` | published by approve and reject; reason re-read on demand, never in the outbox |
| ledger | events `PaymentRecorded(tenantId, List<UUID> paymentIds)`, `PaymentReversed(tenantId, List<UUID> paymentIds)`; `LedgerReadModel.paymentsByIds(ids)` | ids only; published next to the existing `PaymentsChanged` |
| documents | `latestInvoices(YearMonth) → List<MonthInvoice(invoiceId, plotId, version, number, due, month)>`; `invoiceSummary(invoiceId) → Optional<InvoiceSummary(invoiceId, plotId, plotNumber, number, month, version, due, qrLink)>`; `invoiceForDay(plotId)` (today's version if one exists, otherwise `invoiceForToday`, under the month lock) | `DocumentFormat` (amount/month formatting) made public for message texts |
| documents | SPI `PaperRecipients { Map<UUID, List<PaperCopy(memberId, label, Optional<String> linkQrPayload)>> copiesFor(YearMonth, Collection<UUID> plotIds); }` | `PrintBundle` prints one copy per entry with an extra small QR «Отримувати рахунки в Telegram»; without an implementation it keeps today's PAPER filter |
| plots | `PlotCatalog.activeStreets() → List<String>`; `GET /plots/streets` | `hasAnyAuthority('PERM_PLOTS_VIEW','PERM_ANNOUNCEMENTS_SEND')` |

The print PDF (`GET …/print.pdf`) now creates `PAPER` link codes for its recipients (only the hash is stored, so a code
must be created when it is printed). This side effect is documented in the documents and notifications READMEs.

### 2.3 Data model (Flyway `V29__delivery.sql`, `V30__jobrunr.sql`)

All new cooperative tables: `tenant_id uuid not null references tenant(id)`, RLS enabled, policy `tenant_isolation`
(USING and WITH CHECK `tenant_id = current_tenant_id()`), index on `tenant_id`, composite FKs `(tenant_id, x)`.

| Table | Key columns | Constraints and indexes |
|---|---|---|
| `delivery_settings` | `tenant_id` PK; `smtp_host`, `smtp_port`, `smtp_security` (STARTTLS/SSL), `smtp_user`, `smtp_from_address`, `smtp_from_name`, `smtp_password_enc bytea`; `bot_token_enc bytea`, `bot_token_last4`, `bot_username`; `email_rate_per_second` (default 1, 1–10); `notify_payments boolean default true`; `settings_version int`; `updated_at` | `(bot_token_enc is null) = (bot_token_last4 is null)` |
| `telegram_link` | `id`, `member_id`, `chat_id_enc bytea`, `chat_id_bidx bytea`, `linked_at`, `ended_at`, `end_reason` (UNLINKED, BLOCKED, MOVED) | partial unique `(tenant_id, member_id) where ended_at is null` and `(tenant_id, chat_id_bidx) where ended_at is null`; update guard: only `ended_at`/`end_reason`, once |
| `telegram_link_code` | `id`, `code_hash bytea`, `member_id`, `purpose` (ACCOUNT, PAPER), `created_at`, `expires_at`, `used_at` | unique `(tenant_id, code_hash)` |
| `announcement` | `id`, `text` (1–2000, user content, not encrypted), `audience jsonb` (`{"streets":[…]}`, empty = all), `channels text[]`, `created_by`, `created_at` | append-only trigger; index `(tenant_id, created_at desc)` |
| `delivery` | `id`, `kind` (INVOICE, PAYMENT, REVERSAL, ANNOUNCEMENT), `invoice_id`, `invoice_version`, `payment_id`, `announcement_id`, `billing_month date`, `member_id`, `channel` (EMAIL, TELEGRAM, PAPER), `note` (VIBER_UNSUPPORTED), `status` (QUEUED, SENT, FAILED, TO_PRINT, PRINTED), `failure_code` (CHECK list), `attempts`, `resends`, `next_attempt_at`, `sent_at`, `created_at` | exactly one reference per kind; TO_PRINT/PRINTED only for PAPER; FAILED needs a code; partial uniques: `(tenant_id, invoice_id, member_id, channel)` for INVOICE, `(tenant_id, payment_id, kind, member_id, channel)` for PAYMENT/REVERSAL, `(tenant_id, announcement_id, member_id, channel)`; index `(tenant_id, status, next_attempt_at) where status='QUEUED'`, `(tenant_id, billing_month, kind)`; guard trigger: only status, failure_code, attempts, resends, next_attempt_at, sent_at change |
| `delivery_lane` | `tenant_id`, `channel`, `lease_owner`, `lease_until` | PK `(tenant_id, channel)` |
| `bot_poll_state` | `tenant_id` PK, `last_update_id bigint`, `lease_owner`, `lease_until`, `settings_version` | — |

Also in V29:
- `notifications_bot_tenants()` — `SECURITY DEFINER`, owned by `nadel_owner`, `SET search_path = pg_catalog, public`,
  returns `setof uuid` of cooperatives with a bot token; `REVOKE ALL FROM PUBLIC`, `GRANT EXECUTE TO nadel_app`. The
  builder checks that `delivery_settings` is not `FORCE ROW LEVEL SECURITY` for the owner (otherwise the function must
  read through a policy that allows the owner).
- `IndexKind.TELEGRAM_CHAT` (Java only).
- Permission `ANNOUNCEMENTS_SEND`: `seed_base_roles` redefined (V28 pattern) and inserted for the chairman role of every
  existing cooperative.
- Grants: `nadel_app` gets SELECT, INSERT, UPDATE on the new tables (DELETE only on `telegram_link_code` for cleanup);
  `announcement` SELECT, INSERT.

`V30__jobrunr.sql`: the PostgreSQL schema of the pinned JobRunr version, copied from the jar
(`org/jobrunr/storage/sql/postgres/migrations/*`), owned by `nadel_owner`, DML granted to `nadel_app`, no RLS (no
cooperative data; job arguments are `tenantId` and `channel` only, like `event_publication`). `jobrunr.database.skip-create=true`.

Delivery rows hold only ids, codes and the month — never addresses, chat ids or message texts. Texts are rebuilt from
the documents and the ledger when sending.

### 2.4 Sending

- **Planning** (`POST /billing-months/{month}/invoices/send`): for each latest invoice of the month (optionally only with
  `due > 0`), each current owner of the plot (`PlotOwners.currentOwnerIds`), resolve the channel and insert a delivery
  row `ON CONFLICT DO NOTHING` (QUEUED, or TO_PRINT for paper). The preview (`preview=true`) runs the same plan without
  writing and returns counts per channel. Audit `INVOICES_SENT` with month and counts (only when something was queued).
- **Dispatch:** after commit, `JobScheduler.enqueue(DispatchDeliveries(tenantId, channel))`. The job binds
  `TenantContext.runAs`, takes the lane lease (`delivery_lane`, 60 s), then loops: read due rows in a short
  transaction, send outside any transaction under the rate limiters, record the result in a short transaction. It works
  at most ~60 s, then reschedules itself at the earliest `next_attempt_at`; a busy lease reschedules in +5 s. A
  one-minute sweep (in `BotSupervisor`'s scheduler) enqueues lanes with due rows, so nothing is lost after a restart.
- **Attempts:** 3 business attempts: now, +1 min, +5 min; then FAILED with the last code. JobRunr's own retries (2) cover
  only crashes. Permanent failures (`TELEGRAM_BLOCKED`, `NO_CONTACT`, `CHANNEL_NOT_CONFIGURED`, `SMTP_AUTH_FAILED`) fail
  at once.
- **Failure codes:** `SMTP_REJECTED`, `SMTP_AUTH_FAILED`, `CONNECTION_FAILED`, `TELEGRAM_BLOCKED`,
  `CHANNEL_NOT_CONFIGURED`, `NO_CONTACT`.
- **Retry** (`POST /deliveries/{id}/retry`): sets QUEUED, `attempts=0`, `resends+1`, enqueues.
- **Print done** (`POST /billing-months/{month}/print-done`): TO_PRINT → PRINTED for the month's invoice rows.
- **Payment messages:** on `PaymentRecorded`/`PaymentReversed`, if `notify_payments`, plan one row per current owner of
  the plot whose resolved channel is EMAIL or TELEGRAM. The text uses `LedgerReadModel.debtsAsOf(today)`; a cash
  payment attaches `DocumentsFacade.receiptPdf`.

### 2.5 The bot

- **Discovery and lease:** `BotSupervisor` (`@Scheduled` every 20 s on every instance) calls
  `notifications_bot_tenants()`, then per tenant under `runAs` claims or renews `bot_poll_state` with
  `UPDATE … WHERE lease_owner = :me OR lease_until < now()` (lease 60 s). A held tenant runs one `BotWorker` on a
  virtual thread: `getUpdates(offset, timeout=25)`; the offset is saved after each update (and before a reading is
  submitted, so a reading is submitted at most once). A changed `settings_version` restarts the worker; a 401 from
  Telegram stops it until the token changes.
- **Dialogue** (`BotDialogue`): states `IDLE → PICK_PLOT → PICK_METER → ENTER_VALUE`, plus `CONFIRM_MOVE` for moving a
  linked chat. State is in memory on the lease holder, keyed by chat, TTL 10 min, holding only `{step, plotId, meterId,
  pendingCodeHash}`; on failover the member taps the menu again.
- **Commands:** `/start <code>` (link), `/start`, `/help`, «Борг» (`debtsAsOf` for `OwnedPlotsSource.currentPlotsOf`,
  charge type names from `ChargeTypeCatalog`), «Рахунок» (`invoiceForDay` + PDF + «Оплатити» URL button), «Показання»
  (`MemberReadings`), «Відключити» and `/stop`. Unlinked chats get the help and the link invitation only.
- **Untrusted input:** only commands, keyboard buttons and dialogue steps are handled; texts over 64 characters are
  refused; 20 messages a minute per chat (one «Забагато повідомлень…» reply, then silence); texts are never logged or
  stored.

### 2.6 API (`api/openapi.yaml`, tag `notifications`)

| Endpoint | Permission | Shape |
|---|---|---|
| `GET /settings/delivery`, `PUT /settings/delivery` | `SETTINGS_MANAGE` | `DeliverySettings { email { host, port, security, user, fromAddress, fromName, passwordSet, configured }, telegram { tokenLast4, botUsername, configured }, emailRatePerSecond, notifyPayments }`; PUT takes the same plus write-only `password`, `botToken` (empty/absent keeps) |
| `POST /settings/delivery/test-email` | `SETTINGS_MANAGE` | `{ accepted, serverReply }` (server reply text from the SMTP server only, no stack trace) |
| `POST /settings/delivery/test-telegram` | `SETTINGS_MANAGE` | `{ ok, botUsername }` or `{ ok: false, code: TOKEN_INVALID }` |
| `GET /me/notifications`, `PUT /me/notifications` | signed-in member | `{ channel, available[], telegramLinked, botAvailable }`; PUT `{ channel }` |
| `POST /me/telegram-link`, `DELETE /me/telegram-link` | signed-in member | POST → `{ deepLink, expiresAt }` |
| `POST /billing-months/{month}/invoices/send?preview=` | `INVOICES_ISSUE` | body `{ includeNothingDue }` → `{ counts { telegram, email, paper }, queued }` |
| `POST /invoices/{id}/send` | `INVOICES_ISSUE` | → counts |
| `GET /deliveries?month&kind&status&channel&page` | `INVOICES_ISSUE` | page of `{ id, kind, month, plotNumber, memberName, channel, status, failureCode, note, attempts, sentAt }` + `summary` per channel |
| `POST /deliveries/{id}/retry` | `INVOICES_ISSUE` | 204 |
| `POST /billing-months/{month}/print-done` | `INVOICES_ISSUE` | `{ printed }` |
| `POST /announcements?preview=`, `GET /announcements`, `GET /announcements/{id}` | `ANNOUNCEMENTS_SEND` | body `{ text (1..2000), streets[], channels[] }` → preview `{ counts, paperMembers[] }`; list/detail with counts and deliveries |
| `GET /plots/streets` | `PLOTS_VIEW` or `ANNOUNCEMENTS_SEND` | `string[]` |
| `Member.telegramLinked` (read-only) | as the member card | boolean |

Errors are Problem Details with a `code` (e.g. `DELIVERY_NOT_CONFIGURED`, `ANNOUNCEMENT_TOO_LONG`,
`DELIVERY_NOT_RETRYABLE`, `TELEGRAM_NOT_CONFIGURED`). `tenant_id` comes only from the token.

### 2.7 Web (`web/`, `packages/shared`)

| Path | Responsibility |
|---|---|
| `web/src/components/settings/DeliverySettingsPanel.tsx` (+ `SmtpForm`, `TelegramBotForm`) | on `SettingsPage` under `SETTINGS_MANAGE`; write-only secrets (placeholder «збережено», «••••1234»); test buttons; rate and «Повідомляти про платежі» |
| `web/src/components/account/NotificationsPanel.tsx` | on `AccountPage`; channel radio from `available`; connect (opens the deep link) / disconnect; refetch on focus |
| `web/src/pages/DeliveriesPage.tsx`, `components/deliveries/*` | route `/deliveries` under `INVOICES_ISSUE`, nav in the documents group; counts table, filters, list, retry, «Роздруковано»; polling while rows are queued |
| `components/invoices/SendInvoicesDialog.tsx` | from `MonthInvoicesPanel`; preview, «Надсилати й рахунки без боргу», send; per-invoice «Надіслати» |
| `web/src/pages/Announcements*.tsx` | `/announcements`, `/announcements/new`, `/announcements/:id` under `ANNOUNCEMENTS_SEND` |
| `components/MemberDetails.tsx`, `MemberForm.tsx` | «Telegram: підключено / не підключено»; username no longer required for TELEGRAM |
| `packages/shared/src/i18n/{uk,en}.ts` | namespaces `deliverySettings`, `notifications`, `deliveries` (`status.*`, `reason.*`, `kind.*`), `announcements`, `telegramLink`; `nav.deliveries`, `nav.announcements`; `permission.ANNOUNCEMENTS_SEND`; audit action labels |

### 2.8 Infra and configuration

- `infra/docker-compose.yml`: **Mailpit** (`axllent/mailpit`, pinned tag verified with `docker pull`; SMTP 1025, UI 8025;
  healthcheck `/mailpit readyz`) and **fake-telegram** (`node:<pinned>-alpine`, mounts `infra/fake-telegram/server.mjs`,
  port 8091, healthcheck on `/control/health`). The e2e job already runs `docker compose up -d --wait`, so CI needs no
  change.
- `infra/fake-telegram/server.mjs` (node:http only): Bot API subset under `/bot<token>/` (`getMe`, `getUpdates` long
  poll with offset, `sendMessage`, `sendDocument` multipart, unknown → 404, blocked chat → 403, token `bad…` → 401)
  plus a control API: `POST /control/updates`, `GET /control/sent?chatId=`, `POST /control/reset`,
  `POST|DELETE /control/block/{chatId}`, `GET /control/health`. It never logs message texts.
- `application.yml`: `nadel.telegram.api-url: ${NADEL_TELEGRAM_API_URL:https://api.telegram.org}` (dev profile default
  `http://localhost:8091`), `nadel.telegram.poll-timeout: 25s`, `nadel.delivery.*` (attempt delays, lease, sweep);
  `jobrunr.background-job-server.enabled=true`, `jobrunr.dashboard.enabled=false`, `jobrunr.database.skip-create=true`.
- `.env.example`, `infra/README.md`, `Makefile` help text updated. CLAUDE.md's `make infra` line is a proposed owner edit.

### 2.9 Dependencies

- `org.springframework.boot:spring-boot-starter-mail` (managed by Boot).
- `org.jobrunr:jobrunr-spring-boot-4-starter` **9.0.1** (published 2026-10-06; newer versions are under two weeks
  old). The builder first checks that it starts with Boot 4.1 and Jackson 3; if it does not, it stops the slice and
  reports (fallback: Spring `@Scheduled` driving the same delivery-row worker, recorded in ADR 0018).
- Testcontainers `GenericContainer` for Mailpit (already available through Testcontainers).

### 2.10 Demo data and docs

- Demo: `notifications/internal/DemoNotifications` (`ApplicationRunner`, dev profile, idempotent, under `runAs`), like
  `DemoDocuments`: demo cooperative SMTP → `localhost:1025` (no auth, no TLS), bot token `123456:DEMO…` against the fake
  server, two linked demo members (fake chat ids), last month's invoices sent (rows SENT/TO_PRINT). The second demo
  cooperative stays unconfigured.
- Docs: ADR 0018; `notifications/README.md`; README updates in members, meters, ledger, documents, plots, privacy (field
  refs, `TELEGRAM_CHAT`), audit (new actions); `context/product/architecture.md` (e-mail per cooperative SMTP, JobRunr in
  code, notifications module); ADR 0007 implementation note; spec 002 Change Log (username rule); both guides (uk/en)
  and `capture-screens.mjs`; the leaflet `docs/guide/pdf/telegram-leaflet-{uk}.pdf` built by the guide tooling.

---

## 3. Impact and Risk Analysis

- **System dependencies:** members (channel rule, card), meters (new port and event), ledger (new events), documents
  (print set per recipient, one-version-per-day, new facade methods), plots (streets), identity (permission, owner-only
  profile), privacy (`IndexKind`), audit (actions), infra/CI (two compose services), JobRunr (new infrastructure).
- **Risks and mitigations:**
  - *Very large scope* → seven vertical slices plus the testing slice; each slice testable alone.
  - *Cross-tenant discovery breaks the RLS model* → one `SECURITY DEFINER` function returning ids only, tested for
    shape and grants, recorded in ADR 0018.
  - *Secrets leak* → write-only API fields, sealed storage, `sealed=true` audit, redacted Telegram URLs, plaintext scan
    tests over tables, outbox, audit, logs and responses.
  - *Duplicate messages* (outbox replay, double click, two instances) → unique keys with `ON CONFLICT DO NOTHING`, lane
    and poll leases, offset saved before side effects.
  - *Rate limits / free mailbox blocked* → per-lane token buckets, 429 `retry_after`, configurable e-mail rate.
  - *JobRunr compatibility with Boot 4.1* → spike at the start of slice 3 with a defined fallback.
  - *Print PDF side effect* (link codes created on GET) → documented; codes are random and unguessable, only hashes kept.
  - *Bot dialogue lost on failover* → acceptable (member taps the menu again); no texts persisted.
  - *Behaviour change of 002/008* (username no longer required) → tests updated, spec Change Log.

---

## 4. Testing Strategy

- **Unit:** `ChannelResolver` (one test per table row), `LinkCodes` (expiry, single use, hash only), `BotDialogue`
  transitions, rate limiters, failure-code mapping, text rendering.
- **Integration (Testcontainers, PostGIS):**
  - Mailpit as `GenericContainer`; assertions through its `/api/v1/messages`; chaos API or a closed port for
    `SMTP_REJECTED` / `CONNECTION_FAILED`; wrong credentials for `SMTP_AUTH_FAILED`.
  - In-JVM fake Bot API (`com.sun.net.httpserver.HttpServer`, test sources) recording `sendMessage`/`sendDocument`,
    serving queued `getUpdates`, returning 403/429/401 on demand.
  - Delivery idempotency, attempts/backoff (clock injected), retry, print done, payment and reversal messages,
    announcements, the bot commands, reading approval/rejection messages, per-chat rate limit, two `BotSupervisor`
    instances with distinct ids (exactly one reply per update; takeover after lease expiry).
  - Isolation tests for every new table; the discovery function returns only uuids; guard triggers.
  - Permissions allowed/denied per endpoint (section 2.6); audit entries per action.
  - Plaintext scans: no secret, token or chat id in new tables, `event_publication`, `audit_entry`, captured logs or API
    responses.
  - Existing suites updated: `ModularityTests`, `BaseRolesMatrixTests`, `SeedBaseRolesTests`, members/import channel
    rule tests, documents print tests (per-recipient copies).
- **Clients:** `pnpm -r typecheck`, `lint`, `test` (i18n catalog parity).
- **End-to-end (Playwright, `web/e2e`):** helpers `support/fakeTelegram.ts` (control API) and `support/mailpit.ts`;
  specs: treasurer sends October invoices and sees sent/failed/to print (`@smoke`); member links Telegram through the
  fake API and gets the invoice; a payment message after a cash payment; chairman sends an announcement to one street;
  delivery settings (test letter in Mailpit, bot check).
- **Manual:** the owner's real bot and mailbox check (functional spec 2.10), recorded in the PR.

---

## Assumptions

- **Assumption:** `notify_payments` and the e-mail rate live in `delivery_settings`, not `cooperative_settings` (one
  owner module; the requirement calls it a cooperative setting, which it still is).
- **Assumption:** the worker is driven by delivery rows with a lane lease; JobRunr only schedules and wakes it (OSS
  JobRunr has no rate limiting across instances).
- **Assumption:** JobRunr 9.0.1 with tables from a Flyway migration; fallback `@Scheduled` if it does not run on Boot 4.1.
- **Assumption:** the fake Telegram server for `make up`/e2e is a Node script in `infra/`; backend tests use an in-JVM
  fake. Ports 8091 (fake Telegram), 1025/8025 (Mailpit).
- **Assumption:** dialogue state is in memory on the lease holder; no table.
- **Assumption:** link codes for paper copies are created when the print PDF is rendered.
- **Assumption:** announcement text is user content, stored unencrypted, never in audit values or logs.
- **Assumption:** the delivery screen lives at `/deliveries` in the documents navigation group.
- **Assumption:** the leaflet is Ukrainian only (members' language); guides are in both languages.
