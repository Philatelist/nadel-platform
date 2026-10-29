# documents

Printable documents built from what the other modules have booked (feature 016): the **cash receipt** of a payment
and the **invoice** of a plot for a billing month with the NBU payment QR code (on request, and for every plot of a billing month
with the print set for paper delivery) and the **debt certificate** of a plot as of a day with its register and annulment. Every document is a PDF in Ukrainian (an official paper, whatever language the user's screens are in), drawn with
Apache PDFBox and the embedded IBM Plex Sans (ADR 0017). Requirements: `docs/features/016-invoices-and-documents.md`;
specs: `context/spec/016-invoices-and-documents/`.

The module adds no money rule (the invoice's figures are the booking-month rule of the ledger, money rules 13). It
owns the tables `invoice`, `certificate` and `document_counter` (V28, all under RLS `tenant_isolation`; `invoice`
append-only, see below). It reads only through the public APIs of the modules that own the data (ledger, plots,
members, audit, tariffs, settings, tenancy, identity, privacy) and never their tables (ADR 0001). Nothing depends on
it.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /payments/{id}/receipt.pdf` | `getPaymentReceiptPdf` | `PAYMENTS_VIEW`, or a member who owns every plot of the payment's group now |
| `POST /plots/{id}/invoices` (body `{month?}`) | `issuePlotInvoice` | `INVOICES_ISSUE` |
| `GET /invoices?month&plotId&latestOnly&page&size` | `listInvoices` | `CHARGES_VIEW` |
| `GET /invoices/readiness` | `getInvoiceReadiness` | `CHARGES_VIEW` |
| `GET /invoices/{id}` · `/pdf` · `/qr.png` | `getInvoice`, `getInvoicePdf`, `getInvoiceQrPng` | `CHARGES_VIEW`, or a current owner of the plot and only the latest version (else 404) |
| `GET /me/invoices` | `listMyInvoices` | anyone in a cooperative: the latest version per month of the plots they own now (empty without a plot); the operator 403 |
| `POST /billing-months/{month}/invoices?preview` (body `{includeNothingDue?}`) | `buildMonthInvoices` | `INVOICES_ISSUE` |
| `GET /billing-months/{month}/invoices/print.pdf?scope=PAPER\|ALL` | `getMonthInvoicesPrintPdf` | `INVOICES_ISSUE` |

| `POST /plots/{id}/certificates` (body `{asOf?}`) | `issuePlotCertificate` | `CERTIFICATES_ISSUE` |
| `GET /certificates?status&page&size` | `listCertificates` | `CHARGES_VIEW` |
| `GET /certificates/{id}/pdf` | `getCertificatePdf` | `CHARGES_VIEW` |
| `POST /certificates/{id}/annul` (body `{reason}`) | `annulCertificate` | `CERTIFICATES_ISSUE` |

The receipt and invoice endpoints are implemented by `DocumentsController` (the generated `DocumentsApi`), the
certificate endpoints (tag `certificates`) by `CertificatesController` (the generated `CertificatesApi`).

The receipt endpoint carries no `@PreAuthorize`: a member holds no permission for it, so `CashReceipts` decides, in
this order:

1. The platform operator (no cooperative data) → 403 `PERMISSION_DENIED`.
2. Without `PAYMENTS_VIEW`: a caller who owns no plot now (the demo electrician, a custom role without the permission)
   → 403 `PERMISSION_DENIED`, before the payment is looked up, so the answer is the same whatever the id.
3. `LedgerReadModel.cashReceipt(id)`: an unknown id, a payment of another cooperative (RLS) and the id of a payment
   reversal row → 404 `NOT_FOUND`.
4. Without `PAYMENTS_VIEW`: a group with a plot the member does not own now → the same 404 `NOT_FOUND`.
5. A bank payment → 409 `RECEIPT_NOT_AVAILABLE` (after the access checks, so a member learns nothing about another
   plot's payment).

The answer is `application/pdf`, `Content-Disposition: attachment; filename="receipt_{number}.pdf"`,
`Cache-Control: no-store`. Downloads are not audited (feature 016 §2.11).

## The cash receipt (feature 016 §2.8)

`CashReceipts` reads the group of the payment (`ledger.LedgerReadModel#cashReceipt` → `CashReceiptData`: number,
source, day received, who entered it, the payer, every part with its plot, amount, reversal and where its money is
now), names it and draws it with `ReceiptLayout` on one A5 page:

- the requisites: the official name (the cooperative's name when none was entered), `Код ЄДРПОУ` (from
  `tenancy.TenantDirectory`, set by the platform operator), the legal address, the IBAN and the bank (from
  `settings.CooperativeSettings#requisites`), each only when set;
- «Квитанція № {number}» and the day received;
- «СТОРНОВАНО {day}» in a frame when every part is reversed (the day of the last reversal, in Kyiv);
- «Платник: {full name}» of the payer member («не вказано» when none is named) and «Примітка платника» only for a
  viewer with `MEMBERS_VIEW` (through `ledger.PaymentNotes`);
- the parts in street and plot order (`PlotCatalog.inStreetOrder`) with their amounts and «СТОРНОВАНО {day}» per
  reversed part, and «Разом»;
- «Сума прописом» (`AmountInWords`);
- «Розподіл станом на {today}»: per part the charges its money is on now (charge type name, the month or the service
  days, the amount), «Не розподілено (передоплата)» for what is left, «Сторновано {day}» for a reversed part;
- «Прийняв(ла): {name}» of the member who entered the payment (`audit.ActorNames`) with a signature line.

The number and the amounts never change; the placement is the one at the moment of printing. Nothing is stored. The
file's `/ID` is derived from the content and its dates are the moment it was printed, so the same payment printed at
the same moment gives the same bytes.

## The invoice (feature 016 §2.3 to §2.6)

**On request** (`InvoiceBuilder.forPlot`, «Рахунок на сьогодні»): `month` defaults to the current month in Kyiv; a
later month is 400 `VALIDATION_FAILED` on `month`. Then:

1. The month's build lock: the transaction-scoped advisory lock `documents.invoices:{tenant}:{YYYY-MM}` (a
   parameterised native query), taken in an outer transaction and held until the build has committed, so one build of
   a cooperative's month runs at a time (the month build of slice 3 takes the same lock).
