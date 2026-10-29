# notifications

Delivery to members (feature 017, `context/spec/017-delivery-email-telegram/`): e-mail through the cooperative's own
mailbox (SMTP) and Telegram through its own bot. **Draft (slices 1–4):** the channel settings and their checks, the
member's own channel choice, Telegram links and the bot, sending the month's invoices with the delivery register and
worker, the paper copies with a link QR code, and the payment and reversal messages. The bot's commands and
announcements follow in later slices and are added here as they land. Design decisions: ADR 0018.

## Public API

None. No module calls `notifications`; it reads other modules through their public APIs, implements their SPIs and
listens to their events. Allowed dependencies (`package-info.java`): `members`, `documents`, `ledger`,
`meters`, `plots`, `identity`, `privacy`, `audit`, `tenancy`, `tariffs`, `money`, `settings`, `problem`. Used now:
`members` (`MemberDirectory`: names, contacts and channels; `MemberChannels`: the member's own channel choice; the SPI
`TelegramLinkStatus` is implemented here by `TelegramLinkStatusProvider`), `documents` (`DocumentsFacade`:
`latestInvoices`, `invoiceSummary`, `invoicePdf`; `DocumentFormats` for the texts; the SPI `PaperRecipients` is
implemented here by `PaperCopiesProvider`), `plots` (`PlotOwners`: the current owners of a plot; `PlotCatalog`: the plot number of a payment message), `ledger`
(the events `PaymentRecorded` and `PaymentReversed`; `LedgerReadModel.paymentsByIds` and `debtsAsOf` for the payment
messages; `DocumentsFacade.receiptPdf` for the cash receipt), `tenancy` (`TenantContext`,
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
| `POST /billing-months/{month}/invoices/send?preview=` | `sendMonthInvoices` | `INVOICES_ISSUE` |
| `POST /invoices/{id}/send` | `sendInvoice` | `INVOICES_ISSUE` |
| `GET /deliveries?month&kind&status&channel&page&size` | `listDeliveries` | `INVOICES_ISSUE` |
| `POST /deliveries/{id}/retry` | `retryDelivery` | `INVOICES_ISSUE` |
| `POST /billing-months/{month}/print-done` | `markMonthPrinted` | `INVOICES_ISSUE` |

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

## Sending invoices (feature 017 §2.3, §2.4)

- **The channel rule** (`ChannelResolver`, pure; `RecipientChannels` reads what it needs): Telegram when chosen, linked
  and the bot is set up; e-mail when chosen (or Telegram chosen without a link or a bot) with an e-mail and a mailbox;
  otherwise paper. Viber is paper with the note `VIBER_UNSUPPORTED`.
- **Planning** (`DeliveryPlanner`): `sendMonth` takes the month's latest invoices (`DocumentsFacade.latestInvoices`,
  those with nothing due only with `includeNothingDue`), `sendInvoice` one version whatever is due; every current owner
  of the plot (`PlotOwners.currentOwnerIds`) gets one row per (invoice version, member, channel): `INSERT … ON
  CONFLICT DO NOTHING` on the table's unique key, so a second send adds only what is new. E-mail and Telegram rows are
  `QUEUED` (due now), paper rows `TO_PRINT`. The answer is the rows created per channel and how many were queued; the
  preview runs the same plan without writing (`queued` 0). A send that created something publishes `INVOICES_SENT`
  (`BILLING_MONTH`, the entity id of `INVOICES_ISSUED`, fields `month`, `telegram`, `email`, `paper`, and `number` for
  one invoice). After the commit the lanes with queued rows are woken (`DeliveryQueue.wakeAfterCommit`).
- **The register** (`DeliveryRows`, `DeliveryRegister`): the list (newest first, filters month, kind, status, channel;
  the plot from `invoiceSummary`, the member "Last First"; counts per channel and status for the month and kind),
  «Надіслати ще раз» (a `SENT` or `FAILED` e-mail or Telegram row: `QUEUED`, attempts 0, `resends + 1`, the code
  cleared, the lane woken; 409 `DELIVERY_NOT_RETRYABLE` for paper or a row still queued; 404 for another
  cooperative's), «Роздруковано» (`TO_PRINT` → `PRINTED` for the month's invoice rows). Retries and the print mark are
  not audited. A row holds ids, codes and the month only.
