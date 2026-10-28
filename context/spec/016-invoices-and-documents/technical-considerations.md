# Technical Specification: Invoices with the NBU QR code, debt certificates and cash receipts

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Implemented — pending manual check
- **Author(s):** Alex Usov (drafted by `/feature-pipeline` from the spring-backend, postgres-database, react-frontend and
  devops-infra proposals)

---

## 1. High-Level Technical Approach

A new Spring Modulith module **`documents`** (`app.nadel.documents`, planned in `context/product/architecture.md`) builds
three documents from data other modules own, through their public APIs only:

- **Invoice** = a stored, append-only **snapshot** (jsonb, codes and numbers; personal names sealed with the 014 cipher)
  per plot, billing month and version. The PDF and the QR image are rendered from the snapshot on every download and
  are never stored (this supersedes "object storage for invoice PDFs" in `architecture.md`).
- **Certificate** = a snapshot with a gapless number `Д-{YYYY}-{N}` from a per-year counter, annul-once.
- **Cash receipt** = rendered live from the payment group of 015 (no table).

The **month build is synchronous** in the request, like the 011 month run and the ADR 0012 import (owner decision
28.10.2026): one `REPEATABLE READ` transaction reads every plot's figures from one database snapshot, so a payment or
charge booked during the build is in all invoices of that build or in none (owner decision 28.10.2026). No JobRunr
(it comes with 017), no own job queue, no `invoice_run` table. The build is measured on the largest fixture and the
limit and the measurement go into **ADR 0017 "Documents"** as ADR 0012 did.

PDFs are drawn with **Apache PDFBox 3.0.8** (Apache-2.0) and a small own layout layer, with the embedded **IBM Plex Sans**
TTF (OFL-1.1). The QR matrix comes from **ZXing core 3.5.4**; the payload follows NBU Resolution No. 97, Annex 4 (format
003), recorded in `docs/domain/nbu-qr.md`.

Ledger, plots and members get a few new public read methods (§2.2). The web gets a month «Рахунки» panel, plot card
tabs, a certificate register, receipt buttons, member invoices and a requisites banner.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module layout (`app.nadel.documents`)

| Package / file | Responsibility |
|---|---|
| `documents/DocumentsFacade.java` (public) | API for 017: `latestInvoice(plotId, month)`, `invoicePdf(invoiceId)`, `invoiceForToday(plotId)`, `receiptPdf(paymentId)`, `printPdf(month, scope)`, `qrLink(invoiceId)`. Ids and bytes only |
| `documents/internal/InvoiceCalculator` | pure: `InvoiceLedger` + charge type facts → `InvoiceFigures` (rows, lines, totals, due) |
| `documents/internal/InvoiceSnapshots` | builds the snapshot JSON (schema 1), canonical form, content hash, seals `persons` |
| `documents/internal/InvoiceBuilder` | month build and on-request build (§2.5), versions, audit |
| `documents/internal/nbu/NbuQrPayload` | pure: invoice facts → 17-line payload, Base64URL, link (§2.6) |
| `documents/internal/nbu/NbuQrImage` | ZXing matrix + hryvnia circle geometry (§2.6); vector for PDF, PNG for `qr.png` |
| `documents/internal/nbu/HryvniaSign` | replaceable source of the sign outline (§2.6) |
| `documents/internal/AmountInWords` | pure Ukrainian amount in words |
| `documents/internal/pdf/*` | `PdfDocument` (A4/A5, fonts, fixed `/ID` and dates), `TextBlock`, `Table`, one layout class per document (`InvoiceLayout`, `CertificateLayout`, `ReceiptLayout`, `PrintBundle`) |
| `documents/internal/DocumentTexts`, `DocumentFormat` | Ukrainian bundle `resources/documents/messages_uk.properties` (ADR 0012 exception for generated files); `1 234,56 ₴` with U+00A0, `dd.MM.yyyy` Europe/Kyiv, month names nominative and genitive |
| `documents/internal/Certificates`, `DocumentCounter` | issue, register, annul, numbering |
| `documents/internal/CashReceipts` | assembly from `LedgerReadModel.cashReceipt` |
| `documents/internal/*Controller` | implement the generated `DocumentsApi` |
| `documents/internal/DemoDocuments` | dev/demo-profile `ApplicationRunner` that builds the demo documents (§2.9) |
| `resources/documents/fonts/` | `IBMPlexSans-Regular.ttf`, `IBMPlexSans-SemiBold.ttf`, `OFL.txt` |