2. A new `REPEATABLE READ` transaction (`TransactionTemplate`, `REQUIRES_NEW`, through the tenant-aware transaction
   manager), begun only once the lock is held: its one snapshot sees whatever an earlier build committed, and every
   figure comes from it.
3. The plot (`PlotCatalog.find`; unknown or another cooperative's: 404 `PLOT_NOT_FOUND`); the requisites (409
   `REQUISITES_MISSING` with `missing` = `IBAN` and/or `EDRPOU`, `RequisitesCheck`); common land, an archived plot or
   no current owner (`PlotOwners.currentOwnerShares`): 409 `PLOT_NOT_INVOICEABLE`.
4. `asOf`, read once: the later of the application clock and the database clock (`clock_timestamp()`), so every row
   committed before the snapshot counts whichever clock wrote it.
5. `LedgerReadModel.invoiceLedgers([plot], month, asOf)` → `InvoiceCalculator` (pure: the rows in the charge type
   order, a type without figures and lines left out; `endDebt`; `due = max(0.00, endDebt − received after)`); the
   owners' full names (`MemberDirectory`), the chairmen's and treasurers' names (`members.RoleHolders`), the printed
   type names (the bundle's default names of seeded types, or the stored name).
6. The next version (`latest + 1`, 1 for the first), number `{YYYY-MM}/{plot number}`; the QR code only when
   something is due (`NbuQrPayload.of`: the printed official name, the IBAN, «До сплати», the ЄДРПОУ, the plot, the
   month and the number). Its content hash is compared with the latest version's (as the month build does): the same
   → answer 200 with the latest version's `InvoiceRow`, nothing written, no audit. Otherwise the snapshot
   (`InvoiceSnapshots`) is stored as an `InvoiceRecord` with the audit entry `INVOICE_REISSUED`
   (`InvoiceAuditPublisher`, through the outbox: about the plot, label its number, `number`, `month`, `version`,
   `due`). Answer 201 with the `InvoiceRow`.

**The snapshot** (`InvoiceSnapshots`, schema 1, `invoice.snapshot` jsonb): `schema`, `number`, `version`, `month`,
`monthStatus` (closed by `asOf` or not), `asOf`, `issuedAt`, `issuedOn`, `cooperative` (official name or the
cooperative's name, ЄДРПОУ, address, bank, IBAN), `plot` (id, number, street), `owners` (`memberId`, `numerator`,
`denominator`), `persons` (base64 of the owners' names, the chairmen and the treasurers as one JSON object sealed with
`PersonalDataCipher` at `FieldRef("invoice", "snapshot.persons", invoiceId)`), `types` (id, code, printed name, start,
charged, paid, end), `lines` (the month's charges with the copied calculation and `corrects`), `payments` (optional,
written only when the month has any, so older snapshots and invoices without payments keep their canonical form: the
payment rows booked in the month, `paymentId`, `reverses` for a reversal, `receivedOn`, `amount` with its sign,
`receiptNumber` and `bankReference` when set; no payer, no note), `receivedAfter`,
`totals` (`endDebt`, `prepayment`, `receivedAfter`, `due`), `qr` (`link`, `purpose`) or absent. Amounts are strings with
two decimals. The canonical form (without `version`, `asOf`, `issuedAt`, `issuedOn`, `qr`; persons in plaintext; keys
sorted; compact JSON) gives `content_hash` = the keyed blind index `privacy.IndexKind.DOCUMENT_CONTENT`, so a change is
detected without a hash of plaintext names (the month build uses it). The QR link is stored, so a later change of the
code never changes an old version.

**Reading** (`Invoices`): the list (newest month, newest issue, number, newest version; with a `month` filter the
number in the natural order of the plot numbers, `PlotCatalog.inNaturalOrder`, so the month's versions are sorted and
paged in memory; `latest` from a window over the
plot and month; `latestOnly` keeps only the latest version of each plot and month, for the web's main lists), one version opened (`GET /invoices/{id}`, the contract's `InvoiceSnapshot`), its PDF and its QR PNG
(8 px per module; 404 when nothing is due), the readiness (`missing`). Who sees a version: `CHARGES_VIEW`; or a
current owner of its plot and only the latest version of its plot and month; anything else about an id is the same
404 `NOT_FOUND` as an unknown one; without the permission and without a plot of one's own 403 before the lookup; the
platform operator 403. File names `invoice_{YYYY-MM}_plot-{number}_v{N}.pdf` / `_qr.png` (the web builds its own).
Downloads are not audited.

**A member's invoices** (`Invoices.mine`, `GET /me/invoices`, the member account's «Рахунки»): the plots the caller
owns now (`identity.OwnedPlotsSource`), for each the latest version of each month's invoice (one native query: the
version equal to the plot and month's highest), newest month first, then the plots in the natural order of their
numbers; every row `latest: true`. No permission: a caller with no plot, or linked to no member, gets an empty list (a
sold plot is no longer listed); the platform operator 403. Each row's PDF is `GET /invoices/{id}/pdf`, which lets a
member have only that latest version. A member's cash receipts come from the plot account (`GET /plots/{id}/account`:
`payments[].id`, `source`, `receiptNumber`) and `GET /payments/{id}/receipt.pdf`.

