# Functional Specification: Invoices with the NBU QR code, debt certificates and cash receipts

- **Topic:** The board gives members printable documents built from the cooperative's own accounts: a monthly invoice
  per plot with the bank details and a payment QR code that a banking app scans, a debt certificate for a plot as of a
  date, and a receipt for a cash payment. Members download their own invoices and receipts.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/016-invoices-and-documents.md` (roadmap phase 4, feature 1 of 2)

---

## 1. Overview and Rationale (The "Why")

The app already knows what each plot was charged and what was paid (features 011–015), but the board still writes
invoices by hand or in a spreadsheet, members retype the cooperative's bank details into their banking app (and often
forget the plot number in the purpose), and a debt certificate for a plot sale is typed in a word processor from
numbers read off the screen. Cash payments get a receipt number, but no printed receipt.

This feature turns the accounts into documents:

- **Invoice** (one plot, one billing month): the debt at the start of the month, what was charged and paid in the month
  per charge type, what is due, the bank details and the **national payment QR code** of the National Bank of Ukraine
  (NBU). A banking app scans the code and opens a filled-in transfer: recipient, account, recipient code and purpose
  are fixed; the member may lower the amount to pay part of it.
- **Debt certificate** («Довідка про стан розрахунків») for one plot as of a date, with a sequential number and a
  register, for a sale or on a member's request.
- **Cash receipt** («Квитанція») for a cash payment, printed at the cash desk.

Every document is a PDF in Ukrainian (an official document, whatever language the user's screens are in). The app
keeps the numbers and names it printed, so the same document can be opened again later and looks exactly the same,
even after the owner changes or later corrections.

**Success looks like:** on 5 November the treasurer builds October invoices for all plots in one click, prints one PDF
for the members who want paper, and a member opens their invoice on their phone, scans the QR code with their banking
app and pays 413,26 ₴ with the plot number already in the purpose.

---

## 2. Functional Requirements (The "What")

Unless said otherwise, amounts are shown as `1 234,56 ₴`, dates as `05.11.2026`, and months as «жовтень 2026» in the
documents. Document texts are in Ukrainian; screen texts follow the user's language.

### 2.1 The payment QR code

The QR code follows the NBU rules for the payment QR code (Resolution of the NBU Board No. 97 of 19.08.2025, format
version 003). The exact layout is recorded in the project's domain documentation, quoting the official field table.

What the code carries:

| What | Value |
|---|---|
| Transfer type | the payer chooses a regular or an instant transfer |
| Recipient | the cooperative's full official name (shortened by the NBU rules if it is too long) |
| Account | the cooperative's IBAN |
| Amount | «До сплати» of the invoice in hryvnias, written in the shortest form the rules ask for; absent when nothing is due |
| Recipient code | the cooperative's ЄДРПОУ |
| Purpose | `Ділянка {номер}. Внески за {місяць рік}. Рахунок {номер рахунку}` |
| Category / purpose | `SUPP/SUPP`, as in the official example for utility payments |
| Reference | the invoice number |
| Locked | recipient, account, recipient code and purpose cannot be changed by the payer; the amount can |

- The purpose is shortened to the allowed length when needed (a long street or plot number); the plot number and the
  invoice number are always kept.
- The code image is at least 30 × 30 mm in the PDF, with «Відскануйте QR-код у застосунку свого банку» under it and the
  link text printed under that.

**Acceptance Criteria:**
- [ ] When the treasurer opens the QR image of a demo invoice and it is decoded with a QR reader, then it shows a link
      starting with `https://qr.bank.gov.ua/` whose content, field by field, matches the official field table: the
      service mark, version 003, the cooperative's name, IBAN, the amount, the ЄДРПОУ, the purpose with the plot number
      and the invoice number, and the lock of every field except the amount.
- [ ] Given an invoice with «До сплати» 413,26 ₴, when its QR code is decoded, then the amount reads 413.26 hryvnias.
- [ ] Given a street name so long that the purpose would exceed the allowed length, when the invoice is built, then the
      purpose in the QR code is shortened and still contains the plot number and the invoice number.
- [ ] Given an invoice with nothing due, when it is opened, then it has no QR block.
- [ ] When the owner scans the demo invoice's QR code with two real banking apps before merging, then the transfer form
      opens with recipient, IBAN, purpose and amount filled in and the locked fields not editable (manual check, the
      result is written in the pull request).

### 2.2 Bank details required

