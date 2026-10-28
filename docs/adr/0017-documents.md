# 0017. Documents: PDFs drawn with Apache PDFBox 3.0.8 and the embedded IBM Plex Sans, byte-stable, never stored

**Status:** proposed; introduced in phase 4 by spec 016 (`context/spec/016-invoices-and-documents/`). The measurement
of the month build and its limit are added by slice 3 of the spec.

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

An invoice build (on request now, the month build in slice 3) reads every figure from one database snapshot: a new
`REPEATABLE READ` transaction through the tenant-aware transaction manager. One build per cooperative and month runs
at a time: the transaction-scoped advisory lock `documents.invoices:{tenant}:{YYYY-MM}` is taken first, in an outer
transaction that holds it until the build has committed, and the `REPEATABLE READ` transaction begins only once the
lock is held. A single `REPEATABLE READ` transaction could not take the lock itself: PostgreSQL fixes the snapshot at
its first statement (the tenant's `set_config`), so after waiting for an earlier build it would not see that build's
versions. The moment of the build (`asOf`) is the later of the application clock and the database clock, read once
after the snapshot began, so every row committed before it counts whichever clock wrote its `created_at`.

## Consequences

- Two new libraries (PDFBox, three artifacts; ZXing core, one artifact) to watch in OSV-Scanner; a new advisory is bumped or, with a reason,
  accepted in `osv-scanner.toml`.
- The layout layer is ours to maintain: wrapping, tables and page breaks are tested in `PdfDocumentTests`; a richer
  layout need (for example free-form templates) would be a new decision.
- About 400 KB of font files in the backend jar; the TTF files and `OFL.txt` must travel together.
- Rendering costs CPU on every download (a receipt takes milliseconds); the month build and the print set are measured
  in slice 3 and recorded here with the limit.
- The hryvnia sign of the NBU QR code uses the U+20B4 glyph of the embedded font until the owner supplies the official
  drawing (slice 2, `docs/domain/nbu-qr.md`).
