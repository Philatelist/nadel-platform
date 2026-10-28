# 016. Invoices with the NBU QR code, debt certificates and cash receipts

Roadmap phase 4, feature 1 of 2. Input for `/feature-pipeline`. Requires 011 (ledger, month run), 013 (cooperative
requisites, reports) and 015 (payments, allocations, receipts).

The owner changed the order on 28.10.2026:
- `016 Invoices and documents`;
- `017 Delivery by e-mail and Telegram`;
- `018 Cash book and reconciliation` (planned before as 017);
- then the member account and mobile, the bank statement import, SaaS and automation, and the map last.

Deployment is manual until then.

## 1. Goal

A member gets a monthly **invoice** for each plot. The invoice holds the debt at the start of the month, what was
charged and paid in the month, what is due, the cooperative's bank details and the **NBU payment QR code**. A banking
app scans the code and fills in the transfer. The board also issues a **debt certificate** for a plot as of a date (for
a sale, money-rules 8), and the treasurer prints a **cash receipt** for a cash payment of 015.

Every document is a PDF that the system builds from data it stores, so the same document can be built again
identically. Sending documents by e-mail and Telegram is feature 017.

**Decided with the owner (28.10.2026):**
- **Invoices** are monthly, after the month's charges, plus an invoice on request on any day.
- **Bank account:** one cooperative account (IBAN) for everything. The purpose names the plot. One QR code per invoice
  carries the whole amount due.
- **Cash receipt:** a simple PDF receipt, not the standard КО-1 form (прибутковий касовий ордер, the cash receipt
  order).
- **Delivery channels (017):** e-mail and Telegram first. Viber needs a paid contract and is later. Paper stays possible
  through one print-ready PDF of all paper invoices.

## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- `docs/domain/money-rules.md` sections 1, 2, 3.1, 7, 8 and 9;
- `docs/domain/glossary.md` (Invoice);
- the READMEs of `ledger`, `reports` (requisites, the read model), `settings`, `members`, `plots` and `privacy`;
- ADRs 0006, 0007, 0013, 0015 and 0016;
- the guides in `docs/guide/`.

General rules as in earlier features:
- contract-first;
- Flyway only;
- tenant-scoped tables with RLS and isolation tests;
- permissions, not role names;
- Problem Details with a `code`;
- i18n for every screen text;
- audit through the outbox;
- only the server calculates money;
- every worked example is a unit test;
- the verification levels and the guides rules of `CLAUDE.md`.

**Language of documents.** Official documents are in Ukrainian, whatever the user's UI language. The screens that list
and build them follow the UI language as usual. The English guide says so.

## 2. The NBU payment QR code

**Standard:** the NBU rules for forming the data structure and the image of the QR code for credit transfers, Resolution
of the NBU Board No. 97 of 19.08.2025, in force from 01.11.2025: **format version 003**. The data structure:
- starts with the service mark `BCD` and the version `003`, one field per line, with LF line endings;
- is encoded with Base64URL behind the start link `https://qr.bank.gov.ua/`;
- is at most 507 bytes (QR version 17).

**What Phase 1 has to establish.** The official text gives the exact field order, the encoding field, the amount format,
the function codes `UCT`/`ICT`/`XCT` and the bitmask of fields the payer may not change. Phase 1 must get them from that
text and record them in `docs/domain/nbu-qr.md`, quoting the field table. If the agent cannot download the text, the
pipeline stops and asks the owner to put the PDF into `docs/domain/`. The field layout is never guessed. Open-source
implementations of format 003 may be used to cross-check, never as the source.

**What our code fills:**

| Field | Value |
|---|---|
| Function | `XCT` (the payer chooses a regular or an instant transfer) |
| Recipient | the cooperative's full official name (013 requisites; shortened by the rules if longer than allowed) |
| Account | the IBAN from the requisites |
| Amount | the amount due, `UAH` with two decimals; absent when nothing is due |
| Recipient code | the ЄДРПОУ (from the tenant) |
| Purpose | `Ділянка {number}. Внески за {місяць рік}. Рахунок {invoice number}`, at most the allowed length |
| Reference | the invoice number |
| Locked fields | recipient, account, recipient code and purpose: the payer cannot change them, so phase 3 matching (the bank statement import) can read the plot from the purpose. The amount stays editable, so a member can pay part of it |

**Validation.** Without an IBAN or ЄДРПОУ in the requisites, invoices cannot be built: 409 `REQUISITES_MISSING`, with
the list of missing fields and a link to Settings → Requisites.