Invoices need the cooperative's IBAN (Settings → Requisites) and ЄДРПОУ (set by the platform operator).

**Acceptance Criteria:**
- [ ] Given the cooperative has no IBAN, when the treasurer tries to build invoices (for a month or for one plot), then
      nothing is built and they see a message naming the missing IBAN with a link to Settings → Requisites.
- [ ] Given the ЄДРПОУ is missing, when the treasurer tries to build invoices, then the message names the missing ЄДРПОУ.
- [ ] Given IBAN or ЄДРПОУ is missing, when the chairman opens Settings → Requisites or an invoices screen, then a warning
      banner says invoices cannot be built until they are filled in.

### 2.3 The invoice

One invoice is for one plot and one billing month.

**Header:** the cooperative's official name; ЄДРПОУ, address, bank and IBAN; «Рахунок № {РРРР-ММ}/{номер ділянки}»
(for example `2026-10/9`) with «(версія N)» from the second version on; the date of issue; the billing month.

**To:** plot number and street; the current owners' full names with their shares.

**Table, one row per charge type** in the cooperative's charge type order, columns: debt at the start of the month;
charged in the month (regular charges, adjustments and reversals booked in that billing month); paid in the month
(payments placed in that month); debt at the end of the month.

Under the charged amount the invoice shows the details members ask about most:
- electricity: each reading interval with its start and end readings, the kWh and the price;
- losses and common consumer shares on their own lines;
- a fixed tariff: its basis, for example «50,00 ₴ × 6,00 сотки / 12»;
- an adjustment booked in this month for an earlier month: the month it corrects.

**Totals:** debt at the end of the month; prepayment («Аванс»); **«До сплати»** = the end debt minus money received
after the month but before the issue, never below 0,00.

**Received after the month:** payments received after the billing month but before the issue are listed under the
table as «Отримано після {місяць}» and lower «До сплати».

**QR block** (2.1), only when «До сплати» is above 0,00.

**Footer:** «Сплачуйте за реквізитами або QR-кодом; у призначенні вказуйте номер ділянки»; the chairman's and the
treasurer's names (no signature image); «Сформовано в системі Наділ {дата час}».

**Open month:** an invoice for a month that is not closed yet states «станом на {дата}».

**A closed month never changes:** a correction made after the month was closed shows up in the invoice of the month it
is booked in, with the month it corrects; the closed month's invoice and its re-issues keep the same numbers.

**Acceptance Criteria (worked examples; plot 9, charge types Membership then Electricity; October 2026; issued
05.11.2026):**
- [ ] Given start debt 0,00, October membership 75,00 and electricity 338,26, and no payments, when the October invoice
      is built, then it shows start 0,00; charged 413,26; paid 0,00; end 413,26; «До сплати» 413,26; QR amount 413,26.
- [ ] Given a September electricity debt of 1 135,08 and an October payment of 1 500,00 placed on 1 135,08 (September
      electricity) + 75,00 (membership) + 289,92 (October electricity), when the October invoice is built, then it shows
      start 1 135,08; charged 413,26; paid 1 500,00; end 48,34 (electricity); «До сплати» 48,34.
- [ ] Given example 1 plus a payment of 413,26 on 03.11, when the October invoice is built on 05.11, then the end of
      October is 413,26, «Отримано після жовтня» 413,26, «До сплати» 0,00 and there is no QR block.
- [ ] Given the plot has no debt at the end of October and a prepayment of 100,00, when the invoice is built, then
      «До сплати» is 0,00, «Аванс 100,00 ₴» is shown and there is no QR block.
- [ ] Given October was closed on 03.11 and on 06.11 a recalculation lowered an October charge by 20,00 (booked in
      November), when the October invoice is re-issued, then its numbers are the same as before, and when the November
      invoice is built, then it shows the −20,00 adjustment with «за жовтень 2026».
- [ ] Given the IBAN is missing, when any invoice is built, then the message names the missing IBAN (2.2).
- [ ] When an invoice PDF is opened, then all Ukrainian text (names, «До сплати», «Ділянка») is readable, with no missing
      or replaced characters.

### 2.4 Versions

- The app keeps every invoice it built with all its numbers and names. Opening it again shows exactly the same document,
  even after an owner change or later payments.
- **Re-issue:** after a payment, a correction or a recalculation, the treasurer can build the month's invoice again. It
  keeps the number and gets the next version («версія 2»). Older versions stay readable from the plot's invoice list.
- A member sees only the latest version of each invoice.
- An invoice is never deleted.