`documents` depends on ledger, tariffs (`ChargeTypeCatalog`), settings, tenancy, plots, members, identity, privacy, audit,
money, problem. Nothing depends on it. `ModularityTests` must stay green.

### 2.2 New public reads in other modules

All are read-only, `Propagation.MANDATORY`, and return ids, codes and `Money`.

| Module | Method | Returns |
|---|---|---|
| ledger | `LedgerReadModel.invoiceLedgers(Collection<UUID> plotIds, YearMonth month, Instant asOf)` | `Map<UUID, InvoiceLedger>`; 3 queries per chunk |
| ledger | `LedgerReadModel.cashReceipt(UUID paymentId)` | `Optional<CashReceiptData>` (number, group, received on, entered by, source, parts with plot, amount, payer member, reversal, current allocations, unallocated) |
| plots | `PlotOwners.currentOwnerShares(Collection<UUID> plotIds)` | `Map<UUID, List<OwnerShare(memberId, numerator, denominator)>>` |
| members | `RoleHolders.activeHolders(String baseRoleCode)` | `List<MemberRef>` in name order (CHAIRMAN, TREASURER) |
| members | `MemberDirectory.deliveryChannels(Collection<UUID>)` | `Map<UUID, DeliveryChannel>` (new public enum EMAIL, TELEGRAM, VIBER, PAPER) |
| privacy | `IndexKind.DOCUMENT_CONTENT` | keyed blind index for the content hash |

`InvoiceLedger(plotId, month, status, closedAt, types[TypeMovement(chargeTypeId, start, charged, paid, end)],
lines[InvoiceChargeLine(chargeId, chargeTypeId, kind, source, amount, serviceFrom, serviceTo, parts, readings,
corrects)], prepaymentAtEnd, receivedAfter[LaterPayment(paymentId, receivedOn, bookedIn, amount)])`. `parts` and
`readings` are copied from the stored `charge.calculation` (tariff basis, quantity, unit, rate, days; reading points
with `takenOn` and `value`; losses percent, common consumer share).

### 2.3 Booking-month rule (pure `ledger/internal/MonthMovements`)

Documented in `money-rules.md` as the new section «Рахунки та довідки».

- A charge and a payment count in their own `billing_month` (bm).
- An allocation or a release counts in `pm = GREATEST(payment.bm, charge.bm, openMonthAt(row.created_at))`, where
  `openMonthAt(t)` is the month after the last month closed at or before `t`.
- Only rows created at or before `asOf` count.
- Per charge type: `start` = charges with bm < M − allocations with pm < M; `charged` = charges with bm = M; `paid` =
  allocations with pm = M; `end` = start + charged − paid.
- `prepaymentAtEnd` = payments with bm ≤ M − allocations with pm ≤ M, not below 0.
- `receivedAfter` = payments booked after M and created at or before `asOf`, net of reversals created by then.
- A closed month never changes: every row with pm ≤ M was created before M was closed, and the ledger is
  append-only (example 5: the −20,00 adjustment and its release land in November).

**Invoice figures:** `endDebt = Σ end`; `due = max(0.00, endDebt − Σ receivedAfter)`; QR only when `due > 0`. Types
whose four figures are 0.00 and that have no line are left out; order = the cooperative's charge type order.

### 2.4 Data model (V28, one file `V28__documents.sql`)

