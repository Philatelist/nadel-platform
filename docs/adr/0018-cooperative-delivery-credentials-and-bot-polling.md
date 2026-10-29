# 0018. Delivery: each cooperative's own mailbox and bot, their secrets sealed in its data, long polling with a lease, two cross-tenant discovery functions, a delivery register worked by JobRunr

**Status:** accepted (2026-10); introduced in phase 4 by spec 017 (`context/spec/017-delivery-email-telegram/`).

## Context
Feature 017 sends invoices, payment messages and announcements to members by e-mail and Telegram, and lets members ask
the bot for their debt, the invoice and to pass a meter reading (functional spec §1, §2). Members must see the
cooperative's name, not a third-party service: e-mail comes from the cooperative's own mailbox (often a free one, with
an app password) and Telegram messages from the cooperative's own bot, which its chairman creates with @BotFather.

That gives every cooperative two secrets the platform must keep: the mailbox password and the bot token. The root
`CLAUDE.md` says "secrets come only from environment variables", which fits the platform's own secrets (database,
master key) but not hundreds of cooperatives' credentials that their chairmen enter and change in the settings page.

The bot must also receive what members write. Telegram offers two ways: a webhook (Telegram calls a public HTTPS URL of
ours per bot) or long polling (`getUpdates`, our server asks). Several application instances may run (ADR 0010 plans
one server, but rolling deploys briefly run two), and a cooperative's bot must answer each message exactly once.

The poller has to know which cooperatives have a bot, and a periodic sweep has to know which cooperatives have
messages waiting. Row level security (ADR 0002) shows a transaction only the rows of its bound cooperative, and both
start without one.

Finally, a month's invoices go to hundreds of members through mailboxes and bots that limit how fast they accept
messages, and a send can fail for a moment (a mailbox busy, Telegram's 429) or for good (a wrong password, a member
who blocked the bot). ADR 0007 planned JobRunr for such work.

Questions:

1. Where do the cooperatives' secrets live, and how are they protected?
2. Webhook or long polling, and how does exactly one instance poll a bot?
3. How do the poller and the sweep find their cooperatives without breaking the RLS model?
4. How are messages queued, paced and retried, and what does JobRunr do in it?

## Decision

### Secrets of a cooperative live in its data, sealed with its data key

- The mailbox password and the bot token are stored in the cooperative's row of `delivery_settings`
  (`smtp_password_enc`, `bot_token_enc`, `bytea`), sealed with the cooperative's own data key through
  `privacy.PersonalDataCipher` (ADR 0016) at `FieldRef("delivery_settings", "smtp_password_enc" | "bot_token_enc",
  tenantId)`. They are not personal data, but the mechanism gives exactly what they need: AES-256-GCM bound to the
  cooperative, table, column and row, a key per cooperative under the master key from the environment, rotation with
  the master key, nothing readable in a dump or a backup.
- **This is the one exception to "secrets come only from environment variables"**: secrets that belong to a
  cooperative and are entered by its board are cooperative data. The platform's own secrets (database roles, the
  master key, Keycloak) stay environment-only.
- The secrets are **write-only** in the API: `GET /settings/delivery` returns only `passwordSet` and the token's last
  four characters (`bot_token_last4`, kept in the clear for that purpose; a CHECK keeps it in step with the token). A
  `PUT` with an absent or empty secret keeps the saved one; saving the same secret again changes nothing.
- The audit entry `DELIVERY_SETTINGS_CHANGED` lists a changed secret as the field `password` or `botToken` with
  `sealed = true` and **no values**, so neither the outbox (`event_publication`) nor `audit_entry` ever holds a secret,
  and the history shows only «змінено».
- A secret is opened only to connect: `SmtpSettings` for the mail sender (`MailSenderFactory`, one
  `JavaMailSenderImpl` per cooperative, rebuilt when its mailbox changes) and the token for the Telegram client. The
  token is part of the Bot API URL, so the client never logs a URL or chains an exception that could carry it, and a
  token with characters outside `[A-Za-z0-9:_-]` never reaches a URL. `toString()` of every type that holds a secret
  prints `<set>` instead.
- `settings_version` grows with every new bot token; the bot of the cooperative restarts when it changes.

### Long polling with a lease, not webhooks

