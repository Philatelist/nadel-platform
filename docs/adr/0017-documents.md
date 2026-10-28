# 0017. Documents: PDFs drawn with Apache PDFBox 3.0.8 and the embedded IBM Plex Sans, byte-stable, never stored

**Status:** proposed; introduced in phase 4 by spec 016 (`context/spec/016-invoices-and-documents/`). Slice 3 of the
spec adds the synchronous month build with its limit and measurement.

## Context
Feature 016 prints official documents for a cooperative: the invoice of a plot for a billing month with the NBU payment
QR code, the debt certificate and the cash receipt of a payment (feature document `docs/features/016-invoices-and-documents.md`,
functional spec §2). The documents must be:

- **PDF** files, A4 (invoice, certificate, print set) and A5 (cash receipt), always in **Ukrainian** with every letter
  (ґ, є, і, ї), the quotes «», the apostrophe, the sign № and the hryvnia sign ₴;
- **byte-stable**: the same document opened again gives the same bytes, so an old invoice version looks exactly as it
  did (technical considerations 2.1, 3);
- **made by the server** from data it already has (the client shows no money it computes), on request, without a
  browser or a native tool on the server;
- within the supply-chain rules: exact pins, OSV-Scanner green on any severity, few transitive artifacts.

Questions:

1. Which PDF engine, and what decides it?
2. Which font, and how do we keep the documents legal to distribute and legible in Ukrainian?
3. How are the files made byte-stable?
4. Are the files stored?
5. How is a month's set of invoices built, in the request or as a background job, and up to what size?

## Decision

### PDF engine: Apache PDFBox 3.0.8 with a small own layout layer

`org.apache.pdfbox:pdfbox:3.0.8`, pinned in `backend/pom.xml` (`pdfbox.version`). It brings `pdfbox-io` 3.0.8,
`fontbox` 3.0.8 and `commons-logging` (the version Spring Boot manages, 1.3.6):

```
\- org.apache.pdfbox:pdfbox:jar:3.0.8:compile
   +- org.apache.pdfbox:pdfbox-io:jar:3.0.8:compile
   +- org.apache.pdfbox:fontbox:jar:3.0.8:compile
   \- commons-logging:commons-logging:jar:1.3.6:compile
```

On top of it the module `documents` has its own layer in `documents/internal/pdf` (about 400 lines): `PdfDocument`
draws top to bottom with a cursor (paragraphs that wrap, tables whose rows never split and that repeat their header on a
new page, rules, a framed mark, a signature line), `TextBlock`, `Table` and `PdfFonts`. One layout class per document
(`ReceiptLayout` so far) turns already named content into calls of that layer.

| Criterion | Apache PDFBox 3.0.8 (chosen) | OpenHTMLtoPDF (HTML + CSS to PDF) | OpenPDF (iText 4 fork) |
|---|---|---|---|
| Licence | Apache-2.0 | LGPL-2.1 | LGPL-2.1 / MPL-2.0 (dual) |
| Size | ~3.8 MB in 3 artifacts (`pdfbox` 2.1 MB, `fontbox` 1.6 MB, `pdfbox-io` 52 KB) plus `commons-logging` (74 KB, already managed by Spring Boot) | PDFBox itself (it renders through it) plus its own core and the CSS / HTML stack; SVG needs Batik, a large stack with its own advisory record | ~2 MB core jar plus its font and text modules for full TrueType support |
| Byte stability | Every input is ours: `/ID` set from the content, dates set, the font subset tag derived from the glyphs; verified by a double-render test | Possible through PDFBox, but the layout engine adds its own metadata and resource order to control; harder to prove | `/ID` and dates are filled by the writer from the clock unless overridden; possible, needs care per version |
| Cyrillic and ₴ | `PDType0Font` embeds a subset of any TTF with Identity-H and a ToUnicode map (text can be searched and copied) | Yes, through PDFBox fonts declared in CSS | Yes, with `BaseFont.IDENTITY_H` and an embedded TTF |
| Layout effort | Own small layer (cursor, wrap, tables); exact control of every point of the A4/A5 page | Least for rich layout (HTML templates); a CSS paged-media subset to learn and test | Has paragraphs and tables (`PdfPTable`), but its API is the old iText 4 one |
| Maintenance | Apache project, regular 3.0.x releases | The original project was archived; development continues in a community fork | Active community project (LibrePDF) |
| Advisory surface (OSV gate) | One well-known project; its few CVEs were fixed in 3.0.x patch releases | PDFBox plus the HTML / CSS / SVG stack | One project with its own record |