- **The worker** (`DeliveryDispatcher`, the JobRunr job `DispatchDeliveries(tenantId, channel)` run by
  `DispatchDeliveriesHandler`): binds the cooperative (`TenantContext.runAs`), claims the lane's lease in
  `delivery_lane` (`DeliveryLanes`, 60 s, database time; a lane held elsewhere is tried again after 5 s), then takes
  the due rows 20 at a time in a short transaction; per row it prepares the message in a short read-only transaction
  (`InvoiceMessages`: the summary and the PDF; the opened mailbox and the member's e-mail, or the opened bot token
  and the member's chat), waits for its slot (`RateLimiters`) and sends outside any transaction (`MailChannel`,
  `TelegramChannel`), then records the outcome in a short transaction, only while the row's attempts are unchanged. A
  run stops after 60 s and wakes the lane again, or when nothing is due and schedules the lane at the earliest next
  attempt. Three attempts: now, +1 min, +5 min; then `FAILED` with the last code. `TELEGRAM_BLOCKED` (403, the link
  ends `BLOCKED`), `NO_CONTACT` (no e-mail, no active link, a chat Telegram refuses), `CHANNEL_NOT_CONFIGURED` (no
  mailbox or bot, a token Telegram refuses) and `SMTP_AUTH_FAILED` fail at once; `SMTP_REJECTED` and
  `CONNECTION_FAILED` are tried again. Telegram's 429 moves the row by `retry_after` without counting an attempt and
  pauses the lane until then. JobRunr retries a crashed run only (2 retries). Job ids come from the lane and the second
  it is due, so a lane woken twice in a second gets one job.
- **The sweep** (`DeliveryDispatcher.sweep`, every `nadel.delivery.sweep-interval` from `BotSchedule`): lists the lanes
  with due rows through the `SECURITY DEFINER` function `notifications_due_lanes(now)` (V30_1: cooperative id and
  channel code only; it runs without a cooperative, where RLS shows no row) and wakes each, so nothing waits after a
  restart or a lost job.
- **The pace** (`RateLimiters`, in memory: the lane lease makes one instance send a lane): one message a second per
  member's chat, 20 a second per bot, the cooperative's `email_rate_per_second` per mailbox; sends are spaced evenly.
- **The texts** (`messages_uk.properties`, `invoice.*`): Telegram sends the PDF (`sendDocument`) with the caption
  «Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴» and the URL button «Оплатити» (the invoice's NBU payment
  link) when something is due, otherwise «Рахунок за …, ділянка 9. Боргу немає, дякуємо!» without a button. E-mail: the
  subject «Рахунок за жовтень 2026, ділянка 9», plain text and HTML with the same words, the payment link, «Рахунок у
  вкладенні (PDF).» and «Правління {cooperative}», the PDF attached, from the mailbox's sender name.

## Payment and reversal messages (`PaymentMessages`, feature 017 §2.4)