**Generating the code:**
- The image is built on the server with ZXing (Apache 2.0) at error correction level M, with a quiet zone of at least 4
  modules.
- In the PDF it is at least 30 × 30 mm, with the text «Відскануйте QR-код у застосунку свого банку» under it and the link
  text printed under that.

**Owner's check before merging:** scan the QR code of a demo invoice with two real banking apps. The transfer form must
open with the recipient, the IBAN, the purpose and the amount filled in, and the locked fields not editable. The result
is written in the PR.

## 3. The invoice

### 3.1 Content (one invoice = one plot, one billing month)

**Header:**
- the cooperative's official name;
- ЄДРПОУ, address, bank and IBAN;
- «Рахунок № {YYYY-MM}/{plot number}», for example `2026-10/9`. The number is deterministic: one invoice per plot and
  month; a re-issue keeps the number and adds a version, «(версія 2)»;
- the date of issue;
- the billing month.

**To:**
- plot number and street;
- the current owners (full names) with their shares.

**Table per charge type**, in the cooperative's charge type order. Columns:
- debt at the start of the month;
- charged in the month (regular charges, adjustments and reversals booked in that billing month);
- paid in the month (allocations of payments booked in that month);
- debt at the end of the month.

The charged column also shows the details members ask about most:
- for electricity: each reading interval with its start and end values, the kWh and the price;
- losses and common consumer shares on their own lines;
- for a fixed tariff: the basis, for example «50,00 ₴ × 6,00 сотки / 12».

**Totals:**
- debt at the end of the month;
- prepayment;
- **«До сплати»** = the end debt (0.00 when there is none).

**The QR block** of section 2, shown when «До сплати» is above 0.00.

**Footer:**
- «Сплачуйте за реквізитами або QR-кодом; у призначенні вказуйте номер ділянки»;
- the chairman's and treasurer's names from the roles (no signature image);
- «Сформовано в системі Наділ {date time}».

**Amounts.** Every amount comes from the ledger's read model (013), as of the end of the billing month (end-of-month
balances) and as of the issue moment (payments received after the month but before the issue are listed below the table
as «Отримано після {місяць}» and reduce «До сплати»). The worked examples fix this.

### 3.2 Snapshot and versions

- Building an invoice stores its **snapshot**: every number, name and requisite shown, as JSON with codes and numbers. The
  owners' names are encrypted with the `privacy` cipher, like other personal data, since the snapshot outlives a change
  of owner.
- The PDF is rendered from the snapshot on demand and is not stored, so it always looks the same.
- **Re-issue:** after a payment, a correction or a recalculation, the treasurer can build the month's invoice again. A
  new version is stored, the old version stays readable, and the member sees the latest.
- An invoice is never deleted.

### 3.3 Building invoices

- **For the month:** on the billing months screen, «Сформувати рахунки за {місяць}» with a preview. The preview shows
  how many plots get invoices (active, not common land, with a current owner), how many have nothing due and are
  skipped by default (switch «Включити рахунки без боргу»), and how many already have an invoice for this month and will
  get a new version only if their numbers changed.
- **Running it:**
  - the build runs as a background job (ADR 0007) in batches of 50 plots and is idempotent;
  - progress is shown;
  - an open month can be invoiced (the board usually invoices right after the month run). The invoice then states «станом
    на {дата}» and a re-issue after more changes is a new version.
- **On request:** the plot card → «Рахунок на сьогодні». This builds an invoice as of today (its month = the current
  billing month) with the same number scheme, as a new version.
- **Print:** «PDF для друку» joins the latest invoices of the month for the members whose delivery channel is `PAPER` (or
  all, with a switch) into one PDF, one invoice per A4 page, ordered by street and plot.

## 4. The debt certificate

