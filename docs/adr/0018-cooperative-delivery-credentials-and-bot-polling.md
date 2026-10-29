# 0018. Delivery: each cooperative's own mailbox and bot, their secrets sealed in its data, long polling with a lease, one cross-tenant discovery function

**Status:** proposed (draft); introduced in phase 4 by spec 017 (`context/spec/017-delivery-email-telegram/`). Slice 1
delivers the settings and the secrets; the bot polling and the discovery function are used from slice 2. The draft is
finished in slice 7.

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

Finally, the poller has to know which cooperatives have a bot. Row level security (ADR 0002) shows a transaction only
the rows of its bound cooperative, and the poller starts without one.

Questions:

1. Where do the cooperatives' secrets live, and how are they protected?
2. Webhook or long polling, and how does exactly one instance poll a bot?
3. How does the poller find the cooperatives with a bot without breaking the RLS model?

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
  cooperative's row in `bot_poll_state` with `UPDATE … WHERE lease_owner = :me OR lease_until < now()` (lease 60 s).
  Only the holder polls; another instance takes over after the lease runs out. The update offset is saved in the same
  row after each update (before a reading is submitted), so a takeover neither loses nor repeats an update.
- A 401 from Telegram stops the worker until the token changes (`settings_version`).

### One cross-tenant discovery function

- `notifications_bot_tenants()` (V29) returns the ids of the cooperatives that have a bot token. It is
  `SECURITY DEFINER`, owned by the migration role (`nadel_owner`, which RLS does not apply to because the tables are
  not `FORCE ROW LEVEL SECURITY`), with a fixed `search_path`, `EXECUTE` granted only to `nadel_app`. It returns
  **identifiers only**: every read of a cooperative's data still runs bound to that cooperative
  (`TenantContext.runAs`) under RLS.
- It is the only function of the platform that crosses cooperatives; a test checks its shape (ids only), that it works
  without a bound cooperative, and that no other function has such a grant.

## Consequences
- A cooperative configures its own channels with no platform operator involved; the operator never sees them (ADR
  0008). A leaked database without the master key exposes no mailbox or bot.
- The platform does not run a mail service: deliverability and daily limits are those of the cooperative's mailbox.
  The sending rate (`email_rate_per_second`, 1–10, default 1) protects free mailboxes from being blocked.
- Losing the master key loses the secrets with the contacts; the chairman enters them again.
- Polling costs one open HTTPS request per cooperative bot; at the planned scale (hundreds of cooperatives) that is
  within one server's means on virtual threads.
- The discovery function is a deliberate, tested exception to "RLS everywhere"; any second one needs its own ADR.

## Alternatives considered
- **Secrets in a vault or the environment per cooperative:** an operator would have to enter each cooperative's
  password; no self-service, and the platform has no vault (ADR 0010).
- **A platform mailbox or transactional provider (Postmark, SES) with the cooperative as reply-to:** members would see
  a third-party sender, which the feature rules out; `context/product/architecture.md` is updated accordingly.
- **Webhooks:** need a public URL and certificate per environment, a secret per bot and re-registration on every token
  change, and do not work locally without a tunnel.
- **Reading `delivery_settings` as the owner role from the application:** would give the application a second database
  role that bypasses RLS; the one narrow function is smaller.

## Non-goals
- Viber delivery (a later feature; such members get paper with a note).
- A platform-wide fallback mailbox or bot.