**The page** (`InvoiceLayout`, A4, one page per invoice): the requisites with, beside them, «Рахунок № {number}» and
«(версія N)» from version 2, «Розрахунковий місяць», «Дата видачі» and «Станом на {asOf}» for a month that was open;
«Платник: ділянка № N, street» and the owners with their shares; the table per charge type (start, charged, paid, end,
«Разом»); «Нарахування за {місяць}» with each charge's details (a tariff part «50,00 ₴ × 6,00 сотки / 12» or «75,00 ₴
за ділянку», «, N дн.» for part of a month; a regular charge or an adjustment of a metered source shows one range: a
reading interval «показники 10.08.2026 → 03.10.2026: 3 435,900 → 3 705,500 = 269,600 кВт·год × 4,32 ₴», fixed-percentage
losses «показники A → B: втрати 5,00 % від … кВт·год = … кВт·год × …», a losses run «втрати в мережі, A → B: …», a
common consumer share «спільний споживач «name», A → B: …», where a whole month is its name; «Коригування за» /
«Сторнування за» the month corrected); «Оплати за {місяць}», the payment rows booked in the month (day, «квитанція № N»
or «банківський платіж, референс …» or nothing, the amount; a reversal «сторнування платежу (…)» with its negative
amount); «Отримано після {місяця}» with the payments; then, kept on one page with the footer
(`PdfDocument.keepTogether`), the totals with «Аванс» when there is one and «До сплати» and beside them
(`PdfDocument.beside`) the QR block (only when something is due: the code at 0.5 mm per module drawn with
`PdfDocument.drawing` in the space of a version 17 code, `NbuQrImage.maxSidePoints`; «Відскануйте QR-код у застосунку
свого банку»; «Оплатити в застосунку банку», a link annotation that opens the code's link, which is not printed
(`PdfDocument.link`); the recipient and the purpose); the footer «Сплачуйте за реквізитами або QR-кодом; у призначенні
вказуйте номер ділянки», the chairmen and treasurers, «Сформовано в системі Наділ {дата час}». An invoice too long for
one page (many reading intervals) goes on on the next page, whose header (`PdfDocument.continuation`) is «Рахунок №
{number}, ділянка № N, street (продовження)»; a table repeats its header there. The file's `/ID` is derived from the
version's whole snapshot and its dates are `issuedAt`, so a version always gives the same bytes.

## The debt certificate (feature 016 §2.7)

**Issue** (`Certificates.issue`, one read committed transaction): `asOf` defaults to today in Kyiv; a later day is 400
`VALIDATION_FAILED` on `asOf` (`INVALID`). The plot (`PlotCatalog.find`; unknown or another cooperative's: 404
`PLOT_NOT_FOUND`); common land 409 `PLOT_COMMON_LAND`, an archived plot 409 `PLOT_ARCHIVED` (the existing codes:
the plot balances report, whose figures the certificate repeats, lists neither); the requisites as for an invoice (409
`REQUISITES_MISSING` with `missing`, `RequisitesCheck`). A plot without a current owner gets a certificate without
owners. Then: the current owners with their shares (`PlotOwners.currentOwnerShares`) and full names
(`MemberDirectory`); the figures `LedgerReadModel.debtsAsOf(asOf, every type, [plot])`, the same as the plot balances
report for that day (the debt per charge type the plot has a counted charge of, in the cooperative's type order with
the printed name; the total; the prepayment); the area (`PlotFacts.areaM2`); the issuer: the issuing user's full name
and role («Голова» when they hold the chairman's role, else «Казначей», else their custom roles' names, else «Член
кооперативу»; a renamed base role prints its stored name). The names are sealed at
`FieldRef("certificate", "snapshot.persons", certificateId)` (ADR 0016) before the number is taken.