**Acceptance Criteria:**
- [ ] Given the October invoice of plot 9 (version 1), when a payment is entered and the treasurer re-issues it, then a
      version 2 with the new numbers appears, version 1 still opens with its old numbers, and the member sees version 2.
- [ ] Given an invoice was built and the plot's owner then changed, when the invoice is opened again, then it shows the
      owners as they were when it was built.
- [ ] When the same invoice version is downloaded twice, then the two PDFs are identical apart from the file's technical
      creation date.

### 2.5 Building the month's invoices

On the billing month screen, a «Рахунки» section.

- **Preview** «Сформувати рахунки за {місяць}»: how many plots get invoices (active plots that are not common land and
  have a current owner); how many have nothing due and are skipped by default (switch «Включити рахунки без боргу»);
  how many already have an invoice for this month — they get a new version only if their numbers changed.
- **Build:** the treasurer clicks «Сформувати» and waits on the screen (a spinner) until the result appears: built, new
  versions, unchanged, skipped. A cooperative of the largest supported size is built within the time recorded in the
  project's decision record on documents; a progress indicator is shown only if that measured time exceeds a few
  seconds. Running it again builds nothing new when nothing changed. Two builds of the same month at the same time are
  not possible: the second one is told a build is already running.
- An open month can be invoiced; the board usually invoices right after the month's charges.
- **List** of the month's invoices: plot, version, «До сплати», issued at, with a PDF link per row.
- **«PDF для друку»:** one PDF of the latest invoices of the month for the members who chose paper delivery (or all,
  with a switch), one invoice per A4 page, ordered by street and plot.

**Acceptance Criteria:**
- [ ] Given October has 20 invoiceable plots of which 5 have nothing due, when the treasurer opens the preview, then it
      shows 15 to build and 5 skipped.
- [ ] When the treasurer starts the build, then a spinner shows until the result appears, and the list shows one invoice
      per built plot.
- [ ] Given a build of the month is under way, when another user starts a build of the same month, then they see that a
      build is already running and nothing is built twice.
- [ ] Given October invoices exist and nothing changed, when the treasurer builds again, then the result says no new
      invoices or versions were created.
- [ ] Given one plot got a payment after the first build, when the treasurer builds again, then only that plot gets a
      version 2.
- [ ] Given «Включити рахунки без боргу» is on, when the treasurer builds, then plots with nothing due also get an
      invoice (without a QR block).
- [ ] When the treasurer clicks «PDF для друку», then one PDF downloads with one page per paper-delivery member's
      invoice in street and plot order; with the switch for all, every latest invoice of the month is included.

### 2.6 An invoice on request

On the plot card, a «Рахунки» tab lists the plot's invoices (month, version, «До сплати», issued) and has
«Рахунок на сьогодні»: it builds an invoice as of today for the current billing month with the same number scheme, as
a new version.

**Acceptance Criteria:**
- [ ] When the treasurer clicks «Рахунок на сьогодні» on plot 9, then a new invoice (or a new version of this month's
      invoice) appears in the list and its PDF opens with today's date as the issue date.

### 2.7 The debt certificate

On the plot card, a «Довідка» tab: a date (default today), then «Видати довідку».

