# Functional Specification: Delivery by e-mail and Telegram, the cooperative's bot

- **Topic:** The cooperative sends invoices, payment messages with cash receipts and board announcements to members by
  e-mail (the cooperative's own mailbox) and Telegram (the cooperative's own bot), and sees the outcome of every send.
  The bot also answers members: what they owe, the invoice as of today, and it takes meter readings for approval.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/017-delivery-email-telegram.md` (roadmap phase 4, feature 2 of 2)

---

## 1. Overview and Rationale (The "Why")

After feature 016 the board can build October invoices for every plot in one click, but it still has to get them to
members: print them, hand them out, or forward PDFs one by one from a personal phone. Payments recorded at the cash desk
give a receipt on paper only, and board announcements go into a chat group the board does not control.

This feature delivers documents through two channels the cooperative owns:

- **E-mail** through the cooperative's own mailbox, set up by the chairman.
- **Telegram** through the cooperative's own bot, created by the chairman. Members see the cooperative's name, not a
  third-party service.

Every send to one member through one channel is recorded with its outcome (sent, failed with a reason, or to print), so
the treasurer knows who got the invoice and who still needs paper. The bot also lets a member ask «Борг» (what I owe),
get «Рахунок» (the invoice as of today) and send a meter reading for approval without signing in to the web account.

**Success looks like:** on 5 November the treasurer builds October invoices, clicks «Надіслати рахунки за жовтень», and a
minute later sees 120 sent by Telegram and e-mail, 2 failed with their reason and 14 to print. A member gets
«Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴» in Telegram with the PDF and an «Оплатити» button that opens
their banking app.

---

## 2. Functional Requirements (The "What")

Messages and e-mails to members are always in Ukrainian, like the documents of 016. Screen texts follow the user's
language. Amounts are shown as `1 234,56 ₴`, dates as `28.10.2026`, months as «жовтень 2026».

### 2.1 Channel settings (Settings → «Розсилка»)

Only a user with the right to manage settings sees and changes this section.

**E-mail:** server address, port, security (STARTTLS or SSL), user name, password, the sender's address and the
sender's name. «Надіслати тестовий лист» sends a test letter to the signed-in chairman's own e-mail and shows the mail
server's answer (accepted, or the server's error in plain words).

**Telegram:** the bot token and «Перевірити бота», which asks Telegram about the bot and shows its @name, or «Токен
недійсний». Next to the field a short instruction: open @BotFather → `/newbot` → name «Надія — рахунки» → copy the
token here.

**Rates:** the e-mail sending rate (letters a second, default 1, so a free mailbox is not blocked).

**Payment messages:** the switch «Повідомляти про платежі» (default on).

**Secrets:** the mailbox password and the bot token are never shown again after saving. The screen shows «збережено»
for the password and the last 4 characters of the token. Leaving a secret field empty keeps the saved value. The change
history records only that a secret was changed, never its value.

A channel without settings is shown as «не налаштовано», and nothing is sent through it.

- **Acceptance Criteria:**
  - [x] Given a chairman on Settings, when they open «Розсилка» for a cooperative that never set it up, then both
        channels show «не налаштовано».
  - [x] When the chairman saves the mailbox settings and reopens the page, then the password field shows «збережено» and
        never the password.
  - [x] When the chairman saves a bot token and reopens the page, then only the last 4 characters of the token are
        shown.
  - [x] When the chairman clicks «Надіслати тестовий лист» with working settings, then they see that the server accepted
        the letter, and the letter arrives at their own e-mail.
  - [x] When the chairman clicks «Надіслати тестовий лист» with a wrong password, then they see the server's refusal in
        plain words and nothing else changes.
  - [x] When the chairman clicks «Перевірити бота» with a valid token, then they see the bot's @name; with an invalid
        token, then they see «Токен недійсний».
  - [x] Given a treasurer without the right to manage settings, when they open Settings, then the «Розсилка» section is
        not shown and saving it is refused.
  - [x] When the chairman changes the mailbox password, then the change history shows «змінено» for the password and not
        the value.

### 2.2 Linking a member to Telegram

A bot cannot write first to a person by phone or @username: the member opens the bot once through a personal link.

- **From the account:** the «Сповіщення» panel has «Підключити Telegram». It opens the cooperative's bot with a personal
  one-time code, valid 30 minutes.
- **From a paper invoice:** each paper copy carries a small QR code «Отримувати рахунки в Telegram» with a personal
  one-time code of its recipient, valid 90 days. Each co-owner who gets paper gets their own copy with their own code.
- On opening the bot with a valid code, the bot answers «Вітаємо, {ім'я}! Тепер рахунки за ділянку {номери}
  приходитимуть сюди.» and shows the menu. The code cannot be used again.
- A used, expired or unknown code is answered «Посилання недійсне, отримайте нове в кабінеті або в правлінні», and
  nothing is linked.
- If the Telegram account is already linked to another member of the same cooperative, the bot asks «Цей Telegram уже
  підключено до іншого члена кооперативу. Це ви, {ім'я}?» and moves the link only after «Так, це я».
- **Unlinking:** «Відключити» in the bot (or `/stop`), «Відключити Telegram» in the account, or the member blocking the
  bot ends the link. The member then gets documents through the fallback channel (2.3).
- The board sees per member «Telegram: підключено / не підключено» on the member card, never the member's Telegram
  account details.

- **Acceptance Criteria:**
  - [x] Given a member with the bot set up, when they click «Підключити Telegram» and open the bot, then the bot greets
        them by name with their plot numbers and shows the menu, and the account shows «Telegram підключено».
  - [x] When someone opens the bot with a link that was already used, then the bot answers «Посилання недійсне,
        отримайте нове в кабінеті або в правлінні» and nothing is linked.
  - [x] When a member opens the bot with an account link older than 30 minutes, then the bot answers that the link is
        invalid.
  - [x] Given a printed invoice copy of a paper member, when the member scans its QR code within 90 days and opens the
        bot, then their Telegram is linked.
  - [x] Given a Telegram account linked to member A, when member B opens the bot with B's valid link, then the bot asks
        for confirmation, and only after «Так, це я» the link moves to B.
  - [x] When the member sends «Відключити» in the bot, then the account shows «Telegram не підключено» and the next
        invoice goes by the fallback channel.
  - [x] Given a linked member who blocked the bot, when the next message to them is sent, then the link ends and the
        delivery is shown as failed «Бот заблоковано».
  - [x] Given a cooperative without a bot, when the member opens «Сповіщення», then «Підключити Telegram» is not
        offered.
  - [x] When the board opens a member card, then it shows only «Telegram: підключено» or «не підключено».

### 2.3 Who gets what, through which channel

The member's preferred channel decides:

| Preferred channel | Situation | Gets it by |
|---|---|---|
| Telegram | linked and the bot is set up | Telegram |
| Telegram | not linked, or no bot | e-mail if the member has an e-mail and the mailbox is set up, otherwise paper |
| E-mail | e-mail present and the mailbox is set up | e-mail |
| E-mail | no e-mail, or no mailbox | paper |
| Paper | — | paper (the print PDF) |
| Viber | — | paper, with the note «Viber ще не підтримується» on the delivery screen |

The member chooses the preferred channel in the account («Сповіщення») among the channels available to them (Telegram
when the bot is set up; e-mail when they have an e-mail; paper always). The board still sets it on the member form. A
member may choose Telegram without a Telegram username.

**Co-owners:** every current owner of a plot gets the plot's invoice through their own channel. A member who owns
several plots gets one message per plot.

- **Acceptance Criteria:**
  - [x] When the channel rule is checked for each row of the table above, then each case gives the channel in the
        table.
  - [x] Given a plot with two current owners, one on Telegram and one on paper, when the month's invoices are sent, then
        one gets a Telegram message and the other appears among «до друку».
  - [x] Given a member who owns plots 9 and 10, when invoices are sent, then they get two messages, one per plot, each
        with its own PDF.
  - [x] When a member changes their channel to e-mail in «Сповіщення», then the next invoice comes by e-mail, and the
        board sees the new channel on the member form.
  - [x] Given a member without an e-mail, when they open «Сповіщення», then e-mail is not offered.

### 2.4 Sending invoices, payment messages and the delivery screen

Only users with the right to issue invoices send invoices, see the delivery screen and resend.

**Invoices:** «Надіслати рахунки за {місяць}» on the month's invoices first shows a preview: how many members will get
it by Telegram, by e-mail and how many on paper; a checkbox decides whether invoices with nothing due are sent too.
«Надіслати» on one invoice sends it to that plot's owners.

- Telegram: «Рахунок за жовтень 2026, ділянка 9: до сплати 413,26 ₴» + the PDF + the button «Оплатити» (the payment QR
  link of 016, which opens the banking app). Nothing due: «Боргу немає, дякуємо!» + the PDF.
- E-mail: the same text in the letter, the PDF attached, from the cooperative's sender name.
- Paper: «до друку» until the treasurer clicks «Роздруковано» for the month's print PDF.

**Payment messages:** when the switch «Повідомляти про платежі» is on, after a payment is saved, every current owner of
the plot who gets messages by Telegram or e-mail gets «Отримано 500,00 ₴ по ділянці 9 (28.10.2026). Борг: 1 234,56 ₴»
(or «Аванс: …» when nothing is owed), plus the cash receipt PDF for a cash payment. After a reversal: «Платіж від
28.10.2026 на 500,00 ₴ сторновано. Борг: …». Paper members get no payment message.

**Outcome:** each send is «у черзі», then «надіслано» (the mail server or Telegram accepted it) or «помилка» with a
reason: «Сервер пошти відмовив», «Бот заблоковано», «Немає контакту», «Канал не налаштовано», «Не вдалося з'єднатися».
Sending runs in the background at a limited pace (Telegram at most 25 messages a second overall and 1 a second per
member; e-mail at the configured rate) and tries 3 times with growing pauses before «помилка».

The same invoice version is never sent twice to the same member through the same channel by repeating the month's
send. «Надіслати ще раз» on a failed or sent row sends it once more.

**Delivery screen** (Документи → «Розсилка»): per month, the counts sent, failed and to print per channel; a list
filtered by kind (рахунок, платіж, оголошення), status and channel; failed rows with their reason and «Надіслати ще
раз».

- **Acceptance Criteria:**
  - [x] Given October invoices built, when the treasurer clicks «Надіслати рахунки за жовтень», then they see the preview
        counts per channel before anything is sent.
  - [x] When the treasurer confirms, then the delivery screen shows sent, failed and to print per channel for October.
  - [x] When the treasurer sends October invoices a second time, then no member gets the same invoice again.
  - [x] Given a member who blocked the bot, when invoices are sent, then their row shows «помилка: Бот заблоковано».
  - [x] Given a mailbox that refuses letters, when invoices are sent, then the e-mail rows end as «помилка: Сервер пошти
        відмовив» after 3 attempts.
  - [x] When the treasurer clicks «Надіслати ще раз» on a failed row after fixing the cause, then the row becomes
        «надіслано».
  - [x] When the treasurer clicks «Роздруковано» for October, then the paper rows stop showing as «до друку».
  - [x] Given a linked member and the switch on, when the cashier saves a cash payment of 500,00 ₴ for their plot, then
        the member gets «Отримано 500,00 ₴ по ділянці 9 (…)» with the debt and the cash receipt PDF.
  - [x] Given the switch off, when a payment is saved, then no payment message is sent.
  - [x] When a payment is reversed, then the member gets «Платіж від … на … сторновано» with the new debt.
  - [x] Given a user without the right to issue invoices, when they try to send invoices or open the delivery screen,
        then it is refused.
  - [x] Given a Viber member, when invoices are sent, then they are listed «до друку» with «Viber ще не підтримується».

### 2.5 The bot

**Menu** (buttons under the message box, Ukrainian): «Борг», «Рахунок», «Показання», «Відключити». `/start` and `/help`
show the menu and a short help.

- **«Борг»:** per plot of the member, the debt per charge type, the total and the prepayment, as on the account.
- **«Рахунок»:** the member chooses a plot (if several) and gets the invoice as of today with the PDF and «Оплатити». The
  same day returns the same invoice; at most one new invoice version per plot per day.
- **«Показання»:** choose the plot (if several), then the meter (resource and serial number), then type the value. The
  reading is submitted for the board's approval with today's date. The answer: «Показання 12 345 по лічильнику ЕЛ-9/2
  передано на перевірку». A wrong value gets the same message the account shows, in plain words, and the member can type
  again. When the board approves or rejects it, the member gets «Показання 12 345 по лічильнику ЕЛ-9/2 прийнято» or
  «… відхилено: {причина}».
- **«Відключити»:** ends the link (2.2).
- A linked member reaches only their own current plots and nothing else. An unlinked chat gets only the help text and
  the invitation to link through the account.

**Untrusted input:** anything other than the commands and the steps of the reading dialogue gets the help text. Messages
longer than 64 characters in the reading dialogue are refused. More than 20 messages a minute from one chat get
«Забагато повідомлень, спробуйте за хвилину» once, and the rest of that minute is ignored. Message texts are never
kept, except a reading value submitted for approval.

**One bot worker per cooperative:** only one copy of the application answers a cooperative's bot at a time; a second
copy waits and takes over if the first stops. Changing the bot token restarts that cooperative's bot within a minute.

- **Acceptance Criteria:**
  - [x] Given a linked member with plot 9, when they press «Борг», then they see the debt per charge type, the total and
        the prepayment matching the account.
  - [x] When the member presses «Рахунок» twice on the same day, then both answers carry the same invoice number.
  - [x] When the member sends a reading 12 345 for meter ЕЛ-9/2 through «Показання», then the bot answers «… передано на
        перевірку» and the reading appears for the board's approval.
  - [x] When the board approves the reading, then the member gets «… прийнято»; when the board rejects it with a reason,
        then the member gets «… відхилено: {причина}».
  - [x] When the member types a reading below the previous one, then the bot answers with the same validation message as
        the account and asks again.
  - [x] When the member sends any other text, then the bot answers with the help text.
  - [x] When a chat sends 25 messages in one minute, then it gets «Забагато повідомлень…» once and the rest are not
        answered.
  - [x] Given two copies of the application running, when a member writes to the bot, then exactly one answer arrives.
  - [x] Given an unlinked chat, when it presses «Борг», then it gets the invitation to link and no amounts.

### 2.6 Announcements

Only a user with the new right «Надсилати оголошення» (the chairman by default) sends announcements.

- The chairman writes the text (up to 2000 characters, plain text) and chooses the recipients (all members, or the
  owners of plots on chosen streets) and the channels (Telegram, e-mail or both).
- A preview shows how many members get it through each channel and lists the members who have no chosen channel
  («на папері»), to inform them in person.
- «Надіслати» sends it. The announcements list shows past announcements with the date, author, audience and counts sent,
  failed and on paper; opening one shows its text and its deliveries.

- **Acceptance Criteria:**
  - [x] When the chairman writes an announcement for the street «Вишнева» with Telegram only and opens the preview, then
        they see the count by Telegram and the list of the street's members without Telegram.
  - [x] When the chairman clicks «Надіслати», then the street's linked members get the text in Telegram and the
        announcement appears in the list with its counts.
  - [x] When the text is longer than 2000 characters, then «Надіслати» is refused with «Не більше 2000 символів».
  - [x] Given a treasurer without the right «Надсилати оголошення», when they open announcements, then sending is not
        available and is refused.

### 2.7 Change history

The change history records: delivery settings changed (which settings, never secret values), invoices sent (month and
counts per channel), announcement sent (audience and counts), Telegram linked and unlinked for a member (no Telegram
account details). Single sends are not history entries; they are on the delivery screen.

- **Acceptance Criteria:**
  - [x] When the treasurer sends October invoices, then the history shows «Рахунки надіслано» with the month and counts.
  - [x] When a member links or unlinks Telegram, then the history shows it for that member without any Telegram details.

### 2.8 Local development and demo

- The local environment includes a mail catcher with a web page, so letters from the demo cooperative can be read
  locally, and a fake Telegram server, so the demo bot works without the real Telegram.
- Demo data: the demo cooperative's mailbox points at the mail catcher, a demo bot is set up against the fake Telegram,
  two demo members are linked, and last month's invoices are already sent.

- **Acceptance Criteria:**
  - [x] When the developer starts the local environment and signs in as the chairman, then «Розсилка» shows both
        channels set up, and the delivery screen shows last month's invoices sent.
  - [x] When the test letter is sent locally, then it appears in the mail catcher's page.

### 2.9 Documentation

- Both guides (Ukrainian and English) describe setting up the mailbox (a free mailbox with an app password, its daily
  limits) and the bot step by step, sending invoices and reading the delivery screen, announcements, and what a member
  sees in the bot. The owner's manual covers the mail catcher, the fake Telegram server, the single bot worker and
  troubleshooting (a wrong token, a blocked mailbox, a rate limit).
- A one-page leaflet for members «Як отримувати рахунки в Telegram» as a printable PDF.

- **Acceptance Criteria:**
  - [x] When the owner opens the guides in both languages, then the new sections are present and marked as working.
  - [x] When the board opens the leaflet PDF, then it fits on one page and explains linking from the account and from the
        paper QR code.

### 2.10 The owner's check before merging

The owner creates a real bot, links their own account from the account page and from a printed QR code, gets an
invoice, asks «Борг», sends a reading, sends a test e-mail through a real mailbox, and writes the result in the pull
request.

- **Acceptance Criteria:**
  - [?] When the owner performs the real bot and mailbox check, then the result is written in the pull request.

---

## Verification (pipeline, 2026-10-30)

- Every criterion except 2.10 is checked by tests: see `acceptance-coverage.md` (acceptance tests `DeliveryAcceptanceTests`,
  the e2e specs `delivery-invoices`, `telegram-link`, `delivery-payment-message`, `delivery-announcement`, `delivery-demo`,
  and the slice tests). Full suite on 35b7ced: `make check` green (backend 3887 tests, coverage met; clients green).
- Rendered in Chrome against the local stack (`make up`, demo data, Mailpit and the fake Telegram server):
  - `docs/screenshots/017-delivery-email-telegram-settings-mailbox.png` — «Розсилка» with both channels «налаштовано»;
  - `docs/screenshots/017-delivery-email-telegram-settings-test-results.png` — test letter accepted (Mailpit reply),
    «Перевірити бота» → @nadel_demo_bot, token shown as the last 4 characters only; the letter arrived in Mailpit;
  - `docs/screenshots/017-delivery-email-telegram-deliveries.png` — the delivery screen with counts per channel,
    «до друку», the Viber note and «Надіслати ще раз»;
  - `docs/screenshots/017-delivery-email-telegram-announcement-preview.png` — street audience, preview counts and the
    paper list (nothing sent);
  - `docs/screenshots/017-delivery-email-telegram-member-card-telegram.png` — «Telegram: підключено» on the member card.
- `[?]` 2.10: the owner's check with a real bot and a real mailbox needs real credentials and is done by the owner
  before merging.

---

## 3. Scope and Boundaries

### In-Scope

- Channel settings for the cooperative's mailbox and bot, with test buttons and hidden secrets.
- Linking members to Telegram from the account and from paper invoices; unlinking.
- The channel choice per member, co-owners, the member's own channel choice.
- Sending invoices, payment and reversal messages, announcements; the delivery screen; resending; marking paper as
  printed.
- The bot: «Борг», «Рахунок», «Показання» with approval messages, unlinking, help, input limits.
- Change history entries, permissions, the new right «Надсилати оголошення».
- Local mail catcher, fake Telegram server, demo data, guides and the leaflet.

### Out-of-Scope

- Viber; SMS.
- Photos of meter readings (phase 5); push messages in the mobile app (phase 5).
- Processing bounced letters; tracking whether a message was read.
- Scheduled reminders to debtors (phase 8).
- Members' replies to announcements.
- A shared platform bot for all cooperatives.

---

## Assumptions & Decisions

Phase 1 of the pipeline found no blocking questions; each item below took the recommended answer (see
`pipeline-log.md`, entries `AUTO:`).

- Choosing Telegram as the preferred channel no longer requires a Telegram username: linking replaces it. The username
  stays an optional note on the member form and in the import.
- Billing months are addressed by month (as in 016), not by a separate identifier.
- One bot worker per cooperative is guaranteed across application copies; a stopped copy is replaced within about a
  minute.
- «Рахунок» in the bot reuses the 016 invoice as of today, limited to one new version per plot per day.
- Paper copies of invoices are printed one per paper recipient (not one per plot), each with its own link QR; this
  changes the 016 print set. The paper list follows the channel rule of 2.3 (unlinked Telegram without e-mail and Viber
  go to paper).
- Payment messages go to every current owner of the paid plot who gets messages by Telegram or e-mail.
- The streets to choose for an announcement are the streets of the cooperative's active plots.
- Messages in the reading dialogue are limited to 64 characters; other limits as in the requirement.
- A secret field left empty on save keeps the saved secret.
- The «Розсилка» section is a panel on the Settings page; the delivery screen is reached from Documents.
- The owner's real bot and mailbox check is a manual step recorded in the pull request.

---

## Change Log

- (none yet)
