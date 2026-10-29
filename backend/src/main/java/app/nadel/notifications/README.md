# notifications

Delivery to members (feature 017, `context/spec/017-delivery-email-telegram/`): e-mail through the cooperative's own
mailbox (SMTP) and Telegram through its own bot. **First draft (slice 1):** the channel settings and their checks. The
Telegram links, the delivery register and worker, the bot and announcements follow in later slices and are added here
as they land. Design decisions: ADR 0018.

## Public API

None. No module calls `notifications`; it reads other modules through their public APIs and (later) listens to their
events and implements their SPIs. Allowed dependencies (`package-info.java`): `members`, `documents`, `ledger`,
`meters`, `plots`, `identity`, `privacy`, `audit`, `tenancy`, `tariffs`, `money`, `settings`, `problem`. Used now:
`members` (`MemberDirectory#contacts`: the signed-in member's own e-mail for the test letter), `tenancy`
(`TenantContext`, `TenantDirectory`: the cooperative's name), `privacy` (`PersonalDataCipher`, `FieldRef`), `audit`
(`AuditEvent`, `FieldChange`, `Actor`), `identity` (`CurrentUser`, `AccessProfile`) and `problem`.

## Endpoints (tag `notifications`)

| Endpoint | Operation | Permission |
|---|---|---|
| `GET /settings/delivery` | `getDeliverySettings` | `SETTINGS_MANAGE` |
| `PUT /settings/delivery` | `updateDeliverySettings` | `SETTINGS_MANAGE` |
| `POST /settings/delivery/test-email` | `testDeliveryEmail` | `SETTINGS_MANAGE` |
| `POST /settings/delivery/test-telegram` | `testDeliveryTelegram` | `SETTINGS_MANAGE` |

The platform operator has no permissions, so every endpoint answers it 403 `PERMISSION_DENIED`.

## Delivery settings (`delivery_settings`, V29)

One row per cooperative (the primary key is the cooperative; RLS `tenant_isolation`); a cooperative without a row has
no mailbox, no bot, `email_rate_per_second` 1 and `notify_payments` true. `DeliverySettingsService` reads it and changes
it under a row lock (`select … for update`; the first save inserts the default row with `on conflict do nothing`).

- **Mailbox:** `smtp_host`, `smtp_port`, `smtp_security` (`STARTTLS`, `SSL` or null for none, local test servers
  only), `smtp_user`, `smtp_from_address`, `smtp_from_name`, `smtp_password_enc`. A mailbox is *configured* when the
  host, the port and the sender address are set. `PUT` with `email` replaces every mailbox field (absent or blank
  clears it) except the password; clearing the user removes the password.
- **Bot:** `bot_token_enc`, `bot_token_last4` (shown as «••••1234»), `bot_username` (the @name `getMe` reported; kept
  by `testDeliveryTelegram`, forgotten when the token changes; it only stays when the token it was asked with is still
  the saved one, by `settings_version`). *Configured* when a token is saved. `settings_version` grows with every new
  token, so the bot restarts with it (slice 2).
- **Secrets:** sealed with the cooperative's data key at `FieldRef("delivery_settings", "smtp_password_enc",
  tenantId)` and `FieldRef("delivery_settings", "bot_token_enc", tenantId)`. Write-only in the API (`passwordSet`,
  `tokenLast4`); an absent or empty secret in a `PUT` keeps the saved one, the same secret again is no change. Opened
  only to connect (`SmtpSettings`, `BotToken`, whose `toString()` never prints them).
- **Audit:** a change publishes `DELIVERY_SETTINGS_CHANGED` (entity type `DELIVERY_SETTINGS`, entity id the
  cooperative, label `delivery-settings`) in the changing transaction, with old and new values of `host`, `port`,
  `security`, `user`, `fromAddress`, `fromName`, `emailRatePerSecond`, `notifyPayments`; a changed secret is the field
  `password` or `botToken` with `sealed = true` and no values. Saving the current values records nothing.

## Checks

`DeliveryChecks` reads what it needs in a short read-only transaction and talks to the network outside any
transaction.

- **Test letter** (`testDeliveryEmail`): to the signed-in member's own e-mail (409 `NO_OWN_EMAIL` without one; 409
  `DELIVERY_NOT_CONFIGURED` without a mailbox). Subject and text from `notifications/messages_uk.properties`
  (`NotificationTexts`, Ukrainian only, ADR 0012). `TestLetters` connects and sends itself, so the answer carries the
  server's own last reply (`serverReply`) and a `failureCode`: `SMTP_AUTH_FAILED` (sign-in refused, with the server's
  reply), `SMTP_REJECTED` (letter refused, with the server's reply when it sent one), `CONNECTION_FAILED` (no
  connection, timeout, unknown host, TLS failure; no reply). Never a stack trace or a library message.
- **Bot check** (`testDeliveryTelegram`): `getMe` with the saved token (409 `DELIVERY_NOT_CONFIGURED` without one);
  `{ok, botUsername}`, `TOKEN_INVALID` for 401/404, `TELEGRAM_UNAVAILABLE` otherwise.

## Mail and Telegram clients

- `internal.mail.MailSenderFactory`: one `JavaMailSenderImpl` per cooperative, kept while its mailbox is unchanged
  and rebuilt when any value (the password included) differs. Connect, read and write timeout
  `nadel.delivery.smtp-timeout` (10 s). `STARTTLS` is required when chosen (no fall back to plain text), `SSL` is TLS
  from the start; both check the server certificate's host name. There is no application-wide mail server
  (`spring.mail.*` stays unset).
- `internal.telegram.TelegramClient`: the JDK `HttpClient` against `nadel.telegram.api-url`, `POST
  <api-url>/bot<token>/<method>` with a JSON body; `getMe` now, `getUpdates`, `sendMessage` and `sendDocument` are
  added over the same `call`. Errors are `TelegramException` with a `Kind` (`UNAUTHORIZED` 401/404, `FORBIDDEN` 403,
  `TOO_MANY_REQUESTS` 429 with `retryAfter`, `REJECTED`, `UNAVAILABLE`); their message names the method and status
  only and no cause is chained, so the token (part of the URL) never reaches a log.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `nadel.telegram.api-url` | `${NADEL_TELEGRAM_API_URL:https://api.telegram.org}`; dev profile `${NADEL_TELEGRAM_API_URL:http://localhost:8091}` | Works with `NADEL_TELEGRAM_API_URL` unset in both: production talks to Telegram, `make up` to the fake server of `infra/fake-telegram`; tests set the in-JVM fake |
| `nadel.telegram.connect-timeout`, `read-timeout` | 5 s, 15 s | Ordinary Bot API calls |
| `nadel.delivery.smtp-timeout` | 10 s | Connect, read and write timeout of a mailbox |

## Tests

`DeliverySettingsTests`, `TestEmailTests`, `TestTelegramTests`, `DeliverySettingsAccessTests` (plus the V29 table tests
`NotificationsTenantIsolationTests`, `NotificationsGuardsTests`, `BotTenantsFunctionTests`). No test reaches Telegram
or a real mailbox: `app.nadel.notifications.FakeBotApi` (test sources) is an in-JVM Bot API on a random port that every
Spring test context points `nadel.telegram.api-url` at (`IntegrationTest`), with handlers per method, one-shot
failures (403, 429) and a call record; `app.nadel.notifications.Mailpit` is Mailpit (`axllent/mailpit:v1.27.8`, as in
`infra/docker-compose.yml`) in a Testcontainers `GenericContainer` with SMTP sign-in, read through `/api/v1/messages`.

## Things to remember

- Never log an address, a token, a password, a chat id or a message text. Logs carry the cooperative id and codes.
- Network calls run outside transactions; database work in short transactions bound to the cooperative.
