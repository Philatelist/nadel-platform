# 0019. Cash book: member payment movements by a synchronous in-transaction listener, receipt files in the database with JDK-only metadata stripping, HEIC only in the browser, a migration-time backfill read of `payment`

**Status:** accepted (2026-11); introduced in phase 4 by spec 018
(`context/spec/018-cash-book-expenses-reconciliation/`).

## Context

Feature 018 adds the cooperative's own money: a cash account and a bank account, an append-only journal of every move
of money (`account_movement`), expenses with receipt files, handovers to the bank, other income, cash counts and a
monthly bank reconciliation. It lives in a new module, `treasury` (ADR 0001). Four questions needed a decision:

1. **Member payments in the journal.** A payment of feature 015 (and its reversal) is money that comes into the cash
   or the bank. Its journal line must exist exactly when the payment exists: a payment without its line makes the cash
   book wrong, a line without its payment is money that never came. The payment lives in `ledger`; the journal in
   `treasury`. ADR 0007 says cross-module side effects go through domain events and the Spring Modulith outbox, which
   delivers *after* the commit, in another transaction, and may retry; ADR 0011 says a synchronous cross-module SPI may
   veto or read, but a write must be an event. And `ledger` must not depend on `treasury` (reports and documents depend
   on both).
2. **Receipt files.** An expense carries up to five photos or PDFs of its receipt. A phone photo carries Exif with the
   GPS position of the treasurer's home or garden, the camera and the time; storing it as received leaks personal data
   to everyone with `CASHBOOK_VIEW`. The platform has no object storage yet (`context/product/architecture.md`: S3
   product to decide in phase 5).
3. **HEIC.** iPhones take photos as HEIC. The JDK cannot read HEIC, and the libraries that can (libheif, bundled by
   `heic2any` and `heic-to` on the web) are LGPL-3.0.
4. **Existing payments.** A cooperative that already has payments when the feature is installed must see them in its
   journal from the first day, exactly once each (FS 2.15).

## Decision

### Payment movements: one synchronous listener in the payment's transaction (the exception to ADR 0007)

- `ledger` publishes `PaymentMoneyMoved(tenantId, lines)` inside the transaction that writes payment rows, after they
  are flushed: `PaymentService.book` once per group (a line per payment, dated `received_on`, in the payment's billing
  month) and `PaymentCorrections.reverse` for a reversal row (dated today in Europe/Kyiv, in `openForBooking(today)`).
  The event carries ids, codes, amounts and dates only; no personal data and no text (the reversal reason is read back
  through `LedgerReadModel.paymentRefs`).
- `treasury.internal.PaymentMovementListener` is a **plain synchronous `@EventListener`** that calls a
  `Propagation.MANDATORY` writer (`MovementWriter.writePaymentLines`). Spring Modulith persists publications only for
  transactional listeners, so the event never reaches `event_publication`; the movement commits with the payment or
  not at all; a failure of the listener rolls the payment back. The listener checks that the event's cooperative is the
  one bound to the transaction. The write is idempotent (`ON CONFLICT (tenant_id, payment_row_id) DO NOTHING`).
- **This is the one exception** to "cross-module side effects through the outbox": the movement is not a side effect
  of the payment but the same money fact, seen from the cash book. Everything else about a payment that other modules
  do (plot status, notifications, audit) stays on the outbox.
- `ledger` does not depend on `treasury`: it publishes its own event type; `treasury` depends on `ledger`. It is not an
  SPI (ADR 0011: writing SPIs must be events).
- **Guards** (`treasury.PaymentMovementsTests`): the listener is a plain `@EventListener`, never
  `@ApplicationModuleListener` or `@TransactionalEventListener`; the movement is written in the publishing transaction
  and no `event_publication` row exists for the event; a failing listener rolls the payment back; an event of another
  cooperative is refused.
- **Lock order.** The payment path takes … billing month `FOR SHARE` → `receipt_counter` → `money_account` `FOR UPDATE`
  (cash before bank) → `bank_reconciliation` (a bank line dated in a reconciled month reopens it). The treasury's own
  paths take the billing month `FOR SHARE` (through `ledger.BookingMonths.bookFor`) → the cash account → the bank
  account → `bank_reconciliation`. Both orders agree, so they never deadlock (ledger README, treasury README).
- Cost: every bank payment locks the bank account row for the rest of its transaction, and every cash payment the cash
  row; payment entry is a human action, so the contention is negligible.

### Receipt files in the database