The certificate shows: the cooperative's requisites; «Довідка № Д-{РРРР}-{N}», numbered in sequence per cooperative and
year, never reused; plot, area, current owners; the debt per charge type and in total, and the prepayment, as of that
date; «Заборгованість відсутня» when the total debt is 0,00, otherwise «Заборгованість становить {сума}»; the issuer
(the chairman's name and role), the date of issue, a place for a signature and a stamp. The numbers are the same as
the plot balances report for that date.

- An issued certificate never changes. A mistake is answered with a new certificate; the old one can be marked
  «анульовано» with a reason and stays in the register.
- **Register** (Документи → Реєстр довідок): number, plot, as-of date, debt, issued by, issued at, status; open the PDF;
  annul with a reason.

**Acceptance Criteria:**
- [ ] Given plot 9 owes 413,26 as of today, when the chairman issues a certificate, then it gets the next number of the
      year (for example `Д-2026-1`), its PDF says «Заборгованість становить 413,26 ₴» with the per-type amounts, and it
      appears in the register.
- [ ] Given a plot with no debt, when a certificate is issued, then it says «Заборгованість відсутня».
- [ ] Given two certificates are issued at the same moment by two people, when the register is opened, then they have two
      different consecutive numbers with no gap.
- [ ] When the chairman annuls a certificate with a reason, then the register shows it as «анульовано» with the reason,
      its PDF is marked «АНУЛЬОВАНО», and the next certificate still gets the next number.
- [ ] When the chairman tries to annul without a reason, then the annulment is refused with a message that the reason is
      required.
- [ ] Given a certificate was annulled, when the chairman tries to annul it again, then they see that it is already
      annulled.

### 2.8 The cash receipt

For a cash payment, «Квитанція» (A5 or half of A4): the cooperative's requisites; «Квитанція № {номер}»; the date;
plot; payer (the member's name, or the payer note); the amount in figures and in words in Ukrainian (for example
«сто гривень 00 копійок»); what the money was placed on; «Розподіл станом на {дата}»; «Прийняв(ла): {ім'я}» with a
signature line.

- One payment split over several plots gives one receipt listing every plot.
- A reversed payment's receipt carries «СТОРНОВАНО {дата}».
- The receipt number and amount never change; the placement shown is as of printing.
- After saving a cash payment, the screen with the large receipt number has «Друкувати квитанцію»; the payment card has
  it too. A bank payment has no receipt.

**Acceptance Criteria:**
- [ ] When the treasurer saves a cash payment of 100,00 and clicks «Друкувати квитанцію», then a PDF opens with the
      receipt number, «сто гривень 00 копійок», the charges it was placed on and the treasurer's name.
- [ ] Given a cash payment split over plots 7 and 15, when its receipt is printed, then one receipt lists both plots with
      their amounts.
- [ ] Given a reversed cash payment, when its receipt is printed, then it carries «СТОРНОВАНО» and the reversal date.
- [ ] Given a bank payment, when its card is opened, then there is no «Друкувати квитанцію».
- [ ] When amounts are written in words, then 0,05 is «нуль гривень 05 копійок», 1,00 «одна гривня 00 копійок», 21,00
      «двадцять одна гривня 00 копійок», 101,50 «сто одна гривня 50 копійок» and 1 234 567,89 «один мільйон двісті
      тридцять чотири тисячі п'ятсот шістдесят сім гривень 89 копійок» (and at least 30 cases in all).

### 2.9 What a member sees

- In the member account, «Рахунки»: the latest invoice of each of their plots first, each with «Завантажити PDF»; older
  months below.
- Next to their payments, a cash payment has «Квитанція».
- A member never sees certificates, nor any document of a plot they do not own.

**Acceptance Criteria:**
- [ ] Given the member owns plot 9 with an October invoice, when they open «Рахунки», then they see it first and can
      download its PDF.
- [ ] When a member tries to open another plot's invoice or receipt, then they get the same "not found" answer as for a
      plot that does not exist.
- [ ] When a member looks for certificates, then there is no such menu item and the register is refused.

### 2.10 Permissions

- **«Формування рахунків»** (new; Chairman and Treasurer by default): build, re-issue and print invoices.
- **«Видача довідок»** (new; Chairman): issue and annul certificates.
- Viewing invoices and the certificate register needs «Перегляд нарахувань».
- Printing a cash receipt needs «Перегляд оплат»; the payer note is printed only for users who may see member data.
- A member: their own plots' invoices and receipts only.
- Custom roles can be given the two new permissions on the roles screen.

**Acceptance Criteria:**
- [ ] Given a user without «Формування рахунків», when they open a billing month, then they see the invoices list but no
      build or print buttons, and a direct attempt is refused.
- [ ] Given the treasurer, when they open a plot card, then «Видати довідку» is not offered and a direct attempt is
      refused; the chairman can issue.
- [ ] When the roles screen is opened, then the two new permissions are listed with their names in the user's language.

### 2.11 Audit

The audit log records: invoices built for a month (month, count, skipped); an invoice re-issued; a certificate issued
(plot, number, as-of date, total debt); a certificate annulled (the reason). Downloads are not recorded.

**Acceptance Criteria:**
- [ ] When the treasurer builds October invoices and the chairman issues and annuls a certificate, then the audit log
      shows these three actions with their details in the user's language.

### 2.12 Demo data, docs and guides

- Demo cooperative: requisites with a clearly fictitious test IBAN that passes the checksum; invoices for the demo
  closed month; one certificate; one printed receipt.
- A sample invoice PDF of the demo cooperative is linked from both guides.
- The board guide (both languages) gets a chapter on invoices, certificates and receipts, including how to check a QR
  code with a phone; the owner manual covers requisites, fonts, the PDF engine and the permissions; the English guide
  says documents are always in Ukrainian.

**Acceptance Criteria:**
- [ ] When a demo user signs in, then the demo closed month has invoices, the register has one certificate and a cash
      payment's receipt can be printed.
- [ ] When the guides are opened, then both languages describe the new screens and link the sample invoice PDF.

### 2.13 Small fixes carried over from payments (015)

- The payment amount line «1 500,00 ₴ з 1 500,00 ₴» stays on one line.
- The line hint for an amount above the remaining money shows the amount: «Більше, ніж залишок 1 425,00 ₴».

**Acceptance Criteria:**
- [ ] When the treasurer enters a payment of 1 500,00 ₴, then «1 500,00 ₴ з 1 500,00 ₴» shows on one line.
- [ ] When a line amount exceeds the remaining money, then the hint names the remaining amount.

---

## 3. Scope and Boundaries

### In-Scope

- NBU payment QR code (format 003) on invoices; its documentation.
- Invoices per plot and month: build for a month (preview, build with the result, versions), on request,
  print PDF, lists on the month screen, the plot card and the member account.
- Debt certificates with a numbered register and annulment.
- Cash receipts for cash payments, with the amount in words.
- Two new permissions, audit actions, member access.
- Requisites warning banner.
- Demo data, domain docs, a decision record on the PDF engine, both guides in two languages, a sample invoice PDF.
- The two small 015 payment screen fixes (2.13).

### Out-of-Scope

- Sending documents by e-mail or Telegram (feature 017) and Viber.
- Online payment links.
- Electronic signature on certificates.
- The official КО-1 cash order form.
- One invoice for several plots of one owner.
- Reminders to debtors (phase 8).
- Mobile app screens (the mobile app is a shell until phase 5).

---

## Assumptions & Decisions

Decided with the owner (28.10.2026, in the requirement): monthly invoices plus on request; one cooperative account and
one QR per invoice for the whole amount due; a simple receipt, not КО-1; delivery is 017.

Decided by the pipeline (autonomy `minimal`, no blocking questions; recommended answers taken):

- The QR layout is taken from the official text of NBU Resolution No. 97 (Annex 4, format 003), downloaded from
  bank.gov.ua. Where it is stricter than the requirement, the official text wins: the amount is written in the
  shortest form (for example `UAH413.26`; whole hryvnias without `.00`), and lines end with LF only.
- The mandatory category/purpose field (field 10) carries `SUPP/SUPP`, the value of the official Annex 4 example 1
  «Оплата комунальних послуг» (decided by the owner on 28.10.2026). Annex 4 gives no default; its rule (the recipient
  fills ISO 20022 codes as CCCC/PPPP) and the example are quoted in the domain QR documentation.
- «До сплати» = end-of-month debt minus money received after the month and before the issue, never below 0,00
  (examples 1–4).
- Invoiceable plots: active, not common land, with a current owner on the issue day.
- «Змінилися цифри» for a re-build means any printed amount, line or owner differs; the issue date alone does not count.
- An on-request invoice for the current billing month is a new version of that month's invoice (same number).
- PDFs are not stored; they are rebuilt from the kept data on each download.
- The month build runs while the treasurer waits, like the month's charges run, and keeps only the documents' data, not
  PDFs (decided by the owner on 28.10.2026). Background jobs come with feature 017. Its duration at the largest supported
  cooperative size is measured and recorded; a progress indicator appears only if it exceeds a few seconds.
- Documents are always in Ukrainian; the screens follow the user's language.
- The receipt prints the payer member's name; the payer note only for users who may see member data; a member never
  sees another person's note.
- Certificates are issued by users with «Видача довідок» (Chairman by default); the certificate names the chairman as
  the issuer only when the issuing user is the chairman, otherwise the issuing user's name and role.
- An annulled certificate's PDF still opens, marked «АНУЛЬОВАНО».
- The demo test IBAN replaces the current one, is checksum-valid and clearly fictitious; the owner confirms it in the PR.
  For the real-bank QR check, the owner may enter a real account locally.
- Demo invoices are for the demo closed month (the demo months are relative to today, not fixed to October 2026).
- The two 015 screen fixes deferred to 016 by the owner are included (2.13).
- References that still call 016 "bank statement import" are corrected to the new feature order.

---

## Change Log

- [2026-10-28] — created by `/feature-pipeline` from `docs/features/016-invoices-and-documents.md`.
