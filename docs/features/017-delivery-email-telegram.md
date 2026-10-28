# 017. Delivery by e-mail and Telegram, the cooperative's bot

Roadmap phase 4, feature 2 of 2. Input for `/feature-pipeline`. Requires 010 (member reading submission), 014 (the
`privacy` cipher), 015 (payments, receipts) and 016 (invoices, cash receipt PDFs).

## 1. Goal

The cooperative sends documents to members by e-mail and Telegram, and the system records the outcome of each send:
- invoices after they are built;
- a short message and a cash receipt after a payment;
- announcements of the board.

The cooperative's own Telegram bot also answers members:
- what they owe;
- the invoice as of today;
- it takes a meter reading for approval.

**Decided with the owner (28.10.2026):**
- **E-mail:** the chairman sets up the cooperative's mailbox (SMTP) in the settings.
- **Telegram:** the cooperative's own bot, created by the chairman with @BotFather. Its token is entered in the settings.
  Members see the cooperative's name, not a third-party service.
- **Linking a member to the bot:**
  - «Підключити Telegram» in the member account;
  - a small QR code «Отримувати рахунки в Telegram» on paper invoices, for members who never sign in.
- **The bot does:**
  - invoices;
  - payment messages with the cash receipt;
  - the commands «Борг» and «Рахунок»;
  - meter readings sent for approval;
  - board announcements.
- **Co-owners:** every current owner of a plot gets its invoice through their own channel.
- **Viber:** later, it needs a paid contract.

## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- the READMEs of `members` (`delivery_channel`, `telegram_username`, contacts through `MemberDirectory`), `privacy`,
  `meters` (the member flow: `submitPlotReading`, approval), `ledger` (account, payments, `LedgerChanged`), the
  documents module of 016, and `settings`;
- ADRs 0007 (events, outbox, jobs), 0016 (personal data encryption) and 0017 (documents);
- the guides.

General rules as in earlier features:
- contract-first;
- Flyway only;
- RLS and isolation tests;
- permissions, not role names;
- Problem Details with a `code`;
- i18n for screens;
- audit through the outbox;
- the verification levels and the guides rules of `CLAUDE.md`.

Messages and e-mails to members are in Ukrainian, like the documents of 016.

**A new module `notifications`** owns channels, links, deliveries, the bot and announcements. It reads members only
through `MemberDirectory` and documents through the 016 module's facade.

## 2. Channel settings (Settings → «Розсилка», `SETTINGS_MANAGE`)

**E-mail (SMTP):**
- host, port, security (`STARTTLS` or `SSL`), user, password, the sender's address and name;
- «Надіслати тестовий лист» sends to the chairman's own e-mail and shows the SMTP server's answer;
- the guide explains a free mailbox with an app password (Gmail, Ukr.net) and the daily sending limits of such
  mailboxes.

**Telegram:**
- the bot token, and «Перевірити бота», which calls `getMe` and shows the bot's @name;
- a short guide in place: open @BotFather → `/newbot` → name «Надія — рахунки» → copy the token here.

**Secrets of the cooperative.** The SMTP password and the bot token are encrypted with the cooperative's data key (014,
`privacy`):
- they are never returned by the API (only «збережено», saved, and the last 4 characters of the token);
- they are never logged;
- they are never put in an audit value (the audit records «змінено», changed, only).

Platform secrets stay in the environment, as `CLAUDE.md` requires. These two are the cooperative's own credentials,
entered by its chairman, so they live in its data. ADR 0018 records this exception.

**Sending is off until set up.** A channel without settings is shown as «не налаштовано», and nothing is sent through
it.

## 3. Linking a member to Telegram

A bot cannot write first to a person by phone or @username. The member opens the bot once through a link with a
one-time code.

**Codes:**
- random, 128 bits, base64url;
- only a hash is stored;
- bound to one member;
- used once.
- **From the account:** «Підключити Telegram» opens `https://t.me/<bot>?start=<code>`, valid 30 minutes.
- **From a paper invoice:** a small QR code per paper recipient, valid 90 days. The print PDF of 016 prints one copy
  per paper recipient, so each copy carries its owner's code. The code is added at print time and is not part of the
  invoice snapshot.