**Number** `Д-{YYYY}-{N}` (`DocumentCounter`): `YYYY` is the year of the issue instant in Europe/Kyiv; the counter row
`document_counter (tenant, 'CERTIFICATE', year)` is created with `INSERT … ON CONFLICT DO NOTHING` and stepped with
`UPDATE … SET last_value = last_value + 1 … RETURNING`, the last read of the transaction: only the insert of the
`certificate` row and its audit event follow. The row lock orders concurrent issues (the waiting `UPDATE` reads the
committed value), a rollback undoes the step, so numbers are consecutive, never duplicated and never skipped; a new
year starts at 1.

**Snapshot** (`CertificateSnapshots`, schema 1, `certificate.snapshot` jsonb, written once): `schema`, `number`,
`year`, `seq`, `asOf`, `issuedAt` (microseconds), `issuedOn`, `cooperative` (name, ЄДРПОУ, address, IBAN, bank),
`plot` (id, number, street, `areaM2`), `owners` (`memberId`, `numerator`, `denominator`), `types` (id, code, printed
name, `debt`), `totals` (`debt`, `prepayment`), `issuer` (`memberId`, `role`), `persons` (base64 of the owners' and
the issuer's names, sealed). `certificate.total_debt` is the total, never below 0.00 (the column's check).

**Annul** (`Certificates.annul`): the reason is stripped and required (400 `REQUIRED`; over 500 characters
`TOO_LONG`, also by the contract); one `UPDATE certificate SET annulled_at, annulled_by, annul_reason WHERE id = ? AND
annulled_at IS NULL` (the `certificate_guard()` trigger allows only this change, once); 0 rows is 409
`CERTIFICATE_ALREADY_ANNULLED` for an existing certificate, else 404 `NOT_FOUND`. A concurrent second annulment waits
for the row lock and then matches nothing.

**Register** (`Certificates.list`): newest issue first (then year and number), `status` `ISSUED` / `ANNULLED` /
both, pages of at most 100; each row has the number, the plot (as printed), `asOf`, `totalDebt`, who issued it and
who annulled it (current names through `audit.ActorNames`), the times and the reason.

**The page** (`CertificateLayout`, A4): the requisites; «Довідка № Д-2026-1» «про стан розрахунків за ділянкою»;
«Дата видачі», «Станом на»; when annulled, «АНУЛЬОВАНО {день}. Причина: …» in a frame; «Ділянка № N, street»;
«Площа: 600,00 м² (6,00 сотки)»; «Власники: name (1/2), …» or «Власники: не зазначено»; the table of the debt per
charge type with «Разом»; «Аванс (передоплата)»; «Заборгованість відсутня» when the total is 0,00, otherwise
«Заборгованість становить {сума}»; «{role}: {name}» with a signature line, «М. П.» (the place for the stamp); the
footer. The file's `/ID` is derived from the whole certificate with its annulment and its dates are `issuedAt` (or
`annulledAt` once annulled), so a certificate in the same state always gives the same bytes. File name
`certificate_D-2026-1.pdf`.

**Audit** (`CertificateAuditPublisher`, through the outbox): entity type `CERTIFICATE`, entity id the certificate,
label its number; `CERTIFICATE_ISSUED` with `plot` (number), `number`, `asOf`, `totalDebt`; `CERTIFICATE_ANNULLED`
with `number` and `reason`. No names. Downloads are not audited.

**The month build** (`InvoiceBuilder.forMonth`, `POST /billing-months/{month}/invoices`, synchronous: ADR 0017
"Synchronous month build and its limit"):

1. The month (`ListedMonths`): after the current month in Kyiv 409 `PERIOD_IN_FUTURE`; before the first billing month
   (`LedgerReadModel.firstBillingMonth`) or while there is none 404 `NOT_FOUND`, as the billing month endpoints answer.
2. Not a preview: the month's build lock without waiting, in the outer transaction (409 `INVOICE_BUILD_RUNNING` while
   another build of the month or an on-request invoice of it holds it).
3. The new `REPEATABLE READ` transaction: the requisites (409 `REQUISITES_MISSING`); the invoiceable plots =
   `PlotCatalog.findChargeable` (active, not common land) with a current owner (`PlotOwners.currentOwnerShares`), in
   street and plot order (`PlotCatalog.inStreetOrder`); more than 2,000 → 422 `INVOICE_BUILD_TOO_LARGE` with `limit`
   (`ApiProblemException.invoiceBuildTooLarge`); `asOf` read once.