PDFBox is the smallest stack with a permissive licence that gives exact control of the bytes and of the page. The
documents are a handful of fixed forms, so an HTML engine would buy little and cost a second layout system. No
OpenHTMLtoPDF, no Batik, no iText-family library.

### QR matrix: ZXing core 3.5.4

The NBU payment QR code of an invoice (`docs/domain/nbu-qr.md`) needs a QR encoder with a chosen error correction
level and version. `com.google.zxing:core:3.5.4` (Apache-2.0, one artifact of about 0.6 MB without dependencies) is
pinned in `backend/pom.xml` (`zxing.version`); only its encoder is used, for the module matrix. The background circle,
the hryvnia sign and the drawing (vector rectangles in the PDF, Java2D for the PNG) are ours (`documents/internal/nbu`).
`com.google.zxing:javase:3.5.4` is a test dependency only, to decode the drawn code (it brings `jcommander` 1.85 and
`jai-imageio-core` 1.4.0 to the test classpath).

### Font: IBM Plex Sans, embedded as a subset

The design system already uses IBM Plex Sans (`packages/ui-tokens`), licensed under the SIL Open Font License 1.1, which
allows embedding in documents. `IBMPlexSans-Regular.ttf` and `IBMPlexSans-SemiBold.ttf` (version 3.005 of the
official release `@ibm/plex-sans@1.1.0`, asset
`https://github.com/IBM/plex/releases/download/%40ibm/plex-sans%401.1.0/ibm-plex-sans.zip`, files
`ibm-plex-sans/fonts/complete/ttf/`) are stored in `backend/src/main/resources/documents/fonts/` with the licence as
`OFL.txt`. Both have every Ukrainian letter, «», ’, №, the no-break space and the hryvnia sign U+20B4 (glyph 190 in
both faces). Each document embeds only the glyphs it uses (a receipt is about 18 KB). A test checks that every
character of the document bundle has a glyph; user content (a name, a note) passes through `PdfFonts.printable`, which
replaces a character without a glyph by `?`, so no input can break a document.

### Byte stability