**Linking:**
- On `/start <code>` the bot links the chat to the member and answers «Вітаємо, {ім'я}! Тепер рахунки за ділянку {номери}
  приходитимуть сюди.» with the menu.
- A used, expired or unknown code is answered «Посилання недійсне, отримайте нове в кабінеті або в правлінні» and links
  nothing.
- A chat already linked to another member of the same cooperative is moved only after the member confirms «Так, це я».

**Stored and encrypted.** The chat id is personal data, so the link stores it encrypted (014) with a blind index,
because incoming updates are found by chat id.

**Unlinking:**
- `/stop` in the bot, or «Відключити Telegram» in the account, ends the link;
- a Telegram answer 403 (the bot was blocked) ends it too;
- the member's channel then falls back (section 4).

**The board** sees per member «Telegram: підключено / не підключено», never the chat id or the Telegram name.

## 4. Who gets what, through which channel

The member's `delivery_channel` (002) is the preferred channel:
- `TELEGRAM` and linked → Telegram;
- `TELEGRAM` and not linked → e-mail if the member has one, otherwise paper;
- `EMAIL` and an e-mail present → e-mail;
- `PAPER` → paper (in the print PDF);
- `VIBER` → treated as paper until Viber exists, with a note on the delivery screen.

The member chooses the channel in the account («Сповіщення») among the ones available. The board can set it on the
member form as now.

**Co-owners.** Every current owner of the plot gets the plot's invoice through their own channel. A member who owns
several plots gets one message per plot, so each plot's QR and PDF stay separate.

## 5. Deliveries

**What can be sent:**

| Kind | When | Content |
|---|---|---|
| Invoice | «Надіслати рахунки за {місяць}» after the 016 build, or «Надіслати» on one invoice | Telegram: «Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴» + the PDF + the button «Оплатити» (the 016 QR link `https://qr.bank.gov.ua/…`, which opens the banking app on a phone). E-mail: the same text in HTML and plain text, the PDF attached. Nothing due: «Боргу немає, дякуємо!» + the PDF, only if the board included such invoices |
| Payment | after a payment is saved (015), if the cooperative's setting «Повідомляти про платежі» is on (default on) | «Отримано 500,00 ₴ по ділянці 9 (28.10.2026). Борг: 1 234,56 ₴» (or «Аванс: …»), plus the cash receipt PDF of 016 for cash. A reversal: «Платіж від 28.10.2026 на 500,00 ₴ сторновано. Борг: …» |
| Announcement | the chairman sends it (section 7) | the text |

**The delivery record.** Each send to one member through one channel is a delivery row:
- document or announcement;
- member;
- channel;
- status `QUEUED` → `SENT` or `FAILED` (with a code: `SMTP_REJECTED`, `TELEGRAM_BLOCKED`, `NO_CONTACT`, …);
- attempts, timestamps.

`SENT` means the e-mail server or Telegram accepted the message. Bounces and "read" are not tracked (Telegram does not
report reading to bots).

**Sending runs as a background job** (ADR 0007):
- Telegram at most 25 messages a second overall and 1 a second per chat;
- e-mail at a configurable rate (default 1 a second, so a free mailbox is not blocked);
- 3 attempts with backoff;
- idempotent: one delivery per (invoice version, member, channel). «Надіслати ще раз» creates a new attempt.

**Paper:** a delivery `PAPER` with status `TO_PRINT` until the treasurer marks the month's print PDF as printed.

**Status screen per month:**
- sent, failed and to print per channel;
- the failed ones with their reason;
- «Надіслати ще раз».

## 6. The bot

**Menu** (reply keyboard, Ukrainian):
- «Борг»: per plot of the member, the debt per charge type, the total and the prepayment, as on the account (011, 015).
- «Рахунок»: the invoice as of today for a chosen plot (016 on request). At most one new version per plot per day; the
  same day returns that day's version.
- «Показання»: choose the plot (if several), then the meter (resource icon and serial), then type the value. It goes
  through `submitPlotReading` (010) as a `PENDING` reading for approval, with the same validation messages in plain
  words. Today's date is used. The answer: «Показання 12 345 по лічильнику ЕЛ-9/2 передано на перевірку». The member is
  told when it is approved or rejected (with the reason).
- «Відключити»: unlink.
- `/start`, `/help`.

**Incoming messages are untrusted input:**
- only these commands and the steps of the reading dialogue are handled;
- anything else gets the help text;
- input is length-checked;
- at most 20 messages a minute per chat;
- message texts are never logged and never stored, except the reading value through 010.

**How the bot gets updates:** one long-polling worker per cooperative with a bot token (`getUpdates`, no public
webhook, so a manual deployment works behind NAT). One instance polls at a time (a database lock, so a second
application instance waits). A token changed in the settings restarts that cooperative's worker.

**Bot permissions:** a linked member acts as themselves with the rights of a current owner (`MeterAccess.ownsNow`,
`getPlotAccount` owner rule). Nothing else is reachable through the bot.

## 7. Announcements

- The chairman writes the text (up to 2000 characters, plain text) and chooses:
  - the recipients: all members, or the owners of plots on chosen streets;
  - the channels: Telegram, e-mail, or both.
- A preview shows how many members will get it through each channel and how many have no channel (those are listed for
  paper).
- «Надіслати» sends it through the delivery job. The announcement and its deliveries are stored; the list shows past
  announcements with their counts.
- Permission: new `ANNOUNCEMENTS_SEND` (Chairman).

## 8. Development and tests

- `make up` adds **Mailpit** (a local mail catcher with a web UI on a local port) to `infra/docker-compose.yml`, and the
  dev profile sends e-mail there.
- Telegram in tests and e2e: a small **fake Bot API server** (in the test sources) that records `sendMessage` and
  `sendDocument` and feeds `getUpdates`. No test talks to the real Telegram.
- **Owner's check before merging:**
  - create a real bot;
  - link your own account from the account page and from a printed QR;
  - get an invoice, ask «Борг», send a reading;
  - send a test e-mail through the cooperative's mailbox;
  - write the result in the PR.

## 9. API (`api/openapi.yaml`, tag `notifications`)

**Settings:**
- `GET|PUT /settings/delivery`: secrets write-only;
- `POST /settings/delivery/test-email`;
- `POST /settings/delivery/test-telegram`.

**Member:**
- `GET /me/notifications`: channel, available channels, Telegram linked;
- `PUT /me/notifications`;
- `POST /me/telegram-link`: returns the deep link;
- `DELETE /me/telegram-link`.

**Deliveries:**
- `POST /billing-months/{id}/invoices/send?preview=true|false`;
- `POST /invoices/{id}/send`;
- `GET /deliveries?month&kind&status&channel`;
- `POST /deliveries/{id}/retry`;
- `POST /billing-months/{id}/print-done`.

**Announcements:**
- `POST /announcements?preview=true|false`;
- `GET /announcements`;
- `GET /announcements/{id}`.

**Board view of a member:** `telegramLinked` on the member card, a boolean only.

## 10. Data

- `delivery_settings`: per cooperative; encrypted secrets; rates.
- `telegram_link`: member, chat id (encrypted with a blind index), linked at, ended at, end reason.
- `telegram_link_code`: hash, member, expires, used at.
- `delivery`.
- `announcement`: text, audience, channels, created by.
- `bot_poll_state`: last update id per cooperative.

All tables are tenant-scoped with RLS and isolation tests. Delivery rows keep only ids and codes, never message texts
with personal data. The texts are rebuilt from the documents.

## 11. Permissions

- **Sending invoices, payment messages, the deliveries screen and retries** need `INVOICES_ISSUE` (016).
- **Channel settings** need `SETTINGS_MANAGE`.
- **New `ANNOUNCEMENTS_SEND`** (Chairman).
- **A member** manages only their own channel and Telegram link.

## 12. Audit

- `DELIVERY_SETTINGS_CHANGED`: which settings changed, never the values of secrets.
- `INVOICES_SENT`: month, counts per channel.
- `ANNOUNCEMENT_SENT`: audience, counts; the text is user content.
- `TELEGRAM_LINKED` and `TELEGRAM_UNLINKED` about the member: no chat id.

Single deliveries are rows of `delivery`, not audit entries.

## 13. Docs and guides

- ADR 0018 (the cooperative's encrypted credentials, long polling, rates).
- The `notifications` README.
- **Board guide, both languages:**
  - setting up the mailbox and the bot step by step, with @BotFather screenshots described in words;
  - sending invoices and reading the status screen;
  - announcements;
  - what a member sees in the bot.
- **Owner manual:**
  - Mailpit;
  - the fake Bot API;
  - long polling and the database lock;
  - troubleshooting (a wrong token, a blocked mailbox, a rate limit).
- **A one-page leaflet for members** («Як отримувати рахунки в Telegram»), a PDF in `docs/guide/pdf/`, for the board to
  print.

## 14. Out of scope

- Viber;
- SMS;
- photos of meter readings (phase 5);
- push in the mobile app (phase 5);
- bounce processing;
- reminders to debtors on a schedule (phase 8);
- replies of members to announcements;
- a shared platform bot for all cooperatives.

## 15. Acceptance criteria

- [ ] The channel choice of section 4 is a pure function with a test per row.
- [ ] Linking:
      - codes are one-time, expire and are stored hashed;
      - the chat id is encrypted;
      - moving a linked chat needs confirmation;
      - a 403 from Telegram unlinks.
- [ ] Deliveries:
      - idempotent per invoice version, member and channel;
      - rate-limited;
      - retried with backoff;
      - every status and failure code covered by tests against Mailpit and the fake Bot API.
- [ ] The bot: «Борг», «Рахунок» (one version a day), «Показання» (`PENDING`, then the approve or reject message),
      unknown input, the rate limit per chat. A single poller per cooperative under two application instances.
- [ ] Secrets are never in API responses, logs or audit values (tests). Personal data is not in plaintext in the new
      tables or the outbox.
- [ ] Permissions as in section 11, with allowed and denied tests. Isolation tests. Audit as in section 12.
- [ ] Docs, both guides (two languages) and the leaflet are present. Demo data: a configured fake mailbox (Mailpit) and
      fake bot, two linked demo members, last month's invoices sent.
- [ ] `make check` is green. e2e:
      - the treasurer sends October invoices and sees sent, failed and to print (`@smoke`);
      - the member links Telegram through the fake API and gets the invoice;
      - a payment message after a cash payment;
      - the chairman sends an announcement to one street.
- [ ] The owner's real bot and mailbox check is written in the PR.