| Table | Key columns | Rules |
|---|---|---|
| `invoice` | `id` (app-assigned uuid), `tenant_id`, `plot_id` (composite FK to plot), `month date` (first day), `number text` (`2026-10/9`), `version int ≥ 1`, `snapshot jsonb`, `content_hash bytea`, `due numeric(14,2) ≥ 0`, `as_of timestamptz`, `issued_by` (member), `issued_at` | unique `(tenant_id, plot_id, month, version)`; index `(tenant_id, month, plot_id, version desc)`; append-only (`ledger_append_only()` trigger, `nadel_app` SELECT, INSERT only) |
| `certificate` | `id`, `tenant_id`, `year smallint`, `seq int`, `number text` (`Д-2026-1`), `plot_id`, `as_of date`, `snapshot jsonb`, `total_debt numeric(14,2)`, `issued_by`, `issued_at`, `annulled_at`, `annulled_by`, `annul_reason` | unique `(tenant_id, year, seq)`, `(tenant_id, number)`; CHECK annul columns all null or all set, reason 1–500 chars; `certificate_guard()` trigger: only null → set of the annul columns; no DELETE |
| `document_counter` | PK `(tenant_id, kind, year)`, `kind` CHECK IN ('CERTIFICATE'), `last_value bigint ≥ 0` | trigger allows only `+1` (as `receipt_counter`); no DELETE |