- Each cooperative's bot is polled with `getUpdates` (long poll, 25 s) by a `BotWorker` on a virtual thread. No public
  HTTPS endpoint per bot, no webhook secret to manage, nothing to register with Telegram when a token changes, and the
  same code runs locally against the fake Bot API (`infra/fake-telegram`) and in tests against an in-JVM fake.
- **Exactly one poller per bot across instances:** `BotSupervisor` (every 20 s on every instance) claims or renews the
  cooperative's row in `bot_poll_state` with `UPDATE … WHERE lease_owner = :me OR lease_owner IS NULL OR lease_until <
  now()` (lease 60 s, database time). Only the holder polls; another instance takes over after the lease runs out, a
  clean shutdown gives the lease up at once.
- **Take before answer: each update is answered at most once.** Before the worker acts on an update it moves
  `last_update_id` past it in the same row (`BotPollState.take`), conditionally on still holding the lease of the same
  settings version; an instance that lost the lease stops without answering. A crash between the take and the answer
  loses that one reply (the member taps again), but nothing a member asked for — a submitted meter reading above all —
  ever happens twice. At least once would risk duplicate readings; at most once was chosen.
- The dialogue state (which plot, which meter) lives in memory on the lease holder, ids only; after a take-over the
  member starts the menu again.
- A 401 from Telegram stops the worker until the token changes (`settings_version`). When the Bot API cannot be
  reached the worker retries after a growing pause (1–30 s), so a bot outage, or the fake server missing locally,
  never stops the application.
- **The Bot API's address is configuration:** `nadel.telegram.api-url` from `NADEL_TELEGRAM_API_URL`, defaulting to
  `https://api.telegram.org`, and in the `dev` profile to `http://localhost:8091` (the fake Bot API of
  `infra/fake-telegram`), so a local run works with the variable unset and never reaches Telegram; tests point it at an
  in-JVM fake. To try a real bot locally, set the variable to the real URL.

### Two cross-tenant discovery functions, identifiers only

- `notifications_bot_tenants()` (V29) returns the ids of the cooperatives that have a bot token (for the bot
  supervisor). `notifications_due_lanes(timestamptz)` (V30_1) returns the `(tenant_id, channel)` pairs that have
  e-mail or Telegram deliveries due at the given time (for the delivery sweep, which wakes those lanes every minute so
  nothing waits after a restart or a lost job).
- Both are `SECURITY DEFINER`, owned by the migration role (`nadel_owner`, which RLS does not apply to because the
  tables are not `FORCE ROW LEVEL SECURITY`), `STABLE`, with a fixed `search_path`, `EXECUTE` revoked from `PUBLIC` and
  granted only to `nadel_app`. They return **identifiers and a channel code only**, never a row, an address or a text:
  every read of a cooperative's data still runs bound to that cooperative (`TenantContext.runAs`) under RLS.
- They are the only functions of the platform that cross cooperatives (`BotTenantsFunctionTests` checks the first's
  shape, that it works without a bound cooperative and its grants).

### A delivery register worked by JobRunr, with a lease per lane

- **Every message is a row** of `delivery` (V29): kind (`INVOICE`, `PAYMENT`, `REVERSAL`, `ANNOUNCEMENT`), member,
  channel, status (`QUEUED`, `SENT`, `FAILED`, `TO_PRINT`, `PRINTED`), the failure code, attempts and the next
  attempt; ids and codes only, never an address, a chat or a text (texts are written at send time from the Ukrainian
  bundle). Unique keys per (document, member, channel) make every enqueue `INSERT … ON CONFLICT DO NOTHING`, so a
  repeated send or a redelivered event adds nothing; a guard trigger lets only the sending state change.
- **A worker per cooperative and channel (a lane).** The JobRunr job `DispatchDeliveries(tenantId, channel)` binds the
  cooperative with `TenantContext.runAs`, claims the lane's lease in `delivery_lane` (60 s, database time; a lane held
  elsewhere is tried again after 5 s), takes due rows 20 at a time in short transactions and sends outside any
  transaction. Three attempts (now, +1 min, +5 min); permanent failures (wrong password, no contact, blocked bot) fail
  at once; Telegram's 429 moves the row by `retry_after` without counting an attempt. A run stops after 60 s and
  enqueues the next.