- Files are stored in `expense_attachment.content` (`bytea`, `SET STORAGE EXTERNAL`: already compressed, kept out of
  line without a second compression), at most 5 files of 5 MiB (5 242 880 bytes) each per expense (CHECK constraints
  and the application), with their type, name, size and SHA-256. The table is append-only like the journal; a reversed
  expense keeps its files, and «Сторнувати і внести заново» copies them inside the database.
- Lists and the expense card read only the metadata (a separate JPA entity without the content); the content is read
  only for a download.
- **Size.** A cooperative that photographs every receipt adds roughly 1–3 MB per expense (25 MB at most). A few hundred
  expenses a year stay well inside one server's database (ADR 0010); the backups grow with them, and the owner manual
  says so. The multipart request limit is 27 MB (five files of 5 MB and the JSON part); `max-file-size` stays 10 MB.
- Moving the files to object storage later is a data migration of one table and a different reader; the API does not
  change. That move belongs to the S3 decision of phase 5, not to this ADR.

### Metadata stripping with the JDK only

- The server accepts JPEG, PNG and PDF only, by their first bytes (`FileSniffer`), never by the name or the declared
  type.
- `JpegMetadataStripper` walks the JPEG segments without re-encoding: it keeps APP0, APP14, the ICC profile (APP2
  `ICC_PROFILE`), the tables, frames and every scan; it drops APP1 (Exif with GPS and camera data, XMP), other APP2
  (MPF, FlashPix), APP3–APP13, APP15 and comments; it writes back a minimal Exif with only the Orientation when there was
  one, and cuts after the first EOI. `PngMetadataStripper` keeps the critical and the colour chunks and drops every
  other ancillary chunk (`eXIf`, `tEXt`, `iTXt`, `zTXt`, `tIME`, …). A malformed file is refused. A PDF is stored as
  received.
- **No image library** is added to the backend: no re-encoding means no quality loss and no decoder attack surface,
  and a segment/chunk walker is small enough to test exhaustively (fixtures built in memory in the tests).

### HEIC only in the browser, no LGPL library

- The web client turns a HEIC photo into JPEG before the upload with the browser's own decoder (`createImageBitmap` →
  canvas → JPEG): Safari on iOS and macOS decode HEIC, and iOS hands a JPEG to an input that does not accept HEIC. A
  browser that cannot decode it (Chrome or Firefox on a desktop with a `.heic` file) gets the file type error.
- No LGPL-3.0 library (`heic-to`, `heic2any` with libheif) is added; the server never sees HEIC. **Owner decision
  pending:** accepting an LGPL library on the web if HEIC files from desktop browsers matter.

### The backfill: the one migration-time read of `payment` by the treasury

- `V32__treasury.sql` writes one `MEMBER_PAYMENT` movement for every payment row that exists when the feature is
  installed (between `-- BACKFILL BEGIN` and `-- BACKFILL END`): a payment on its `received_on` in its billing month on
  the account of its source; a reversal on the Kyiv day it was made in `greatest(its month, that day's month)` (the
  month `openForBooking` gave then), reversing the payment's movement. `ON CONFLICT DO NOTHING` keeps a re-run harmless.
- It runs as `nadel_owner` and is the one sanctioned read of another module's table by `treasury`: a module never reads
  another module's tables at run time (ADR 0001), and a migration owns the whole schema. ADR 0010 deploys with downtime,
  so no instance of the previous release writes a payment between the backfill and the start of the new one.
- The demo data writes the movements of the payments it inserts directly with the same `INSERT … SELECT`, for the demo
  cooperative only (`R__demo_data.sql`), after removing the demo cash book in foreign key order.

### Later migrations

- `V33__cash_count_booking_reason.sql`: the chairman's reason for booking a count difference. The booked line cannot
  carry it, because `account_movement.reason` is reserved for reversals (a CHECK), so the count keeps it next to
  `booked_movement_id`, written once in the same update.
- `V34__bank_difference.sql`: the reason of a booked bank difference, one append-only row per booked line
  (`bank_difference`, RLS, `tenant_isolation`): a month can have several booked differences, and the line itself cannot
  hold the reason for the same reason as above.

## Consequences

- The cash and bank journals are complete and consistent with the payments by construction, without a reconciliation
  job between the ledger and the treasury.
- A bug in the treasury's writer blocks payment entry (the payment rolls back). That is intended: a payment the cash
  book does not know is worse than a refused payment. The writer is narrow and covered by the guard tests.
- Receipt files grow the database and its backups; the owner manual describes the size and the 5 × 5 MiB limit.
- HEIC from desktop Chrome or Firefox is refused until the owner accepts an LGPL decoder.
- A future bank statement import (out of scope for 018) fills the statement figures and the reconciliation status this
  feature keeps.
