# notifications

Delivery to members (feature 017, `context/spec/017-delivery-email-telegram/`): e-mail through the cooperative's own
mailbox (SMTP) and Telegram through its own bot. **Draft (slices 1–2):** the channel settings and their checks, the
member's own channel choice, Telegram links and the bot. The delivery register and worker and announcements follow in
later slices and are added here as they land. Design decisions: ADR 0018.

## Public API

None. No module calls `notifications`; it reads other modules through their public APIs, implements their SPIs and
(later) listens to their events. Allowed dependencies (`package-info.java`): `members`, `documents`, `ledger`,
`meters`, `plots`, `identity`, `privacy`, `audit`, `tenancy`, `tariffs`, `money`, `settings`, `problem`. Used now:
`members` (`MemberDirectory`: names, contacts and channels; `MemberChannels`: the member's own channel choice; the SPI
`TelegramLinkStatus` is implemented here by `TelegramLinkStatusProvider`), `tenancy` (`TenantContext`,
`TenantDirectory`: the cooperative's name), `privacy` (`PersonalDataCipher`, `FieldRef`, `IndexKind.TELEGRAM_CHAT`),
`audit` (`AuditEvent`, `FieldChange`, `Actor`), `identity` (`CurrentUser`, `AccessProfile`, `OwnedPlotsSource` for the
plot numbers of the bot's greeting) and `problem`.

## Endpoints (tag `notifications`)

| Endpoint | Operation | Permission |
|---|---|---|
| `GET /settings/delivery` | `getDeliverySettings` | `SETTINGS_MANAGE` |
| `PUT /settings/delivery` | `updateDeliverySettings` | `SETTINGS_MANAGE` |
| `POST /settings/delivery/test-email` | `testDeliveryEmail` | `SETTINGS_MANAGE` |
| `POST /settings/delivery/test-telegram` | `testDeliveryTelegram` | `SETTINGS_MANAGE` |
| `GET /me/notifications` | `getMyNotifications` | signed-in member (itself) |
| `PUT /me/notifications` | `updateMyNotifications` | signed-in member (itself) |
| `POST /me/telegram-link` | `createMyTelegramLink` | signed-in member (itself) |
| `DELETE /me/telegram-link` | `deleteMyTelegramLink` | signed-in member (itself) |

One controller implements the generated `NotificationsApi` (`NotificationsController`). The platform operator has no
permissions and no member, so every endpoint answers it 403 `PERMISSION_DENIED`. The `/me` endpoints act on the member
behind the token only; the request never names a member.

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

## The member's choice (`MemberNotifications`, feature 017 §2.2, §2.3)

- `GET /me/notifications`: the stored channel, `available` (in this order: `EMAIL` when the member has an e-mail and
  the mailbox is configured, `TELEGRAM` when the bot is configured, `PAPER` always), `telegramLinked` and
  `botAvailable`. `PUT` sets a channel of `available` through `members.MemberChannels` (audited there as
  `MEMBER_UPDATED`); another channel is 400 `VALIDATION_FAILED` with `errors[{field: channel, code: INVALID}]`.
- `POST /me/telegram-link`: a 30-minute `ACCOUNT` code and the deep link `https://t.me/<bot>?start=<code>`. The bot's
  @name is `delivery_settings.bot_username` (kept by the bot check); without it `getMe` is asked (outside any
  transaction) and the answer kept. 409 `TELEGRAM_NOT_CONFIGURED` without a bot or when Telegram refuses the token.
- `DELETE /me/telegram-link`: ends the link (`UNLINKED`); 204 also when nothing was linked.

## Telegram links (`telegram_link`, `telegram_link_code`, V29)

- **Codes** (`LinkCodes`): 128 random bits (`SecureRandom`), base64url without padding (22 characters); only the
  SHA-256 hash is stored (unique per cooperative). `ACCOUNT` codes live 30 minutes, `PAPER` codes (printed on paper
  invoices, slice 3) 90 days. A code is used up by a conditional update (`used_at is null and expires_at > now`), so
  two chats racing with one code link once. Times come from the injected `Clock`.
- **Links** (`TelegramLinks`): the chat id is sealed at `FieldRef("telegram_link", "chat_id_enc", linkId)` and found
  by its blind index `IndexKind.TELEGRAM_CHAT` of the decimal chat id (`chat_id_bidx`); it never appears in the clear
  in a table, the outbox, the audit log or a log. One active link per member and per chat (partial unique indexes). A
  link ends once, with `end_reason` `UNLINKED` (the member, in the bot or the account), `BLOCKED` (Telegram answered
  403 to a message) or `MOVED`. A chat linked to member A that opens B's code is asked «Цей Telegram уже підключено до
  іншого члена кооперативу. Це ви, {ім'я B}?»; only «Так, це я» uses the code and moves the chat (A's link ends
  `MOVED`); «Ні» changes nothing and leaves the code unused. A member who links a new chat ends their old link
  `MOVED` too (not audited: they stay linked). An archived member's code is invalid.
- **Audit**: `TELEGRAM_LINKED` and `TELEGRAM_UNLINKED` (field `endReason`) of the member (entity type `MEMBER`), the
  author the member (bot actions run as `AccessProfile.ownerOnly`) or the account's caller. Never a chat id.
- **Member card**: `TelegramLinkStatusProvider` implements `members.TelegramLinkStatus`, so the card shows only
  `telegramLinked`.

## The bot (feature 017 §2.5, ADR 0018)

- **One worker per bot across instances.** `BotSupervisor.tick` runs every `nadel.telegram.supervisor-interval` (20 s,
  `BotSchedule`, `@Scheduled`, off with `nadel.telegram.bot-enabled=false`). It lists the cooperatives with a bot
  through `notifications_bot_tenants()` in a transaction without a cooperative, then per cooperative under
  `TenantContext.runAs` opens the token and claims or renews the lease in `bot_poll_state`
  (`update … where lease_owner = :me or lease_owner is null or lease_until < now()`, `nadel.telegram.lease`, 60 s,
  database time). The instance id is random per JVM. A held bot runs one `BotWorker`; a changed `settings_version`
  (new token) restarts it from offset 0; a bot held elsewhere or without a token is stopped here; a 401 from Telegram
  stops the worker until the token changes. A clean shutdown releases the leases (`@PreDestroy`); after a crash the
  other instance takes over once the lease ran out.
- **Worker.** `BotWorker` runs on a virtual thread: `getUpdates(offset, timeout = nadel.telegram.poll-timeout)` (25 s,
  messages only), then per update `BotPollState.take` moves `last_update_id` past it only while this instance holds
  the lease of this settings version — so an update is answered at most once, and before anything it causes (a reading
  submitted in slice 5). An instance that lost the lease stops. Other failures retry after 1–30 s (a 429 waits
  `retry_after`). A stop interrupts only the long poll or a pause, never a database call.
- **Conversation** (`BotConversation`, texts from `messages_uk.properties`): `/start <code>` links (greeting «Вітаємо,
  {ім'я}! Тепер рахунки за ділянку {номери} приходитимуть сюди.» with the menu «Борг», «Рахунок», «Показання»,
  «Відключити»; «Посилання недійсне, отримайте нове в кабінеті або в правлінні» for an unknown, used, expired or
  malformed code); `/start` and `/help` the help and the menu; «Відключити» and `/stop` unlink; «Борг», «Рахунок»,
  «Показання» answer «Ця дія з'явиться незабаром» until slice 5; anything else the help. An unlinked chat gets only the
  invitation to link through the account or the paper QR code, never data. Only private chats are answered.
- **Untrusted input.** At most 20 messages a minute per chat (`BotInputLimiter`: message 21 of a chat's minute gets
  «Забагато повідомлень, спробуйте за хвилину», the rest of the minute is ignored); a text over 64 characters gets
  «Повідомлення задовге…». Message texts and chat ids are never logged or stored; logs carry the cooperative id and an
  outcome.
- **Dialogue** (`BotDialogue`): in memory on the lease holder, per cooperative and chat, 10 minutes after the last
  step; steps `IDLE` and `CONFIRM_MOVE` (holding only the hash of the pending code); slice 5 adds `PICK_PLOT`,
  `PICK_METER`, `ENTER_VALUE`. After a take-over the member taps the menu again.
- **403 on a reply** (the member blocked the bot) ends the chat's link as `BLOCKED`.

## Mail and Telegram clients

- `internal.mail.MailSenderFactory`: one `JavaMailSenderImpl` per cooperative, kept while its mailbox is unchanged
  and rebuilt when any value (the password included) differs. Connect, read and write timeout
  `nadel.delivery.smtp-timeout` (10 s). `STARTTLS` is required when chosen (no fall back to plain text), `SSL` is TLS
  from the start; both check the server certificate's host name. There is no application-wide mail server
  (`spring.mail.*` stays unset).
- `internal.telegram.TelegramClient`: the JDK `HttpClient` against `nadel.telegram.api-url`, `POST
  <api-url>/bot<token>/<method>` with a JSON body; `getMe`, `getUpdates` (long poll, `TelegramUpdate`) and
  `sendMessage` (with a `ReplyMarkup`: a reply keyboard or its removal); `sendDocument` is added in slice 3. Errors are `TelegramException` with a `Kind` (`UNAUTHORIZED` 401/404, `FORBIDDEN` 403,
  `TOO_MANY_REQUESTS` 429 with `retryAfter`, `REJECTED`, `UNAVAILABLE`); their message names the method and status
  only and no cause is chained, so the token (part of the URL) never reaches a log.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `nadel.telegram.api-url` | `${NADEL_TELEGRAM_API_URL:https://api.telegram.org}`; dev profile `${NADEL_TELEGRAM_API_URL:http://localhost:8091}` | Works with `NADEL_TELEGRAM_API_URL` unset in both: production talks to Telegram, `make up` to the fake server of `infra/fake-telegram`; tests set the in-JVM fake |
| `nadel.telegram.connect-timeout`, `read-timeout` | 5 s, 15 s | Ordinary Bot API calls |
| `nadel.telegram.bot-enabled` | true | Whether this instance polls the bots on a schedule; the tests set false and drive `BotSupervisor` themselves |
| `nadel.telegram.poll-timeout` | 25 s | The long poll's `timeout` (whole seconds); tests 1 s |
| `nadel.telegram.supervisor-interval` | 20 s | How often the supervisor claims leases and starts or stops workers |
| `nadel.telegram.lease` | 60 s | How long a bot lease lasts without renewal |
| `nadel.delivery.smtp-timeout` | 10 s | Connect, read and write timeout of a mailbox |

## Tests

`DeliverySettingsTests`, `TestEmailTests`, `TestTelegramTests`, `DeliverySettingsAccessTests` (plus the V29 table tests
`NotificationsTenantIsolationTests`, `NotificationsGuardsTests`, `BotTenantsFunctionTests`); slice 2: `LinkCodesTests`,
`TelegramLinkingTests`, `BotInputTests`, `BotSingleWorkerTests` (two supervisors with their own instance ids over one
database), `MeNotificationsTests`. No test reaches Telegram or a real mailbox: `app.nadel.notifications.FakeBotApi`
(test sources) is an in-JVM Bot API on a random port that every Spring test context points `nadel.telegram.api-url` at
(`IntegrationTest`, which also turns the schedule off and sets a 1 s poll), with a long-poll `getUpdates` over updates
a test pushes per token, a recorded `sendMessage` (403 for a chat that blocked the bot), handlers per method, one-shot
failures (403, 429) and a call record; `app.nadel.notifications.Mailpit` is Mailpit (`axllent/mailpit:v1.27.8`, as in
`infra/docker-compose.yml`) in a Testcontainers `GenericContainer` with SMTP sign-in, read through `/api/v1/messages`.

## Things to remember

- Never log an address, a token, a password, a chat id or a message text. Logs carry the cooperative id and codes.
- Network calls run outside transactions; database work in short transactions bound to the cooperative.