- **«Довідка про стан розрахунків»** for one plot as of a date (default today):
  - the cooperative's requisites;
  - certificate number `Д-{YYYY}-{sequence}`, sequential per cooperative and year, never reused;
  - plot, area, current owners;
  - the debt per charge type and in total, and the prepayment, as of that date;
  - the sentence «Заборгованість відсутня» when the debt is 0.00, otherwise «Заборгованість становить {сума}»;
  - who issued it (the chairman's name and role), the date of issue, a place for a signature and a stamp.
- It is built from the ledger's balance as of the date (money-rules 2), the same read model as the plot balances report
  (013).
- It is stored as a snapshot like an invoice, with a **register of certificates** (number, plot, date, amount,
  issued by).
- An issued certificate is never changed. A mistake is answered by a new certificate; the old one can be marked
  «анульовано» with a reason, and it stays in the register.

## 5. The cash receipt

- For a `CASH` payment of 015, «Квитанція» (PDF, A5 or half of A4):
  - the cooperative's requisites;
  - «Квитанція № {receipt number}»;
  - the date;
  - plot;
  - payer (member name or the decrypted note);
  - amount in figures and in words (Ukrainian, «сто гривень 00 копійок»; a pure, unit-tested function);
  - what it was allocated to;
  - «Прийняв(ла): {treasurer name}» and a signature line.
- A split payment group gives one receipt with every plot.
- A reversed payment's receipt is printed with a «СТОРНОВАНО {дата}» mark.
- It is built from the payment and its allocations at the moment of printing. The receipt number and amount never
  change, but the allocations can, so the receipt shows «Розподіл станом на {дата}».
- After saving a cash payment, the 015 screen with the large receipt number gets «Друкувати квитанцію».

## 6. Worked examples (unit tests of the invoice calculator and the amount in words)

Plot 9, charge type order Membership, Electricity. October 2026, issued 05.11.2026.

| # | Situation | Invoice for October |
|---|---|---|
| 1 | Start debt 0.00. October: membership 75.00, electricity 338.26. No payments | start 0.00; charged 413.26; paid 0.00; end 413.26; due 413.26; QR amount 413.26 |
| 2 | Start debt: electricity 1135.08 (September). October payment 1500.00 allocated 1135.08 + 75.00 membership + 289.92 electricity October | start 1135.08; charged 413.26; paid 1500.00; end 48.34 (electricity); due 48.34 |
| 3 | Example 1 and a payment 413.26 on 03.11 (November) | end of October 413.26; «Отримано після жовтня» 413.26; due 0.00; no QR block |
| 4 | Overpaid: end debt 0.00, prepayment 100.00 | due 0.00; «Аванс 100,00 ₴»; no QR block |
| 5 | October is closed on 03.11. On 06.11 a recalculation adjusts an October charge by −20.00 (booked in November, 012) | the October invoice does not change, and a re-issue gives the same numbers; the November invoice shows the −20.00 adjustment with its original period |
| 6 | IBAN missing in requisites | 409 `REQUISITES_MISSING` with `iban` |
| 7 | Amount in words: 0.05; 1.00; 21.00; 101.50; 1 234 567.89 | «нуль гривень 05 копійок», «одна гривня 00 копійок», «двадцять одна гривня 00 копійок», «сто одна гривня 50 копійок», «один мільйон двісті тридцять чотири тисячі п'ятсот шістдесят сім гривень 89 копійок» |
| 8 | Purpose longer than the field allows (long street) | shortened by the rules; the plot number and invoice number always kept |

The rule behind example 5: a closed month's invoice never changes. A later correction appears in the invoice of the
month it is booked in (money-rules 3.1 and 5.4).

## 7. PDF engine

**Decide in Phase 1 and record in ADR 0017 "Documents".** The options:
- OpenHTMLtoPDF (HTML and CSS templates over PDFBox);
- PDFBox directly;
- OpenPDF.

The criteria:
- licence compatible with a closed SaaS;
- Cyrillic with an embedded font (the design system's font if its licence allows embedding, otherwise Noto Sans; the
  font file is in the repository with its licence);
- A4 and A5 layout;
- the size of the dependency;
- the `Security` CI job stays green.

Templates live in the backend resources, one per document. They are not uploaded by users; those are the 013 report
templates, a different mechanism. The design follows 003: calm, black text, one accent colour, legible at 100 % print.

## 8. API (`api/openapi.yaml`, tag `documents`)

**Invoices:**
- `POST /billing-months/{id}/invoices?preview=true|false`;
- `GET /invoices?month&plotId&status`;
- `GET /invoices/{id}`: the snapshot as JSON;
- `GET /invoices/{id}/pdf`;
- `GET /invoices/{id}/qr.png`;
- `POST /plots/{id}/invoices`: on request;
- `GET /billing-months/{id}/invoices/print.pdf?scope=paper|all`.

**Certificates:**
- `POST /plots/{id}/certificates`;
- `GET /certificates`: the register;
- `GET /certificates/{id}/pdf`;
- `POST /certificates/{id}/annul`.

**Receipts:** `GET /payments/{id}/receipt.pdf`.

**Members:** a member reads the invoices and receipts of their own plots (`GET /me/invoices`, and the same PDF
endpoints, which check ownership as the account does).

## 9. Data

- `invoice`: plot, billing month, number, version, snapshot `jsonb` with the owners' names encrypted inside, due amount,
  issued by, issued at. Append-only.
- `invoice_run`: the month build job and its progress.
- `certificate`: number, plot, as of, snapshot, issued by, annulled at, annulled by, annul reason. Append-only except the
  annul columns, which are set once.
- `document_counter`: certificate numbers per year.

All tables are tenant-scoped with RLS and isolation tests.

## 10. Permissions

- **New `INVOICES_ISSUE`** (Chairman, Treasurer): build, re-issue and print invoices.
- **New `CERTIFICATES_ISSUE`** (Chairman): issue and annul certificates.
- **Viewing invoices and the register** needs `CHARGES_VIEW`.
- **Cash receipts** need `PAYMENTS_VIEW`.
- **A member** sees and downloads the invoices and receipts of their own plots, never certificates (the board hands
  those over).

## 11. Web UI (style of 003)

1. **Billing months → a month:** «Рахунки»: the preview, the build with progress, the list of the month's invoices
   (plot, version, due, issued) and «PDF для друку».
2. **Plot card:**
   - «Рахунки» (the list and «Рахунок на сьогодні»);
   - «Довідка» (a date, then «Видати довідку»).
3. **Документи → Реєстр довідок** (the register of certificates), with annulment.
4. **Payment card:** «Друкувати квитанцію».
5. **Member account:** «Рахунки», the latest invoice per plot first with «Завантажити PDF», and receipts next to the
   payments.
6. **Settings → Requisites:** a warning banner when IBAN or ЄДРПОУ is missing, also shown on the invoice screens.

## 12. Audit

- `INVOICES_ISSUED`: month, count, skipped.
- `INVOICE_REISSUED`.
- `CERTIFICATE_ISSUED`: plot, number, as of, total debt.
- `CERTIFICATE_ANNULLED`: the reason.

Downloads of PDFs are not audited. Documents carry personal data, but only for people allowed to see it, and the
snapshots are encrypted.

## 13. Docs and guides

- `docs/domain/nbu-qr.md`.
- ADR 0017.
- `money-rules.md`: a section «Рахунки та довідки» on which balance each number is, and example 5.
- Glossary: invoice version, certificate, register.
- **Guides, both languages:**
  - the board guide chapter on invoices, certificates and receipts, with how to check a QR code with a phone;
  - the owner manual: requisites, fonts, the PDF engine, permissions;
  - new screens in `capture-screens.mjs`;
  - a sample invoice PDF of the demo cooperative in `docs/guide/pdf/` (both guides link to it).
- **Demo data:**
  - requisites with a test IBAN that passes the checksum but is clearly a test (owner to supply or confirm; never a real
    account);
  - October invoices for the demo plots;
  - one certificate;
  - one printed receipt.

## 14. Out of scope

- sending (017);
- online payment links;
- e-signature (КЕП) on certificates;
- the КО-1 cash order form;
- invoices for several plots of one owner in one document (one per plot; 017 can send them together);
- Viber;
- reminders to debtors (phase 8).

## 15. Acceptance criteria

- [ ] `docs/domain/nbu-qr.md` quotes the official field table of format 003. A unit test builds the payload of a demo
      invoice and checks it field by field against it. The owner scanned the demo QR with two banking apps (noted in
      the PR).
- [ ] Every row of section 6 is a passing unit test, and the amount-in-words function has its own table of at least 30
      cases.
- [ ] The month build is idempotent, runs in batches with progress, skips nothing-due plots by default, and creates
      versions only on change.
- [ ] Invoices, certificates and receipts are rebuilt from snapshots identically (byte-stable PDF apart from the PDF's
      creation metadata).
- [ ] Certificate numbers have no gaps or duplicates under concurrency. Annulment keeps the register.
- [ ] Permissions and member access as in section 10, with allowed and denied tests per endpoint. Isolation tests. Audit
      as in section 12. The owners' names in snapshots are encrypted.
- [ ] The PDFs render Cyrillic with the embedded font. A visual check of each document is attached to the PR as
      screenshots.
- [ ] Docs, both guides (two languages), the sample PDF and the demo data are present.
- [ ] `make check` is green. e2e:
      - the treasurer builds October invoices and downloads plot 9's PDF (`@smoke`);
      - the chairman issues a certificate for plot 9;
      - the treasurer prints a cash receipt;
      - the member downloads their invoice.