All: `tenant_id uuid not null`, RLS enabled, `tenant_isolation` policy (USING and WITH CHECK), index on `tenant_id`,
`unique (tenant_id, id)`. No FK from `documents` tables to ledger tables (the month is a `date`, as the module rule
forbids reading another module's tables).

**Permissions:** `INVOICES_ISSUE` (CHAIRMAN, TREASURER), `CERTIFICATES_ISSUE` (CHAIRMAN), seeded the V27 way
(`seed_base_roles` redefined + idempotent insert for existing cooperatives). Java: `Permission`, `BaseRoles`,
`PermissionCatalog`; contract enum; i18n.

### 2.5 Building invoices

**Month build** `POST /billing-months/{month}/invoices` (`preview=true|false`, body `{includeNothingDue}`):

1. Check requisites (IBAN, ЄДРПОУ) → 409 `REQUISITES_MISSING` with `missing: ["IBAN","EDRPOU"]`.
2. One `@Transactional(isolation = REPEATABLE_READ)` service call through `TenantAwareTransactionManager`;
   `asOf` = the transaction's start instant (`now()` of the snapshot, read once).
3. `pg_advisory_xact_lock` on (tenant, `invoices`, month) via a parameterised native query: one build per month; a second
   one waits (and then finds nothing changed). The plot-level on-request build takes the same month lock.
4. Invoiceable plots: `PlotCatalog.findChargeable` with a current owner, street order. Above the limit (§2.10) →
   422 `INVOICE_BUILD_TOO_LARGE` with `limit`.
5. Chunks of 50: `invoiceLedgers` → calculator → snapshot → content hash → compare with the latest version's hash →
   insert only when different; `flush()` + `clear()` per chunk.
6. Skip: a plot with nothing due is skipped unless `includeNothingDue` or it already has an invoice this month (then it
   is compared and gets a 0.00 version if its numbers changed).
7. One `INVOICES_ISSUED` audit event (month, built, newVersions, unchanged, skipped) in the same transaction.
8. Response `InvoiceBuildResult{invoiceable, built, newVersions, unchanged, skipped}`; the preview returns
   `InvoiceBuildPreview{invoiceable, toBuild, nothingDue, existing}` and writes nothing.

**Why `REPEATABLE READ` and not the charging lock:** it gives one consistent read for the whole build without blocking
payment entry or the month run; the 011 charging lock taken shared would also stop a charge from being booked during the
build but not a payment (payments take the plot lock, not the charging lock). A test proves the consistency (§4).

**Change detection:** canonical JSON of the snapshot without `version`, `asOf`, `issuedAt`, `issuedOn` and `qr`, with
the persons in plaintext, keys sorted → `PersonalDataCipher.blindIndex(tenantId, DOCUMENT_CONTENT, canonical)`. A keyed
hash leaks no names.

**On request** `POST /plots/{id}/invoices` (body `{month?}`, default the current billing month): same lock and isolation,
always a new version, `INVOICE_REISSUED` audit. 409 `PLOT_NOT_INVOICEABLE` for common land, archived or ownerless plots.

**Snapshot (schema 1):** `schema, number, version, month, monthStatus, asOf, issuedOn, cooperative{officialName,
edrpou, address, bankName, iban}, plot{id, number, street}, owners[{memberId, numerator, denominator}], persons
(sealed: owners' names, chairman, treasurer; FieldRef("invoice","snapshot.persons",id)), types[...], lines[...],
receivedAfter[...], totals{endDebt, prepayment, receivedAfter, due}, qr{link, purpose}|null`. Amounts are strings with
two decimals. The QR link is stored, so later code changes never change an old version.

### 2.6 NBU QR (format 003, Annex 4)

`docs/domain/nbu-qr.md` quotes Annex 4 table 2 and items 4.1–4.17, Annex 1 items 5–14, and records our values with the
source of each. Payload, 17 elements joined with LF only, no LF after the last:

| # | Element | Our value | Source |
|---|---|---|---|
| 1 | Службова мітка | `BCD` | table 2 |
| 2 | Версія формату | `003` | table 2 |
| 3 | Кодування | `2` (Windows-1251); `1` (UTF-8) only if a character is not in cp1251 | item 4.3; all official 003 examples use `2` |
| 4 | Функція | `XCT` | item 4.4; requirement |
| 5 | Унікальний ідентифікатор | empty | reserved (Р) |
| 6 | Отримувач | official name (fallback tenant name), ≤ 140 chars, cut at a word | table 2 |
| 7 | Рахунок | IBAN, 29 chars | table 2 |
| 8 | Сума / валюта | `UAH` + shortest form (`UAH413.26`, `UAH3`, no leading zeros) | item 4.8 |
| 9 | Код отримувача | ЄДРПОУ | table 2 |
| 10 | Категорія / ціль | `SUPP/SUPP` | item 4.10 (ISO 20022 codes, `CCCC/PPPP`, chosen by the recipient) + example 1 «Оплата комунальних послуг»; owner decision 28.10.2026 |
| 11 | Reference | invoice number, ISO 646 (a Cyrillic plot number is transliterated, `9а` → `9a`) | item 4.11 |
| 12 | Призначення | `Ділянка {n}. Внески за {місяць рік}. Рахунок {номер}`, shortened: month as `10.2026` → drop «Внески за …» → cut the name; plot and invoice number kept | item 4.12; requirement |
| 13 | Відображення | empty | optional |
| 14 | Код заборони зміни полів | `FEFF` (constant: everything except the amount) | item 4.14 and examples 1–2 |
| 15–17 | | empty | optional |

Base64URL without padding; encoded part ≤ 475 chars, whole link ≤ 507 bytes (Annex 4 items 8–9, QR version ≤ 17).
Before encoding: NBSP → space, typographic apostrophes → `'`.

**Image (Annex 1 items 5–13):** error correction M; QR version 10–17 (force 10 when the encoder picks less); quiet zone
4 modules; a white **background circle** centred on the code, diameter 17 / 19 / 21 / 23 / 25 modules for versions 10 /
11–12 / 13 / 14–15 / 16–17; the **hryvnia sign** inscribed in a circle 4 modules smaller. Module 0.5 mm in the PDF
(item 18), so the code is ≥ 30 mm. In the PDF the code is drawn as vector rectangles; `qr.png` uses the same geometry
(Java2D, 8 px per module).

**The sign (owner decision 28.10.2026):** the resolution PDF has the sign only inside raster example images (PDF pages
46, 49, 52 and 55, e.g. page 46 = Annex 4 sheet 11, a 500 × 500 px PNG); no vector drawing. So `HryvniaSign` uses the
outline of the glyph U+20B4 from the embedded IBM Plex Sans, behind a replaceable source: when the owner supplies the
official file as `docs/domain/nbu-hryvnia-sign.svg`, it is copied into the resources and used instead. The PR notes it.

### 2.7 Certificates and receipts

**Certificate** `POST /plots/{id}/certificates {asOf}` (default today, not in the future → 400 `INVALID`): figures from
`LedgerReadModel.debtsAsOf(asOf, …)` (the same as the plot balances report), area from `PlotCatalog`, owners with
shares, issuer name and role. Numbering in the issuing transaction, as the last step: `INSERT … ON CONFLICT DO NOTHING`
then `UPDATE document_counter SET last_value = last_value + 1 … RETURNING`; the row lock orders concurrent issues and a
rollback leaves no gap. The year = issue year in Europe/Kyiv. Annul `POST /certificates/{id}/annul {reason}`:
`UPDATE … WHERE annulled_at IS NULL`; 0 rows → 409 `CERTIFICATE_ALREADY_ANNULLED`; an annulled PDF carries «АНУЛЬОВАНО».

**Receipt** `GET /payments/{id}/receipt.pdf`: `LedgerReadModel.cashReceipt`; a BANK payment → 409
`RECEIPT_NOT_AVAILABLE`. Payer = the payer member's name; the payer note only when the viewer has `MEMBERS_VIEW`
(through `PaymentNotes`). «Прийняв(ла)» from `ActorNames(enteredBy)`. «СТОРНОВАНО {дата}» per reversed part, and on top
when all parts are reversed. «Розподіл станом на {сьогодні}». A5 page.

**Amount in words** `AmountInWords.of(Money)`: exact split of hryvnias and kopecks; triads with гривня and тисяча
(feminine), мільйон and мільярд (masculine) in one / few (2–4) / many forms, 11–14 always many; zero «нуль гривень»;
kopecks always two digits with the agreeing word; range 0 – 999 999 999 999.99; negative refused.

### 2.8 API contract (`api/openapi.yaml`, tag `documents`; contract first, then `pnpm gen:api`)

| Endpoint | Access | Result |
|---|---|---|
| `POST /billing-months/{month}/invoices?preview` | `INVOICES_ISSUE` | `InvoiceBuildPreview` / `InvoiceBuildResult`; 409 `REQUISITES_MISSING`; 422 `INVOICE_BUILD_TOO_LARGE` |
| `GET /invoices?month&plotId&page&size` | `CHARGES_VIEW` | page of `InvoiceRow` (plot, number, version, due, issuedAt) |
| `GET /invoices/{id}` · `/pdf` · `/qr.png` | `CHARGES_VIEW`, or a current owner and the latest version | `InvoiceSnapshot` / `application/pdf` / `image/png`; else 404 `NOT_FOUND` |
| `POST /plots/{id}/invoices` | `INVOICES_ISSUE` | `InvoiceRow`; 409 `PLOT_NOT_INVOICEABLE`, `REQUISITES_MISSING` |
| `GET /billing-months/{month}/invoices/print.pdf?scope=PAPER\|ALL` | `INVOICES_ISSUE` | `application/pdf` (one invoice per A4 page, street and plot order; PAPER = any current owner with PAPER delivery) |
| `GET /me/invoices` | member | latest version per plot and month, newest first |
| `GET /invoices/readiness` | `CHARGES_VIEW` | `{missing: [...]}` for the banner |
| `POST /plots/{id}/certificates` | `CERTIFICATES_ISSUE` | `CertificateRow` |
| `GET /certificates?status&page` · `GET /certificates/{id}/pdf` | `CHARGES_VIEW` (a member: 403) | register / PDF |
| `POST /certificates/{id}/annul` | `CERTIFICATES_ISSUE` | 400 `REQUIRED`/`TOO_LONG`; 409 `CERTIFICATE_ALREADY_ANNULLED` |
| `GET /payments/{id}/receipt.pdf` | `PAYMENTS_VIEW`, or a member owning every plot of the group | PDF; 409 `RECEIPT_NOT_AVAILABLE` |

PDF responses: `application/pdf`, `format: binary`, `Content-Disposition: attachment; filename=…`. Problem Details carry
`code` and parameters only. A foreign or old invoice for a member is the same 404 as a missing one.

### 2.9 Audit, demo data, docs

- **Audit** (outbox, `audit/README.md` pattern): `INVOICES_ISSUED`, `INVOICE_REISSUED`, `CERTIFICATE_ISSUED`,
  `CERTIFICATE_ANNULLED`; entity types `INVOICE`, `CERTIFICATE`; contract enums and i18n. Downloads are not audited.
- **Demo data:** in `R__demo_data.sql` replace the IBAN `UA213223130000026007233566001` by the fictitious
  `UA850000000000000000000123456` (bank code 000000, mod 97 verified; overwrite only the old demo value); add `invoice`,
  `certificate`, `document_counter` to the demo ledger wipe. Snapshots need the Java cipher, so a demo-profile
  `ApplicationRunner` (`DemoDocuments`) builds, under `TenantContext.runAs`, the invoices of the demo closed month and
  one certificate for plot 9, idempotently (skips when the month already has invoices).
- **Docs:** `docs/domain/nbu-qr.md`; ADR 0017 (PDF engine, fonts, synchronous build with the measurement and limit,
  JobRunr deferred to 017, the sign source) + row in `docs/adr/README.md` + a note in ADR 0007; `money-rules.md` section
  «Рахунки та довідки» with example 5; glossary (invoice version, certificate, register; ЄДРПОУ lives on the tenant);
  `architecture.md` (no PDF storage; documents module); module READMEs (`documents/README.md`, ledger, plots, members);
  stale "016 = bank statement import" references → the new numbering; both guides in uk/en, `capture-screens.mjs`,
  sample invoice PDF in `docs/guide/pdf/`, guide PDFs rebuilt.

### 2.10 Size limit and measurement

`InvoiceBuildLimitTests` (Testcontainers) builds a fixture of **2,000 invoiceable plots** (the import limit), 2 charge
types, 12 months of charges, payments on half the plots, shared ownership; it measures the first build, a rebuild with
no change and the on-request build, and checks loosely against a ceiling as the import tests do. ADR 0017 records the
numbers and the limit (proposed 2,000 plots). **Progress UI:** if the measured first build at the limit is at most about
5 s, the web shows a spinner only; if it is longer, this goes back to the owner (lower limit or 017 JobRunr), and the
pipeline records it.

### 2.11 Web (`web/`, `packages/shared`)

- `ChargesMonthPage` → `components/invoices/MonthInvoicesPanel.tsx`: preview counts with the «Включити рахунки без
  боргу» switch, build button with a spinner and the result, `InvoiceList` (plot, number, version, due, issued, PDF),
  «PDF для друку» with a `PAPER | ALL` switch. Build and print need `INVOICES_ISSUE`; the list needs `CHARGES_VIEW`.
- `PlotPage` → new `PlotTabs` (existing account, «Рахунки» = `PlotInvoices` with «Рахунок на сьогодні», «Довідка» =
  `CertificateForm` gated by `CERTIFICATES_ISSUE`).
- `pages/CertificatesPage.tsx`, route `documents/certificates`, nav item «Реєстр довідок» (`CHARGES_VIEW`; annul button
  with `ConfirmDialog` and a reason for `CERTIFICATES_ISSUE`).
- `components/payments/ReceiptButton.tsx` on `PaymentSaved`, `PaymentGroupSaved` and `PaymentPage` (CASH only).
- `AccountPage` → `MyInvoices` (latest per plot first, «Завантажити PDF») and «Квитанція» next to member cash payments.
- `RequisitesWarning` (from `GET /invoices/readiness`, and on `REQUISITES_MISSING`) on the invoice screens and
  `RequisitesPanel`; ЄДРПОУ missing → «зверніться до оператора платформи».
- PDF download: orval blob functions through `nadelFetch` + `downloadBlob`; file names built on the client
  (`invoice_2026-10_plot-9_v2.pdf`, `invoices_2026-10_paper.pdf`, `certificate_D-2026-1.pdf`, `receipt_42.pdf`).
- i18n (uk, en): `invoices.*`, `certificates.*`, `receipts.*`, permission names, audit actions and entity types,
  problem codes. Document texts stay in the backend bundle.
- 015 polish: `whitespace-nowrap` on the «X з Y» amount pair; the over-remaining hint gets the server's remaining amount
  as a parameter.

---

## 3. Impact and Risk Analysis

- **System dependencies:** ledger (new read methods; `MonthMovements` must agree with the plot account and reports),
  plots, members, privacy (new index kind), settings, tenancy, audit. 017 will consume `DocumentsFacade`.
- **Risks and mitigations:**
  - *QR not accepted by banks* (field 17 empty vs `RFU` in the examples; the sign glyph): decode tests on rendered
    pages; the owner's two-bank scan before merging; field 17 switch to `RFU` is a one-line change.
  - *Booking-month figures differ from the plot account:* examples 1–5 as unit tests plus an integration test that
    compares the invoice end debt with `debtsAsOf` at month end for a closed month.
  - *Build too slow at the limit:* measured (§2.10); chunks with flush/clear; the advisory lock blocks only the same month.
  - *REPEATABLE READ serialisation errors:* the build only reads ledger rows and inserts into `invoice`, so no
    write-write conflict on shared rows; a failure rolls back and the build can be repeated.
  - *Supply chain:* PDFBox 3.0.8 and ZXing 3.5.4 pinned exactly; OSV-Scanner must stay green; no openhtmltopdf, no
    Batik. A new advisory goes to `osv-scanner.toml` only with a reason (never to make CI pass silently).
  - *Byte-stable PDFs:* fixed `/ID` (first 16 bytes of SHA-256 of the snapshot), creation and modification dates =
    `issuedAt`, deterministic font subsetting; a test renders twice and compares bytes.
  - *Fonts:* a test checks every character of the bundle and of a snapshot has a glyph (incl. `₴`, `’`, `«»`).
  - *Personal data:* names only sealed in snapshots; never logged; the content hash is keyed.
  - *Annex 1 item 16* (invoice text = QR content): the PDF prints the full official name; a name longer than 140
    characters is cut only in the QR (recorded in nbu-qr.md).

---

## 4. Testing Strategy

- **Unit (pure):** `MonthMovements` + `InvoiceCalculator` with worked examples 1–5; the requisites check (example 6);
  `AmountInWords` with ≥ 30 cases incl. example 7; `NbuQrPayload` field by field against Annex 4 table 2 (byte dump,
  `FEFF`, `SUPP/SUPP`, no padding, ≤ 507 bytes, cp1251 bytes, shortening of example 8 on a long name / the UTF-8 path);
  circle diameter per QR version; `DocumentFormat`.
- **PDF:** byte-identical double render; `PDFTextStripper` finds the Ukrainian texts; glyph coverage; A4/A5 sizes; a
  rendered invoice page (300 dpi) decodes with ZXing (`javase`, test scope) back to the stored link.
- **Integration (Testcontainers):** month build, rebuild without change, a payment → one version 2; **consistency:** a
  payment committed by another transaction while a build is between two chunks is in none of that build's invoices, and
  the next build picks it up (test hook between chunks); example 5 end to end; concurrent certificates numbered 1 and 2,
  rollback leaves no gap; annul twice; receipt for a split group and a reversed payment; `InvoiceBuildLimitTests`.
- **Security:** allowed and denied per endpoint and permission; member own plot vs foreign plot vs old version (404);
  certificates refused for members; isolation tests for `invoice`, `certificate`, `document_counter`; append-only and
  annul-once DB tests; `SeedBaseRolesTests`, `RolesTests`, `DemoAccessChecks`.
- **Web:** typecheck, lint, unit tests of new components; e2e `web/e2e/invoices.spec.ts` (treasurer builds the demo
  month and downloads plot 9's PDF `@smoke`; chairman issues and annuls a certificate; treasurer prints a cash receipt;
  member downloads their invoice; foreign access 404); PDF asserted by `%PDF-` magic bytes and size.
- **Manual (`[?]`):** the owner's scan with two real banking apps.

---

## Assumptions

- **Assumption:** QR encoding `2` (Windows-1251) as in every official 003 example; UTF-8 only as fallback.
- **Assumption:** field 17 is left empty (table 2 says optional); the official examples print `RFU` — switched if a bank
  rejects it.
- **Assumption:** the requirement's purpose template has no street, so example 8's shortening is tested with a long
  official name and on the UTF-8 path.
- **Assumption:** «До сплати» does not subtract the prepayment (spec §3.1); with auto-allocation off a plot can show both
  a due amount and a prepayment.
- **Assumption:** a plot goes into the PAPER print set if any current owner chose paper delivery.
- **Assumption:** an on-request invoice always creates a new version (`INVOICE_REISSUED`, also for version 1).
- **Assumption:** `CHARGES_VIEW` is enough to see owners' names on an invoice (as on the plot account).
- **Assumption:** the certificate register is visible with `CHARGES_VIEW`; issuing and annulling need
  `CERTIFICATES_ISSUE`.
- **Assumption:** if the official name is empty, the tenant name is used (the banner does not require it).
- **Assumption:** months in `documents` tables are `date` values; no FK to ledger tables (module rule).
- **Assumption:** JobRunr deferred to 017 (owner); the requirement's `invoice_run` table and progress are dropped.
- **Assumption:** the sign uses the U+20B4 glyph outline until the owner supplies the official file.
- **Assumption:** the demo documents are built by a demo-profile runner, not in SQL (the cipher needs Java).