- **Planning:** `@Async @TransactionalEventListener` on `ledger.PaymentRecorded` (every payment entry: one event per
  save with every payment written) and `ledger.PaymentReversed` (the reversed payment's id, not its reversal row), the
  ADR 0007 pattern (`TenantContext.runAs(event.tenantId())`, then a `TransactionTemplate`). Nothing while
  `delivery_settings.notify_payments` («Повідомляти про платежі») is off. Otherwise each payment of the event
  (`LedgerReadModel.paymentsByIds`) goes to every current owner of its plot (`PlotOwners.currentOwnerIds`) whose
  channel comes out as e-mail or Telegram by the rule of invoices (`RecipientChannels`); paper (and Viber) owners get
  nothing. A payment row is always of one plot, so a split over several plots gives one message per plot to each of
  its owners. One row per (payment, kind `PAYMENT` or `REVERSAL`, member, channel), `QUEUED`, with the payment's billing
  month: `INSERT … ON CONFLICT DO NOTHING` on `delivery_payment_uq`, so a redelivered event creates nothing and nothing
  is sent twice. The lanes with new rows are woken after the commit. Not audited (the payment itself is).
- **Sending:** the dispatcher sends kinds `INVOICE`, `PAYMENT` and `REVERSAL` (`DeliveryDispatcher.KINDS`); the
  message is written at send time (`PaymentMessages.of`), never stored: «Отримано 500,00 ₴ по ділянці 9 (28.10.2026).
  Борг: 1 234,56 ₴», with «Аванс: …» instead of «Борг: …» when the plot owes nothing and has a prepayment; after a
  reversal «Платіж від 28.10.2026 на 500,00 ₴ сторновано. Борг: …» (the same balance rule). The day is the day the
  money was received, the balance the plot's as of today in Kyiv (`debtsAsOf`, every charge type), so it matches the
  account when the message goes. A cash payment (not its reversal) carries the cash receipt of its group
  (`DocumentsFacade.receiptPdf`): Telegram `sendDocument` with the text as the caption, e-mail with the PDF attached and
  «Квитанція у вкладенні (PDF).»; a bank payment and a reversal go as a text message (`sendMessage`) or a letter
  without an attachment. Letter subjects «Платіж по ділянці 9 отримано» / «… сторновано». Texts `payment.*` in
  `messages_uk.properties`. The delivery screen lists these rows under their kind; they have no plot number there.

## Paper copies (`PaperCopiesProvider`, documents SPI `PaperRecipients`)

The print PDF of a month (`scope=PAPER`) prints one copy per owner whose channel comes out as paper by the rule above,
named "Last First". **It creates link codes:** when the cooperative has a bot whose @name is known
(`delivery_settings.bot_username`), every such owner who is not linked yet gets a new 90-day `PAPER` code, printed as
the QR code «Отримувати рахунки в Telegram» with the deep link `https://t.me/<bot>?start=<code>` (one code per member
per print). Only the hash is stored, so the code exists only on paper; printing again creates new codes and the old
ones stay valid until they expire or are used.

## Background jobs (JobRunr)

JobRunr 9.0.1 (`jobrunr-spring-boot-4-starter`, Jackson 3) on the application's DataSource as `nadel_app`, its tables
from Flyway V30 (`jobrunr.database.skip-create=true`, default prefix, schema `public`, no RLS: job arguments are a
cooperative id and a channel). Dashboard off, no usage data sent, the background job server on
(`jobrunr.background-job-server.enabled`; the tests turn it off and call the dispatcher, `DeliveryJobTests` turns it on).

## Mail and Telegram clients

- `internal.mail.MailSenderFactory`: one `JavaMailSenderImpl` per cooperative, kept while its mailbox is unchanged
  and rebuilt when any value (the password included) differs. Connect, read and write timeout
  `nadel.delivery.smtp-timeout` (10 s). `STARTTLS` is required when chosen (no fall back to plain text), `SSL` is TLS
  from the start; both check the server certificate's host name. There is no application-wide mail server
  (`spring.mail.*` stays unset).
- `internal.telegram.TelegramClient`: the JDK `HttpClient` against `nadel.telegram.api-url`, `POST
  <api-url>/bot<token>/<method>` with a JSON body; `getMe`, `getUpdates` (long poll, `TelegramUpdate`) and
  `sendMessage` (with a `ReplyMarkup`: a reply keyboard, its removal or a URL button) and `sendDocument` (a hand-built
  `multipart/form-data` body: chat, caption, buttons and the PDF). Errors are `TelegramException` with a `Kind`
  (`UNAUTHORIZED` 401/404, `FORBIDDEN` 403,
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
| `nadel.delivery.retry-delays` | 1m,5m | The pauses before the second and the third attempt |
| `nadel.delivery.lane-lease` | 60 s | How long a send lane's lease lasts without renewal |
| `nadel.delivery.run-limit` | 60 s | How long one run of a lane sends before it hands over to a new job |
| `nadel.delivery.busy-retry` | 5 s | When a run that found its lane held elsewhere tries again |
| `nadel.delivery.sweep-interval` | 60 s | How often the lanes with due rows are woken (with `nadel.telegram.bot-enabled`) |
| `nadel.delivery.telegram-per-second`, `telegram-chat-gap` | 20, 1 s | The pace through one bot and to one chat |
| `jobrunr.*` | see `application.yml` | `database.skip-create=true`, `dashboard.enabled=false`, `jobs.default-number-of-retries=2`, `miscellaneous.allow-anonymous-data-usage=false` |

## Tests

`DeliverySettingsTests`, `TestEmailTests`, `TestTelegramTests`, `DeliverySettingsAccessTests` (plus the V29 table tests
`NotificationsTenantIsolationTests`, `NotificationsGuardsTests`, `BotTenantsFunctionTests`); slice 2: `LinkCodesTests`,
`TelegramLinkingTests`, `BotInputTests`, `BotSingleWorkerTests` (two supervisors with their own instance ids over one
database), `MeNotificationsTests`; slice 3: `ChannelResolverTests` (one per row of the rule), `InvoiceDeliveryTests`
(a throw-away cooperative, `DeliveryTestSupport`: Mailpit and the fake Bot API, a dispatcher with a movable clock),
`DeliveryRateLimitTests`, `DeliveryAccessTests`, `DeliveryJobTests` (JobRunr's server on: the job a send wakes
delivers the letter; the V30 tables) and, in the documents tests, `PrintCopiesTests`; slice 4: `PaymentMessagesTests`
(cash with the receipt by Telegram and by e-mail, a bank payment without an attachment and with the prepayment, the
reversal text, the switch off, a paper co-owner, a redelivered event, the outbox payload) and, in the ledger tests,
`PaymentEventsTests`. No test reaches Telegram or a real mailbox: `app.nadel.notifications.FakeBotApi`
(test sources) is an in-JVM Bot API on a random port that every Spring test context points `nadel.telegram.api-url` at
(`IntegrationTest`, which also turns the schedule off and sets a 1 s poll), with a long-poll `getUpdates` over updates
a test pushes per token, a recorded `sendMessage` and multipart `sendDocument` (caption, file, URL buttons; 403 for a
chat that blocked the bot), handlers per method, one-shot failures (403, 429 with `retry_after`) and a call record; `app.nadel.notifications.Mailpit` is Mailpit (`axllent/mailpit:v1.27.8`, as in
`infra/docker-compose.yml`) in a Testcontainers `GenericContainer` with SMTP sign-in and its chaos switch (every
recipient refused with 550), read through `/api/v1/messages`.

## Things to remember

- Never log an address, a token, a password, a chat id or a message text. Logs carry the cooperative id and codes.
- Network calls run outside transactions; database work in short transactions bound to the cooperative.
