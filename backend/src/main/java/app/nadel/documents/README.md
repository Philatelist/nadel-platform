# documents

Printable documents built from what the other modules have booked (feature 016): so far the **cash receipt** of a
payment; the invoice of a plot with the NBU payment QR code and the debt certificate follow in the later slices of the
spec. Every document is a PDF in Ukrainian (an official paper, whatever language the user's screens are in), drawn with
Apache PDFBox and the embedded IBM Plex Sans (ADR 0017). Requirements: `docs/features/016-invoices-and-documents.md`;
specs: `context/spec/016-invoices-and-documents/`.

The module adds no money rule and owns no table yet. It reads only through the public APIs of the modules that own the
data (ledger, plots, members, audit, tariffs, settings, tenancy, identity) and never their tables (ADR 0001). Nothing
depends on it.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /payments/{id}/receipt.pdf` | `getPaymentReceiptPdf` | `PAYMENTS_VIEW`, or a member who owns every plot of the payment's group now |

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
  header), `rule`, `mark` (framed semibold text), `signature` (text, a line to sign on and a caption), `space`.
- Byte stability: `create(size, identity, time)` sets `/ID` to the first 16 bytes of the SHA-256 of `identity` (the
  content) and the creation and modification dates to `time`; nothing else depends on the clock or chance.
- `PdfFonts`: IBM Plex Sans Regular and SemiBold, read once, embedded per document as subsets (`PDType0Font`,
  Identity-H with a ToUnicode map, so the text can be searched and copied). `printable` turns line breaks into spaces,
  drops control characters and replaces a character without a glyph by `?`, so user content never breaks a document.

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
- `CashReceiptTests` (Testcontainers): a single cash payment, a group split over plots 7 and 15, a reversed payment,
  a bank payment (409), the payer note only with `MEMBERS_VIEW`; texts read back with `PDFTextStripper`.
- `ReceiptAccessTests` (Testcontainers): treasurer, chairman, a custom role with `PAYMENTS_VIEW`, a member with their
  own and a foreign payment, a split group with a plot no longer theirs, a reversal row, the electrician (403),
  another cooperative (404), the operator (403), no token (401).