4. Chunks of 50 plots: `invoiceLedgers` of the chunk, the latest version of each plot's invoice of the month with its
   content hash (`InvoiceRepository.latestOfPlots`), the owners' names; per plot the invoice (`document`, as on
   request) and then: no invoice of the month and nothing due → **skipped** (unless `includeNothingDue`, then version 1
   without the QR code); no invoice → version 1 (**built**); the same content hash as the latest version →
   **unchanged** (nothing written); otherwise the next version (**newVersions**). `flush()` and `clear()` after each
   chunk; a package-private test hook (`afterChunk`, a no-op in production) runs between chunks.
5. One `INVOICES_ISSUED` audit entry (entity type `BILLING_MONTH`, label `YYYY-MM`, the same entity id for every build of
   the month; fields `month`, `built`, `newVersions`, `unchanged`, `skipped`), through the outbox in the build's
   transaction. Answer 200 `InvoiceBuild{preview: false, invoiceable, built, newVersions, unchanged, skipped}`.

The **preview** (`preview=true`) runs steps 1, 3 and 4 with the same decision per plot (`InvoiceBuilder.decide`, the
content hash included), takes no lock and writes nothing: `InvoiceBuild{preview: true, invoiceable, built,
newVersions, unchanged, skipped, toBuild, nothingDue, existing}`, the first five what a build started next answers
(unless something is booked in between); `toBuild = built + newVersions`; `nothingDue` = the plots without an invoice
of the month with nothing due; `existing = newVersions + unchanged`, the plots with an invoice of the month. The build takes the month's lock without waiting
(`pg_try_advisory_xact_lock`): while another build of the month, or an on-request invoice of it, holds it, the answer is
409 `INVOICE_BUILD_RUNNING` and nothing is built (the user waits and refreshes the page). The on-request invoice keeps
the waiting lock (it is short), so it waits for a running month build.

**The print set** (`PrintBundle`, `GET /billing-months/{month}/invoices/print.pdf`): a month that is not listed (after
the current one, before the first) is 404 `NOT_FOUND`. The latest version of each plot's invoice of the month
(`InvoiceRepository.latestOfMonth`); `scope=PAPER` (the default) keeps the plots with at least one current owner whose
delivery channel is `PAPER` (`PlotOwners.currentOwnerIds`, `members.MemberDirectory.deliveryChannels`), `ALL` every
plot; street and plot order; one A4 PDF, each invoice drawn by `InvoiceLayout.draw` from the top of a new page
(`PrintBundle.append`; an invoice fits its page, one that cannot goes on with its continuation header), the
snapshots opened 50 at a time. Without invoices one page «За {місяць} немає рахунків для друку» with the reason of the
scope (`print.*` in the bundle). Its `/ID` comes from the month, the scope and the printed version ids, its time is the
latest `issuedAt` among them, so the same set gives the same bytes. File `invoices_{YYYY-MM}_{paper|all}.pdf`.

## Demo documents

`DemoDocuments` (an `ApplicationRunner`, registered only when `spring.flyway.locations` contains `db/demo` and the
placeholder `demoledger` is `on`: the `dev` profile of `make up`; never in production, never in the integration tests,
which turn the placeholder off). The demo ledger block of `R__demo_data.sql` wipes `invoice`, `certificate` and
`document_counter` of the demo cooperative on every re-run (the snapshots need the Java cipher, so the script cannot
write them); on start-up the runner, under `TenantContext.runAs` for the demo cooperative:

- finds the closed month: the first billing month (the demo ledger books nothing before it) while it is `CLOSED`;
- builds its invoices with `InvoiceBuilder.forMonth` (no preview, with `includeNothingDue`, so every invoiceable plot
  gets one and a plot with nothing due shows 0,00 without a QR code; the demo member's plots 7 and 15 have nothing due
  in that month, and `member@demo.local` needs an invoice in the account) as the system (no member, subject
  `system:documents`), unless the month already has an invoice;