`PdfDocument.create(size, identity, time)`: the file identifier `/ID` is the first 16 bytes of the SHA-256 of the
identity the caller passes (the document's content), written twice; the creation and modification dates are the given
instant in UTC. Nothing else depends on the clock or on chance: PDFBox derives the subset tag of a font from the glyphs
it holds, and the content is drawn in a fixed order. A stored document (an invoice version, a certificate) passes its
issue time, so it re-renders to the same bytes forever; a cash receipt is built live from the ledger and passes the
moment it is printed (its «Розподіл станом на» day), so the same payment printed at the same moment gives the same
bytes. `PdfDocumentTests` renders twice and compares the bytes.

### Texts

Document texts live in one UTF-8 bundle, `backend/src/main/resources/documents/messages_uk.properties` (the ADR 0012
exception for generated files): documents are official papers and always Ukrainian, whatever language the screens
are in. The words of amounts in words (`AmountInWords`) are in the same bundle.

### Not stored

A document is rendered on every download from its data: the receipt from the ledger, an invoice or a certificate (spec
016, later slices) from its stored snapshot. No PDF is written to the database or to object storage (this supersedes
"object storage for invoice PDFs" in `context/product/architecture.md`).

### One consistent read per build

An invoice build (on request, and the month build) reads every figure from one database snapshot: a new
`REPEATABLE READ` transaction through the tenant-aware transaction manager. One build per cooperative and month runs
at a time: the transaction-scoped advisory lock `documents.invoices:{tenant}:{YYYY-MM}` is taken first, in an outer
transaction that holds it until the build has committed, and the `REPEATABLE READ` transaction begins only once the
lock is held. A single `REPEATABLE READ` transaction could not take the lock itself: PostgreSQL fixes the snapshot at
its first statement (the tenant's `set_config`), so after waiting for an earlier build it would not see that build's
versions. The moment of the build (`asOf`) is the later of the application clock and the database clock, read once
after the snapshot began, so every row committed before it counts whichever clock wrote its `created_at`.

### Synchronous month build and its limit

The month build (`POST /billing-months/{month}/invoices`, `InvoiceBuilder.forMonth`) runs **in the request, in one
`REPEATABLE READ` transaction**, like the month run of feature 011 and the Excel import (ADR 0012); the web shows a
spinner (owner decision 28.10.2026). No JobRunr (ADR 0007: it comes with feature 017, which delivers the invoices), no
job queue of our own and no `invoice_run` table: a half-finished background build would need its own state, progress
and retry, and the requirement's `invoice_run` is dropped.

- **One snapshot for the whole build** (owner decision 28.10.2026): the transaction of "One consistent read per build"
  above, with `asOf` read once. A payment or a charge committed by another transaction while the build runs is in all
  of its invoices or in none, even on a plot read after the commit; `InvoiceBuildConsistencyTests` commits a payment
  and a charge between two chunks (through a test hook) and finds them in none of that build's invoices and in the
  next build. `REPEATABLE READ` blocks neither the payment entry nor the month run; the build only reads ledger rows and
  inserts into `invoice`, so it has no write conflict on shared rows.
- **Order and chunks:** the invoiceable plots (active, not common land, with a current owner) in street and plot order,
  50 at a time: `LedgerReadModel.invoiceLedgers` (three queries) per chunk, the snapshot and its keyed content hash per
  plot, compared with the latest version of the month (a new version only when it differs; a plot without an invoice and
  with nothing due is skipped unless asked), then `flush()` and `clear()` of the persistence context, so memory does
  not grow with the cooperative. One `INVOICES_ISSUED` audit entry per build, with the counts.
- **One build per month at a time:** the advisory month lock, taken by the month build without waiting
  (`pg_try_advisory_xact_lock`): a build started while another build of the month (or an on-request invoice of it)
  runs is refused with 409 `INVOICE_BUILD_RUNNING` and builds nothing, so the second user is told a build is already
  running (functional spec §2.5) instead of holding a request thread and two connections for the length of a build.
  The on-request invoice is short and keeps the waiting lock (`pg_advisory_xact_lock`). The preview takes no lock and
  writes nothing.
- **Limit: 2,000 invoiceable plots** per build (the size of the largest supported cooperative, the import's plot
  limit). Above it the build and the preview answer 422 `INVOICE_BUILD_TOO_LARGE` with `limit` and write nothing.
- **Measurement** (`InvoiceBuildLimitTests`, Testcontainers PostgreSQL, the line `INVOICE_BUILD_MEASURE` on the
  test's output): 2,000 invoiceable plots, two charge types charged in each of 12 months (48,000 charges), a bank
  payment of the month on half of the plots, a tenth of the plots shared by two owners; the first build (2,000 new
  versions), a rebuild without a change (2,000 compared, nothing written) and one on-request invoice. The test checks a
  loose ceiling (60 s per build, 10 s on request).
  Measured: <filled after the measurement run>
- **Progress UI:** a spinner only while the measured first build at the limit is at most about 5 s; a longer build
  goes back to the owner (a lower limit, or the build as a JobRunr job in 017) instead of a progress bar here.

The print set of a month (`GET /billing-months/{month}/invoices/print.pdf`, `PrintBundle`) is drawn in the request
too: one A4 PDF with the latest version of each invoice (the paper-delivery plots, or all), one invoice from the top of
a page, from the stored snapshots 50 at a time; its identity is the month, the scope and the printed version ids, so
the same set gives the same bytes.

## Consequences

- Two new libraries (PDFBox, three artifacts; ZXing core, one artifact) to watch in OSV-Scanner; a new advisory is bumped or, with a reason,
  accepted in `osv-scanner.toml`.
- The layout layer is ours to maintain: wrapping, tables and page breaks are tested in `PdfDocumentTests`; a richer
  layout need (for example free-form templates) would be a new decision.
- About 400 KB of font files in the backend jar; the TTF files and `OFL.txt` must travel together.
- Rendering costs CPU on every download (a receipt takes milliseconds); the month build is measured at its limit
  (above) and holds a request thread and two database connections (the lock's and the build's) while it runs.
- A cooperative above 2,000 invoiceable plots cannot build its month's invoices in one request; raising the limit or
  moving the build to a JobRunr job (feature 017) is a new decision.
- The hryvnia sign of the NBU QR code uses the U+20B4 glyph of the embedded font until the owner supplies the official
  drawing (slice 2, `docs/domain/nbu-qr.md`).