- **Pacing in memory** (`RateLimiters`): the lane lease makes one instance send a lane, so the limits need no shared
  store: one message a second per chat, 20 a second per bot, the cooperative's `email_rate_per_second` per mailbox.
  OSS JobRunr has no cross-instance rate limiting, which is why the lease, not JobRunr, owns the pace.
- **JobRunr only wakes and recovers.** JobRunr 9.0.1 (`jobrunr-spring-boot-4-starter`, Jackson 3) on the application's
  DataSource as `nadel_app`, with its tables from Flyway `V30__jobrunr.sql` (copied from the jar's PostgreSQL
  migrations of that version; `jobrunr.database.skip-create=true`, since `nadel_app` cannot create tables), dashboard
  off, no usage data sent. Its tables have **no `tenant_id` and no RLS**, like `event_publication` (ADR 0007): the
  background job server reads them without a bound cooperative, and job arguments are a cooperative id and a channel
  only. Job ids come from the lane and the second, so a lane woken twice gets one job; JobRunr retries a crashed run
  (2 retries), the delivery attempts are counted on the rows.
- **Reading decisions are not deliveries.** The board's approval or rejection of a reading submitted in the bot is
  answered in the bot's conversation, sent directly through the Telegram client at the bot's pace, without a delivery
  row: `delivery.kind` has no such kind and a row would need a migration for no gain. One message per (reading,
  decision) on the instance; a crash between the send and the outbox's completion could repeat it.

### The mailbox host is free text

- The chairman types the SMTP host and port, and the test letter connects to them from the server. An admin with
  `SETTINGS_MANAGE` can therefore make the server open an SMTP connection to an internal host (a server-side request
  forgery limited to SMTP and to the cooperative's own chairman, who sees only the server's reply line). Accepted for
  now: only the cooperative's chairman holds the permission, the connection has 10 s timeouts and the answer carries
  only a failure code and the server's last reply. A later restriction (refusing private and loopback addresses outside
  the `dev` profile, or an allow-list of ports) remains possible without a contract change.

## Consequences
- A cooperative configures its own channels with no platform operator involved; the operator never sees them (ADR
  0008). A leaked database without the master key exposes no mailbox or bot.
- The platform does not run a mail service: deliverability and daily limits are those of the cooperative's mailbox.
  The sending rate (`email_rate_per_second`, 1–10, default 1) protects free mailboxes from being blocked.
- Losing the master key loses the secrets with the contacts; the chairman enters them again.
- Polling costs one open HTTPS request per cooperative bot; at the planned scale (hundreds of cooperatives) that is
  within one server's means on virtual threads.
- The two discovery functions are a deliberate, tested exception to "RLS everywhere"; any further one needs its own
  ADR.
- A month's send returns at once; the board watches the delivery screen fill. A failed message is visible with its
  reason and can be sent again; nothing is sent twice by a retry of the platform itself.
- JobRunr's tables join `event_publication` as infrastructure tables without RLS.
- Locally, Mailpit and the fake Bot API (`infra/docker-compose.yml`) receive everything; the demo cooperative is set up
  for them by the `dev` profile's demo data (`DemoNotifications`).

## Alternatives considered
- **Secrets in a vault or the environment per cooperative:** an operator would have to enter each cooperative's
  password; no self-service, and the platform has no vault (ADR 0010).
- **A platform mailbox or transactional provider (Postmark, SES) with the cooperative as reply-to:** members would see
  a third-party sender, which the feature rules out; `context/product/architecture.md` is updated accordingly.
- **Webhooks:** need a public URL and certificate per environment, a secret per bot and re-registration on every token
  change, and do not work locally without a tunnel.
- **Reading `delivery_settings` and `delivery` as the owner role from the application:** would give the application a
  second database role that bypasses RLS; two narrow functions returning ids are smaller.
- **Answering an update before saving its offset (at least once):** a crash could submit a meter reading twice.
- **One JobRunr job per message, with JobRunr's retries:** no pacing across messages of one mailbox or bot and no
  register to show the board; the register with a lane lease gives both.
- **`@Scheduled` polling of the register without JobRunr:** kept as the fallback had JobRunr not started on Spring Boot
  4.1; JobRunr adds immediate wake-ups after a send and recovery of crashed runs.

## Non-goals
- Viber delivery (a later feature; such members get paper with a note).
- A platform-wide fallback mailbox or bot.