- issues one certificate of plot 9 with `Certificates.issue` as of the last day of the closed month, in the name of the
  demo chairman (the first active holder of the chairman's base role, so it prints «Голова» and a name), unless plot 9
  already has a certificate.

Idempotent: the next start finds both and writes nothing. A refusal (requisites missing, a running build) or any
failure is logged with its problem code or class only and never stops the application; the log has counts only.

## Public API: `DocumentsFacade` (for feature 017)

`DocumentsFacade` (implemented by `internal.DocumentsFacadeService`) with `PrintScope` (`PAPER`, `ALL`): ids, links and
bytes only.

| Method | What |
|---|---|
| `latestInvoice(plotId, month)` | the id of the latest version of the plot's invoice for the month, or empty |
| `invoicePdf(invoiceId)` | the PDF of a version (the same bytes as `GET /invoices/{id}/pdf`), or empty |
| `invoiceForToday(plotId)` | the current month's invoice as of now (as `POST /plots/{id}/invoices`, the same lock and problems): a new version with the `INVOICE_REISSUED` audit only when its content changed, otherwise the latest version's id; issued by the system: no `issued_by`, audit subject `system:documents` |
| `receiptPdf(paymentId)` | the cash receipt drawn now, without the payer note; empty for an unknown payment, a reversal row or a bank payment |
| `printPdf(month, scope)` | the print set (as the endpoint, without the month check: an unknown month is the one page "no invoices") |
| `qrLink(invoiceId)` | the stored NBU link of a version, or empty (unknown id, nothing due) |

The cooperative is the one the caller bound (the request's token, or `TenantContext.runAs` in a job or a listener);
without one a method throws `IllegalStateException`. Each method runs in a transaction of the tenant-aware transaction
manager, `REQUIRED` (it joins the caller's, or opens its own, so a job needs none). The facade checks no permission: it
answers as the system and the caller keeps the endpoints' rules (a member receives only the latest version of an
invoice of a plot they own now, and only the receipt of a group whose every plot they own now). Only
`invoiceForToday` writes (and audits), and only when the invoice changed.

## Amounts in words

`AmountInWords.of(Money)`: the exact split into hryvnias and kopecks (nothing rounded); triads with the feminine
hryvnia and thousand («одна гривня», «дві тисячі»), the masculine million and billion; the forms one (…1), few (…2-4)
and many (…0, …5-9, and 11-14 always); zero is «нуль гривень»; kopecks are always two digits with the agreeing word
(«05 копійок», «21 копійка»); range 0.00 to 999 999 999 999.99, a negative or larger amount is refused. The words are
in the document bundle.

## Formats and texts

- `DocumentFormat`: `1 234,56 ₴` (no-break spaces U+00A0 between the thousands and before the sign, decimal comma, two
  decimals, written from the exact value); days `dd.MM.yyyy` in Europe/Kyiv; months «жовтень 2026» (nominative) and
  «жовтня» (genitive); a service period of one whole month as the month, otherwise its days.
- `DocumentTexts`: the UTF-8 bundle `resources/documents/messages_uk.properties`, the only one: documents are always
  Ukrainian (the ADR 0012 exception for generated files). The default names of the seeded charge types are repeated
  there (`chargeTypes.defaultNames.*`, the same as the web catalog); a renamed or custom type prints its stored name.

## The PDF layer (`internal/pdf`)

- `PdfDocument`: A4 (15 mm margins) or A5 (12 mm), drawn top to bottom with a cursor: `text` (a `TextBlock` that wraps
  at spaces, a word wider than a line is broken between characters), `table` (a `Table`: columns with weights and an
  alignment, amounts right-aligned, a semibold header and total rows; a row never splits, a new page repeats the
  header), `rule`, `mark` (framed semibold text), `signature` (text, a line to sign on and a caption), `space`,
  `drawing(height, Drawing)` (a box across the page the caller draws in on the page's content stream, for the QR code).
- Byte stability: `create(size, identity, time)` sets `/ID` to the first 16 bytes of the SHA-256 of `identity` (the
  content) and the creation and modification dates to `time`; nothing else depends on the clock or chance.
- `PdfFonts`: IBM Plex Sans Regular and SemiBold, read once, embedded per document as subsets (`PDType0Font`,
  Identity-H with a ToUnicode map, so the text can be searched and copied). `printable` turns line breaks into spaces,
  drops control characters and replaces a character without a glyph by `?`, so user content never breaks a document.

## The NBU payment QR code (`internal/nbu`)

The QR code of an invoice: format 003 of NBU Board Resolution No. 97 (Annex 4) with the image rules of its Annex 1.
The official text, every element with our value and its source, the size limits, the shortening order and the open
points (the lock mask, field 17, the sign) are in `docs/domain/nbu-qr.md`, the source of truth.

- `NbuQrPayload.of(NbuQrPayload.Invoice)`: the recipient's name, IBAN, amount due (or none), ЄДРПОУ, plot number,
  billing month and invoice number → the 17 elements joined with LF, element 3 `2` (Windows-1251) or `1` (UTF-8 only
  when a character has no Windows-1251 form), `XCT`, `SUPP/SUPP`, `FEFF`, the reference in ISO 646, and the link
  `https://qr.bank.gov.ua/` + Base64URL without padding (at most 475 characters, 507 bytes). The result also returns
  the name, the purpose and the reference it used (the invoice prints and stores them, Annex 1 item 16). The purpose
  texts and month names come from the document bundle (`qr.purpose`, `qr.purposeShort`, `month.nominative.*`).
- `NbuQrImage.of(link)`: the ZXing matrix (`com.google.zxing:core` 3.5.4) at error correction level M, QR version 10
  to 17 (10 forced for a shorter link, above 17 refused), a quiet zone of 4 modules, the white background circle
  (17 to 25 modules by version) and the hryvnia sign inscribed in a circle 4 modules smaller. `drawPdf(content, x, y,
  moduleMm)` draws it as vector shapes (the lower left corner at `x`, `y` in points; 0.5 mm modules on an invoice),
  `png(pxPerModule)` as a PNG (8 px per module for `qr.png`), `sidePoints(moduleMm)` gives its size.
- `HryvniaSign`: the source of the sign's outline. `HryvniaSign.standard()` is `GlyphHryvniaSign`, the outline of the
  glyph U+20B4 of the embedded IBM Plex Sans SemiBold, because the resolution has the sign only as raster images.

**Replacing the sign.** When the owner supplies the official drawing as `docs/domain/nbu-hryvnia-sign.svg`: copy it to
`backend/src/main/resources/documents/nbu-hryvnia-sign.svg`, add a class implementing `HryvniaSign` whose `outline()`
reads the file's path into a `java.awt.geom.Path2D` (SVG coordinates already point down), return it from
`HryvniaSign.standard()` and update `docs/domain/nbu-qr.md` §12. Nothing else changes: `NbuQrImage` inscribes any
outline in the sign circle, and `NbuQrImageTests` must still decode the drawn code. Stored invoices keep their link,
not an image, so they are drawn with the new sign from then on and their data does not change.

## Fonts

`resources/documents/fonts/IBMPlexSans-Regular.ttf` and `IBMPlexSans-SemiBold.ttf`, version 3.005, from the official
IBM Plex release `@ibm/plex-sans@1.1.0`
(`https://github.com/IBM/plex/releases/download/%40ibm/plex-sans%401.1.0/ibm-plex-sans.zip`, folder
`ibm-plex-sans/fonts/complete/ttf/`), unchanged; the licence (SIL Open Font License 1.1) is `OFL.txt` next to them,
copied from the release's `LICENSE.txt`. Both faces cover every Ukrainian letter, «», ’, №, the no-break space and the
hryvnia sign U+20B4. SHA-256: Regular `975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5`, SemiBold
`a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1`.

## Tests

- `AmountInWordsTests`: the functional spec examples and more than 50 further amounts, the forms, the range ends and
  the refused amounts.
- `DocumentFormatTests`: amounts, days in Kyiv (summer and winter), month names, service periods.
- `PdfDocumentTests`: byte-identical double render, `/ID` and dates, A4 and A5 sizes, a glyph for every bundle
  character (and ₴ « » ’ № and every Ukrainian letter), replaced user content, wrapping, tables over several pages
  with their header, the receipt page rendered twice to the same bytes.
- `NbuQrPayloadTests`: every element against Annex 4 table 2 with a byte dump, the official example 1 re-encoded to
  its table 4 text, the amount forms, the size limits, each shortening step on a long name and the UTF-8 path, the
  transliterated reference.
- `NbuQrImageTests`: the background circle per QR version, the version per link length, decoding with ZXing after the
  circle and the sign are drawn (PNG at 8 px per module, and a PDF page rendered at 300 dpi), the replaceable sign.
- `CashReceiptTests` (Testcontainers): a single cash payment, a group split over plots 7 and 15, a reversed payment,
  a bank payment (409), the payer note only with `MEMBERS_VIEW`; texts read back with `PDFTextStripper`.
- `MonthMovementsTests` (ledger, pure), `InvoiceCalculatorTests` (pure): the worked examples 1 to 5 of the
  requirement §6; `RequisitesCheckTests` (pure): example 6.
- `InvoicePdfTests` (pure): the Ukrainian texts read back with `PDFTextStripper`, «(версія 2)», «Станом на», the line
  details, the QR code decoded with ZXing from the page rendered at 300 dpi back to the stored link, no QR block when
  nothing is due, the same bytes twice, A4.
- `InvoiceSnapshotTests` (Testcontainers): the names sealed (never plaintext in the database) and opened for the
  reader, an owner change keeps the old names on the old version, the same version downloads to the same bytes, the
  content hash the same for the same content and different after a payment.
- `InvoiceOnRequestTests` (Testcontainers): a repeated request without a ledger change returns the same version (200,
  no row, no audit entry), a payment gives exactly one new version (201), version 1 unchanged; `latestOnly` lists;
  example 5 end to end (the closed month's figures unchanged after a later adjustment, the November invoice with
  «Коригування за»; the closed month's end debt equal to `debtsAsOf` of its last day); a payment received after the
  month lowers «До сплати» to 0.00 and removes the QR code; example 6 (409 `REQUISITES_MISSING` with `missing`);
  common land, an archived plot, no owner (409), an unknown plot (404), a future month (400); the `INVOICE_REISSUED`
  entry without names.
- `InvoiceAccessTests` (Testcontainers): every endpoint for the treasurer, the chairman, a custom role with
  `CHARGES_VIEW` only and with `INVOICES_ISSUE` only, a member (own latest only; old version, foreign plot, a sold plot
  404; list, readiness and issue 403), the electrician and a custom role without either (403), another cooperative
  (404, empty list, `PLOT_NOT_FOUND`), the operator (403), no token (401); the month build (preview and build) and the
  print set: allowed with `INVOICES_ISSUE`, 403 with `CHARGES_VIEW` only, for a member, the electrician and the
  operator (nothing written), 401 without a token, another cooperative's preview writes nothing here.
- `InvoiceMonthBuildTests` (Testcontainers): the preview's counts (20 invoiceable, 5 with nothing due, plots without
  an owner, archived or common land not counted) and nothing written; the preview and the following build agree
  (built, newVersions, unchanged, skipped) with one plot invoiced before (`existing` 1), and again after a payment; a build, then a rebuild without a change; a
  payment gives one version 2; `includeNothingDue` (invoices without the QR code, compared on the next build); a
  closed and an open month; a future month (409), a month before the first (404), missing requisites (409); a second
  build started while the first runs is 409 `INVOICE_BUILD_RUNNING` (a preview meanwhile is 200) and nothing is built
  twice, the next build finds everything unchanged; one `INVOICES_ISSUED`
  entry per build with its counts and no names; the month list in the natural order of the plot numbers.
- `InvoiceBuildConsistencyTests` (Testcontainers): a payment and a charge committed (with a `created_at` before the
  build) between the first and the second chunk of a 60-plot build, on plots of both chunks, are in none of its
  invoices; the next build gives exactly those four plots a new version.
- `InvoicePageTests` (unit, the longest demo invoices `DemoInvoices`: plots 15 and 2 of October 2026 from
  `src/test/resources/documents`): one page per invoice in the print set with its footer; an invoice repeated past a
  page goes on on pages headed «… (продовження)» with the totals, the QR block and the footer together on its last
  page; the QR link a link annotation and not in the text; one range per metered charge; the month's payment rows with
  the receipt number, the bank reference and a reversal; a snapshot without `payments` opens.
- `InvoicePaymentsTests` (Testcontainers): two cash payments and a reversal of one in the month are stored in the
  snapshot (the reversal negative, with the reversed payment's receipt number, no payer note) and printed; an invoice
  without payments in its month stores no `payments`.
- `InvoicePrintTests` (Testcontainers): one page per latest invoice in street and plot order, `PAPER` (the default)
  with a plot shared by an e-mail and a paper owner, only the latest version, the same bytes twice, the one page of an
  empty set, a month not listed (404), an unknown scope (400).
- `InvoiceBuildLimitTests` (Testcontainers): 2,000 invoiceable plots, 12 months of two charge types, payments on half,
  shared ownership; the first build, a rebuild and an on-request invoice measured (`INVOICE_BUILD_MEASURE` on the
  output, the numbers in ADR 0017) under a loose ceiling; plot 2,001 refused with 422 `INVOICE_BUILD_TOO_LARGE` and
  `limit`, preview and build.
- `DocumentsFacadeTests` (Testcontainers, under `TenantContext.runAs` without a request): the latest invoice, its PDF
  equal to the endpoint's, the QR link (none when nothing is due), the system's invoice for today (no member, audit
  subject), the receipt without the payer note and none for a bank payment, the print set equal to the endpoint's,
  another cooperative finds nothing, no bound cooperative fails.
- `CertificateTests` (Testcontainers): the debt text with the per-type amounts and the no-debt text, the figures
  equal to the plot balances report for today and for the end of last month, the names sealed, a future day (400, no
  number taken), common land, an archived plot, an unknown plot and missing requisites, a plot without owners, the
  annulment reason (required, blank, too long), annulled once (409) with the register and the PDF marked and the next
  number still next, the register's pages, byte-identical PDFs before and after the annulment, the audit entries.
- `CertificateNumberConcurrencyTests` (Testcontainers): six issues waiting on the counter row get 1 to 6; an issue
  that fails after taking its number leaves no gap; the year of the issue in Kyiv with a fixed clock, a new year
  starting at 1.
- `CertificateAccessTests` (Testcontainers): the chairman issues, reads and annuls; the treasurer reads but cannot
  issue or annul; custom roles with `CERTIFICATES_ISSUE` only and `CHARGES_VIEW` only; a member (own plot too) and the
  electrician 403; another cooperative (404, nothing in its register); the operator 403; no token 401.
- `MemberInvoicesTests` (Testcontainers): `GET /me/invoices` lists only the latest version per month of the member's
  own plots, newest month first and the plots in natural order; a sold plot drops out; the listed PDFs download, an
  older version and another plot's invoice are 404; the certificates are 403; the member's own cash receipt downloads,
  another plot's is 404; a caller without a plot (also the treasurer) and another cooperative's member get an empty
  list; the operator 403; no token 401.
- `DemoDocumentsTests` (Testcontainers, the demo ledger loaded): the runner is not registered in the tests; a run builds
  the closed month's invoices (version 1, no member; also the demo member's nothing-due plots 7 and 15, due 0) and
  plot 9's certificate as of the month's last day by the demo
  chairman; a second run writes nothing and the rows and the counter stay the same; without a closed month nothing is
  built.
- `ReceiptAccessTests` (Testcontainers): treasurer, chairman, a custom role with `PAYMENTS_VIEW`, a member with their
  own and a foreign payment, a split group with a plot no longer theirs, a reversal row, the electrician (403),
  another cooperative (404), the operator (403), no token (401).
