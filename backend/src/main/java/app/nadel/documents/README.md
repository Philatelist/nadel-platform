# documents

Printable documents built from what the other modules have booked (feature 016): the **cash receipt** of a payment
and the **invoice** of a plot for a billing month with the NBU payment QR code (on request; the month build follows in
slice 3); the debt certificate follows in a later slice of the spec. Every document is a PDF in Ukrainian (an official paper, whatever language the user's screens are in), drawn with
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
| `GET /invoices?month&plotId&page&size` | `listInvoices` | `CHARGES_VIEW` |
| `GET /invoices/readiness` | `getInvoiceReadiness` | `CHARGES_VIEW` |
| `GET /invoices/{id}` · `/pdf` · `/qr.png` | `getInvoice`, `getInvoicePdf`, `getInvoiceQrPng` | `CHARGES_VIEW`, or a current owner of the plot and only the latest version (else 404) |

All are implemented by `DocumentsController` (the whole generated `DocumentsApi`).

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
6. Always a new version (`latestVersion + 1`), number `{YYYY-MM}/{plot number}`; the QR code only when something is
   due (`NbuQrPayload.of`: the printed official name, the IBAN, «До сплати», the ЄДРПОУ, the plot, the month and the
   number); the snapshot (`InvoiceSnapshots`) stored as an `InvoiceRecord`; the audit entry `INVOICE_REISSUED`
   (`InvoiceAuditPublisher`, through the outbox: about the plot, label its number, `number`, `month`, `version`,
   `due`). Answer 201 with the `InvoiceRow`.

**The snapshot** (`InvoiceSnapshots`, schema 1, `invoice.snapshot` jsonb): `schema`, `number`, `version`, `month`,
`monthStatus` (closed by `asOf` or not), `asOf`, `issuedAt`, `issuedOn`, `cooperative` (official name or the
cooperative's name, ЄДРПОУ, address, bank, IBAN), `plot` (id, number, street), `owners` (`memberId`, `numerator`,
`denominator`), `persons` (base64 of the owners' names, the chairmen and the treasurers as one JSON object sealed with
`PersonalDataCipher` at `FieldRef("invoice", "snapshot.persons", invoiceId)`), `types` (id, code, printed name, start,
charged, paid, end), `lines` (the month's charges with the copied calculation and `corrects`), `receivedAfter`,
`totals` (`endDebt`, `prepayment`, `receivedAfter`, `due`), `qr` (`link`, `purpose`) or absent. Amounts are strings with
two decimals. The canonical form (without `version`, `asOf`, `issuedAt`, `issuedOn`, `qr`; persons in plaintext; keys
sorted; compact JSON) gives `content_hash` = the keyed blind index `privacy.IndexKind.DOCUMENT_CONTENT`, so a change is
detected without a hash of plaintext names (the month build uses it). The QR link is stored, so a later change of the
code never changes an old version.

**Reading** (`Invoices`): the list (newest month, newest issue, number, newest version; `latest` from a window over the
plot and month), one version opened (`GET /invoices/{id}`, the contract's `InvoiceSnapshot`), its PDF and its QR PNG
(8 px per module; 404 when nothing is due), the readiness (`missing`). Who sees a version: `CHARGES_VIEW`; or a
current owner of its plot and only the latest version of its plot and month; anything else about an id is the same
404 `NOT_FOUND` as an unknown one; without the permission and without a plot of one's own 403 before the lookup; the
platform operator 403. File names `invoice_{YYYY-MM}_plot-{number}_v{N}.pdf` / `_qr.png` (the web builds its own).
Downloads are not audited.

**The page** (`InvoiceLayout`, A4): the requisites; «Рахунок № {number}» and «(версія N)» from version 2; «Дата
видачі», «Розрахунковий місяць» and «Станом на {asOf}» for a month that was open; «Платник: ділянка № N, street» and
the owners with their shares; the table per charge type (start, charged, paid, end, «Разом»); «Нарахування за
{місяць}» with each charge's details (a tariff part «50,00 ₴ × 6,00 сотки / 12» or «75,00 ₴ за ділянку», «, N дн.» for
part of a month; a reading interval «показники A – B (day – day)» and «338,300 кВт·год × 4,32 ₴»; losses «втрати 5 % від
… кВт·год» or «втрати в мережі»; a common consumer's name; «Коригування за» / «Сторнування за» the month corrected);
«Отримано після {місяця}» with the payments; the totals with «Аванс» when there is one and «До сплати»; the QR block
(only when something is due: the code at 0.5 mm per module drawn with `PdfDocument.drawing`, «Відскануйте QR-код у
застосунку свого банку», the link, the recipient and the purpose); the footer «Сплачуйте за реквізитами або QR-кодом;
у призначенні вказуйте номер ділянки», the chairmen and treasurers, «Сформовано в системі Наділ {дата час}». The file's
`/ID` is derived from the version's whole snapshot and its dates are `issuedAt`, so a version always gives the same
bytes.

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
- `InvoiceOnRequestTests` (Testcontainers): a new version per request and after a payment, version 1 unchanged;
  example 5 end to end (the closed month's figures unchanged after a later adjustment, the November invoice with
  «Коригування за»; the closed month's end debt equal to `debtsAsOf` of its last day); a payment received after the
  month lowers «До сплати» to 0.00 and removes the QR code; example 6 (409 `REQUISITES_MISSING` with `missing`);
  common land, an archived plot, no owner (409), an unknown plot (404), a future month (400); the `INVOICE_REISSUED`
  entry without names.
- `InvoiceAccessTests` (Testcontainers): every endpoint for the treasurer, the chairman, a custom role with
  `CHARGES_VIEW` only and with `INVOICES_ISSUE` only, a member (own latest only; old version, foreign plot, a sold plot
  404; list, readiness and issue 403), the electrician and a custom role without either (403), another cooperative
  (404, empty list, `PLOT_NOT_FOUND`), the operator (403), no token (401).
- `ReceiptAccessTests` (Testcontainers): treasurer, chairman, a custom role with `PAYMENTS_VIEW`, a member with their
  own and a foreign payment, a split group with a plot no longer theirs, a reversal row, the electrician (403),
  another cooperative (404), the operator (403), no token (401).
