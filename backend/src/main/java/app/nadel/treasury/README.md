# treasury

The cooperative's own money (feature 018, «Каса і банк»): the **cash account** («Каса», the cash the treasurer
holds) and the **bank account** («Банківський рахунок»), their **opening balances**, the **journal** of every move of
money and the **balance** of an account on any day. Members' debts never change here: the module only shows where the
money they paid went. Draft: slices 1 to 5 of the feature (accounts, opening balances, member payments in the journal;
expenses with receipt files, categories, the no-negative-cash rule; handovers to the bank and other income; cash
counts with their act and the booked difference; the monthly bank reconciliation); the reports and the reconciliation
act come with later slices.
The money rules are `docs/domain/money-rules.md` §14 (written with the feature); the decisions are ADR 0019.

Tables (`V32__treasury.sql`, all under RLS `tenant_isolation`, an index leading with `tenant_id`, `UNIQUE (tenant_id,
id)` and composite foreign keys on `(tenant_id, x_id)`):

| Table | What it holds | Writes |
|---|---|---|
| `money_account` | One `CASH` and one `BANK` account per cooperative (`seed_treasury`, called by `seed_base_roles`; deterministic id `md5(tenant || ':ACCOUNT:CASH' / ':ACCOUNT:BANK')`), the opening balance `opening_amount` (≥ 0), `opening_date`, `opening_set_by`, `opening_set_at` (all null or all set) | `nadel_app` may update only the `opening_*` columns (column grant), and the trigger `money_account_guard` lets them go from null to a value once |
| `account_movement` | The journal: `account_id`, `kind` (`MovementKind`), signed `amount` (in positive, out negative, never 0), `moved_on`, `booking_month` (the first day of the billing month, mapped to `YearMonth`), `source_type`/`source_id` (what caused it), `payment_row_id` (a `MEMBER_PAYMENT` line: the ledger payment row, a payment or its reversal; unique), `reverses_movement_id` + `reason` (a reversal: same kind and account, negated amount, reversed once), `created_by`, `created_at` | append-only (`ledger_append_only` triggers, `UPDATE`/`DELETE`/`TRUNCATE` revoked); only `MovementWriter` inserts |
| `expense_category` | Expense and income categories (`kind` `EXPENSE`/`INCOME`); a seeded one has `system_code` and `name` null until renamed; unique `lower(name)` per kind (`expense_category_name_uq`, archived ones included) | `nadel_app` may update only `name`, `sort_order`, `archived`; never deleted; written by `Categories` |
| `expense` | An expense: account, category, `amount` (> 0), `expense_date`, `counterparty_enc`/`description_enc` (sealed), document number and date, `movement_id`; a reversal is a second row with `reverses_expense_id` and `reason`, dated the day of the reversal | append-only; written by `Expenses` (`ExpenseQueries`) |
| `expense_attachment` | Up to five receipt files per expense (`position` 1..5): stored type, name, `size_bytes`, `sha256` and `content` (`STORAGE EXTERNAL`) | append-only; two JPA entities: `ExpenseAttachmentRecord` (no content: lists, counts, the card) and `ExpenseAttachmentContent` (insert of an upload, the download); a reused file is copied inside the database |
| `handover` | Cash taken to the bank: `amount` (> 0), `handed_on`, `document_number` (the deposit slip, required), `out_movement_id` (the cash line) and `in_movement_id` (the bank line); a reversal is a second row with `reverses_handover_id`, `reason`, the original's document number and its own two reversing movements, dated the day of the reversal | append-only; written by `Handovers` (`HandoverQueries`) |
| `other_income` | Income not from members: account, income category, `amount` (> 0), `received_on`, `description_enc` (sealed), `movement_id`; a reversal is a second row with `reverses_other_income_id`, `reason` and the category, without a description | append-only; written by `OtherIncomes` (`OtherIncomeQueries`) |
| `cash_count` | A count of the cash: `counted_at`, `counted_on` (its Kyiv day), `counted_amount` (≥ 0), the `book_balance` computed when it was saved, `difference` (= counted − book), `explanation` (required for a difference; user content in the clear), `booked_movement_id` and `booking_reason` (V33) once the difference is booked | `nadel_app` may update only `booked_movement_id` and `booking_reason` (column grants), and `cash_count_guard` lets that happen once, in one update; written by `CashCounts` (`CashCountQueries`) |
| `bank_reconciliation` | One row per calendar month (`month` = its first day), created when the statement is first entered: `statement_opening/incoming/outgoing/closing` (they add up: CHECK), `status` `NOT_RECONCILED`/`RECONCILED`/`NEEDS_RECHECK`, `reconciled_by/at`, `reopened_movement_id/at` (the last line that reopened it) | `nadel_app` may update only the statement, status, reconciled and reopened columns (column grants); `bank_reconciliation_guard` allows `NOT_RECONCILED → RECONCILED → NEEDS_RECHECK → RECONCILED` and no statement change while `RECONCILED`; written by `BankReconciliations` and, for the reopening, `MovementWriter` (`BankReconciliationQueries`) |
| `bank_difference` (V34) | The reason of a booked bank difference line: `reconciliation_id`, `movement_id` (unique), `reason` (1–500 characters) | append-only; written by `BankReconciliations` |

V32 also backfilled one movement per payment row that existed when the feature was installed (the one sanctioned
migration-time read of `payment`, between the `BACKFILL BEGIN`/`END` markers): a payment on its `received_on` in its
billing month, a reversal on the Kyiv day it was made in `greatest(its month, that day's month)`.

## Public API

| Type | Use |
|---|---|
| `MoneyAccountKind` (`CASH`, `BANK`) | The two accounts |
| `MovementKind` (`MEMBER_PAYMENT`, `EXPENSE`, `OTHER_INCOME`, `HANDOVER_OUT`, `HANDOVER_IN`, `COUNT_DIFFERENCE`, `BANK_DIFFERENCE`) | What moved the money of a journal line; a reversal has the kind of the line it reverses |
| `BankMonthStatus` (`NOT_RECONCILED`, `RECONCILED`, `NEEDS_RECHECK`) | The reconciliation status of a bank month (for the bank movements report of a later slice) |
| `CashCountActs.act(countId)` → `CashCountAct` (with `Status`, `Booking`, `Reversal`) | The figures of a count for its act (`documents`): moment, day, book, counted, difference, explanation, booking and its reversal; `MANDATORY`, no permission check of its own (the caller checks `CASHBOOK_VIEW`); empty for an unknown count |

Everything else is in `internal`.

## Endpoints (tag `treasury`)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /money-accounts?asOf` | `listMoneyAccounts`: both accounts, cash first: id, kind, the bank's IBAN from the requisites (`settings.CooperativeSettings`), `opening {amount, date}`, `balance` on `asOf` (absent without an opening balance), `negative`, `asOf` (default today in Europe/Kyiv; a later day is 400 `asOf` `INVALID`); the cash its `lastCount` and `unresolvedDifference`; the bank its `months` (see "Bank reconciliation") | `CASHBOOK_VIEW` |
| `POST /money-accounts/{id}/opening-balance` | `setOpeningBalance` `{amount, date}`: once (409 `OPENING_BALANCE_ALREADY_SET`); `amount` 0.00 or more with at most 12 integer digits (400 `amount` `INVALID`), `date` not after today (400 `date` `DATE_IN_FUTURE`); 404 `NOT_FOUND`. Audit `OPENING_BALANCE_SET`. Answers the account with today's balance | `CASHBOOK_APPROVE` |
| `GET /money-accounts/{id}/movements?from&to&page&size` | `listAccountMovements`: the lines dated `from..to` (default the current month up to today; `from` after `to` is 400 `from` `INVALID`), oldest first (date, time entered, id), with `openingBalance` (end of `from − 1`) and `closingBalance` (end of `to`) over the whole period, `negative` (closing below 0.00). Each line: date, kind, amount, `bookingMonth`, `beforeOpening`, `source {type, id}`, `reversed`, `reversesMovementId`/`reason` (a reversal), `createdByName` (the author's current name through `audit.ActorNames`, as the payment card shows it to the board), `createdAt`; a member payment line adds `plotId` and `receiptNumber` (cash) or `documentNumber` (the bank reference) through `LedgerReadModel.paymentRefs`; a handover line adds `documentNumber` (the deposit slip), an expense or other income line `categoryId` (a reversal line its entry's). 404 `NOT_FOUND` | `CASHBOOK_VIEW` |

| `POST /expenses` (multipart: part `expense` JSON, parts `files`) | `createExpense`: see "Expenses" below; 201 with the card | `CASHBOOK_ENTER` |
| `GET /expenses?from&to&account&categoryId&page&size` | `listExpenses`: originals only, newest first (date, time entered, id), each with account, category, counterparty, amount, `attachmentCount` and `reversed`; `from` after `to` is 400 `from` `INVALID` | `CASHBOOK_VIEW` |
| `GET /expenses/{id}` | `getExpense`: the card with counterparty, description, document, the files (number, name, type, size) and the reversal (date, reason, who, when); 404 for a reversal row | `CASHBOOK_VIEW` |
| `GET /expenses/{id}/attachments/{n}` | `downloadExpenseAttachment`: the stored file with its stored type, `Content-Disposition: attachment` (ASCII `filename` and UTF-8 `filename*`), `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`; not audited | `CASHBOOK_VIEW` |
| `POST /expenses/{id}/reverse` `{reason}` | `reverseExpense`: 200 with the card, now reversed | `CASHBOOK_APPROVE` |
| `POST /handovers` `{date, amount, documentNumber}` | `createHandover`: see "Handovers" below; 201 with the handover | `CASHBOOK_ENTER` |
| `POST /handovers/{id}/reverse` `{reason}` | `reverseHandover`: 200 with the handover, now reversed; the id is the `source.id` of a handover line | `CASHBOOK_APPROVE` |
| `POST /other-income` `{account, date, amount, categoryId, description?}` | `createOtherIncome`: see "Other income" below; 201 with the income | `CASHBOOK_ENTER` |
| `POST /other-income/{id}/reverse` `{reason}` | `reverseOtherIncome`: 200 with the income, now reversed | `CASHBOOK_APPROVE` |
| `POST /cash-counts?preview` `{countedAt, counted, explanation?}` | `enterCashCount`: see "Cash counts" below; `preview=true` 200 (nothing saved), otherwise 201 with the card | `CASHBOOK_ENTER` |
| `GET /cash-counts?page&size` | `listCashCounts`: newest first (moment of the count, time saved, id) | `CASHBOOK_VIEW` |
| `GET /cash-counts/{id}` | `getCashCount`: the card with the lines since the previous count and the booking | `CASHBOOK_VIEW` |
| `POST /cash-counts/{id}/book-difference` `{reason}` | `bookCashCountDifference`: 200 with the card, now `BOOKED` | `CASHBOOK_APPROVE` |
| `POST /money-accounts/{id}/movements/{movementId}/reverse` `{reason}` | `reverseAccountMovement`: reverses a booked count or bank difference line; 200 with the reversal line | `CASHBOOK_APPROVE` |
| `GET /bank-reconciliations/{month}` | `getBankReconciliation`: see "Bank reconciliation" below | `CASHBOOK_VIEW` |
| `PUT /bank-reconciliations/{month}` `{opening, incoming, outgoing, closing}` | `enterBankStatement`: 200 with the reconciliation | `CASHBOOK_ENTER` |
| `POST /bank-reconciliations/{month}/book-difference` `{amount, reason}` | `bookBankDifference`: 200 with the reconciliation | `CASHBOOK_APPROVE` |
| `POST /bank-reconciliations/{month}/confirm` | `confirmBankReconciliation`: «Звірено»; 200 with the reconciliation | `CASHBOOK_ENTER` |
| `GET /expense-categories?kind&includeArchived` | `listExpenseCategories` (kind default `EXPENSE`), by `sortOrder` | `CASHBOOK_VIEW` |
| `POST /expense-categories`, `PUT /expense-categories/{id}`, `PUT /expense-categories/order` | add, rename/archive (`archived: false` offers it again), reorder the non-archived of a kind (a list that is not exactly them is 400 `ids` `INVALID`) | `CASHBOOK_APPROVE` |

A member and the platform operator hold none of the permissions (403); another cooperative's account, expense or
category is 404 (RLS).

## The no-negative-cash rule (`CashGuard`, pure)

An entry that takes money out of the cash dated *t* (a cash expense, a handover, the reversal of a cash other income;
later: shortages) is refused with 409 `CASH_INSUFFICIENT` when the lowest balance from *t* on (the running balance at the end of
*t* and of every later movement day) plus the entry is below 0.00; the problem carries that lowest balance (`balance`,
before the entry) and the earliest day it is reached (`date`). So a back-dated entry is refused when a later day would
go negative although its own day stays positive (FS 2.17 #5). The caller locks the cash account `FOR UPDATE` (after
the billing month) before it reads the day totals (READ COMMITTED), so two entries at the same moment are checked one
after the other (`ExpenseConcurrencyTests`). A bank expense, other income, an expense reversal and a handover reversal
(money back into the cash) are never checked; member payment reversals are exempt (slice 1).

## Expenses (`Expenses`)

Recording, in this order (the first refusal answers, nothing is stored): the fields (400 `VALIDATION_FAILED`:
`amount` `AMOUNT_NOT_POSITIVE` or more than 12 integer digits `INVALID`, `date` `DATE_IN_FUTURE`, `categoryId`
`INVALID` (unknown, another cooperative's, or an income category: the database does not enforce the kind) or
`ARCHIVED`, `reuseAttachments[i]` `INVALID`, the contract's `REQUIRED`/`TOO_LONG`); the files (`AttachmentUploads`:
more than five, reused ones included, 400 `TOO_MANY` on `files`; per file `files[i]` over 5 MiB 413 `FILE_TOO_LARGE`,
not a JPEG/PNG/PDF by its first bytes (`FileSniffer`) or an unreadable JPEG/PNG 400 `FILE_TYPE_NOT_ALLOWED`), before
any lock; then `ledger.BookingMonths.bookFor(date)` (a closed month books in the earliest open month, the date is
kept), the account `FOR UPDATE`, 409 `OPENING_BALANCE_MISSING`, 400 `BEFORE_OPENING_BALANCE` (`date`), and for the
cash `CashGuard`. Then `MovementWriter.writeEntry` (`EXPENSE`, the negated amount, source `EXPENSE`), the expense row,
the files (reused ones first, copied by `INSERT … SELECT`, then the uploads) and `EXPENSE_RECORDED`, in one transaction.

Reversing («Сторнувати»): a blank reason is 400 `reason` `REQUIRED`; `bookFor(today)`, the account `FOR UPDATE`, 409
`ENTRY_ALREADY_REVERSED` (the unique keys `expense_reversed_once_uq` and `account_movement_reversed_once_uq` are the
backstop); `MovementWriter.writeReversal` (same account and kind, the negated amount, dated today, with the reason), the
reversal row and `EXPENSE_REVERSED`. «Сторнувати і внести заново» is the reversal and then `createExpense` with
`reuseAttachments`.

The `expense` part may come without a content type (the generated web client appends it as a plain form field):
`ExpensePartConverter` reads it as JSON; a part sent as `application/json` is read by the standard converter.
`spring.servlet.multipart.max-request-size` is 27MB (five files of 5 MB and the JSON) and `server.tomcat.max-swallow-size`
30MB; `max-file-size` stays 10MB (the imports).

### Attachments (JDK only, no image library)

- `JpegMetadataStripper`: segment by segment without re-encoding; keeps APP0, APP14, APP2 `ICC_PROFILE`, the tables,
  frames and every scan (progressive files included); drops APP1 (Exif with GPS and camera IFDs, XMP), other APP2
  (MPF, FlashPix), APP3–APP13, APP15 and COM; re-adds a minimal big-endian APP1 Exif holding only the Orientation (1–8)
  when the original had one, after SOI and the leading APP0; cuts after the first EOI (MPF's extra images, trailing
  data). No SOI, a segment past the end, no frame, scan or EOI: refused.
- `PngMetadataStripper`: keeps IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, iCCP, sBIT, bKGD, pHYs, acTL, fcTL,
  fdAT byte for byte; drops every other ancillary chunk (eXIf, tEXt, iTXt, zTXt, tIME, …); cuts after IEND. A wrong
  CRC, a chunk past the end, no IHDR first, no IDAT/IEND or an unknown critical chunk: refused.
- A PDF is stored as received. The stored name is the client's base name without control and invisible characters, at
  most 255 characters (`receipt.jpg`/`.png`/`.pdf` when nothing is left); `size_bytes` and `sha256` are those of the
  stored bytes. No name and nothing read from a file is logged or answered.

### Personal data

The counterparty and the description are sealed (`TreasuryCipher`, `privacy.PersonalDataCipher`) at
`FieldRef("expense", "counterparty_enc" | "description_enc", expenseId)`; screens with `CASHBOOK_VIEW` show them. In the
audit they are `counterparty`/`description` sealed at `FieldChange.newValueRef(eventId, field)` (`sealed = true`), so
neither `audit_entry` nor `event_publication` holds them in the clear and the log opens them only with `MEMBERS_VIEW`.

## Handovers (`Handovers`)

Recording, in this order (the first refusal answers, nothing is stored): the fields (400 `VALIDATION_FAILED`: `amount`
`AMOUNT_NOT_POSITIVE`/`INVALID`, `date` `DATE_IN_FUTURE`, `documentNumber` `REQUIRED` when blank after trimming, the
contract's `TOO_LONG`); `bookFor(date)`, the cash and then the bank account `FOR UPDATE`, for each 409
`OPENING_BALANCE_MISSING` and 400 `BEFORE_OPENING_BALANCE`, then `CashGuard` on the cash. Then two
`MovementWriter.writeEntry` lines of the same date and month (`HANDOVER_OUT` the negated amount on the cash,
`HANDOVER_IN` the amount on the bank, both source `HANDOVER` with the handover id), the handover row and
`HANDOVER_RECORDED`. Reversing: a blank reason is 400 `reason` `REQUIRED`; `bookFor(today)`, the cash and the bank
locked, 409 `ENTRY_ALREADY_REVERSED` (backstops `handover_reversed_once_uq`, `account_movement_reversed_once_uq`); the
reversal of each line dated today (the cash gets the money back, the bank may go below zero: never refused for a
balance), the reversal row and `HANDOVER_REVERSED`. The deposit slip number is a bank document number, not personal
data: it is stored, audited (also as the entry label) and shown in the clear.

## Other income (`OtherIncomes`)

Recording: the fields (400 `VALIDATION_FAILED`: `amount`, `date` as for an expense, `categoryId` `INVALID` (unknown,
another cooperative's, or an expense category: the database does not enforce the kind) or `ARCHIVED`, the contract's
`TOO_LONG` for `description`); `bookFor(date)`, the account `FOR UPDATE`, 409 `OPENING_BALANCE_MISSING`, 400
`BEFORE_OPENING_BALANCE`; money comes in, so no balance rule. Then the `OTHER_INCOME` line (the amount, source
`OTHER_INCOME`), the income row with the description sealed at `FieldRef("other_income", "description_enc",
incomeId)` and `OTHER_INCOME_RECORDED` (the description sealed like an expense's). Reversing: as an expense, but the
reversal takes the money out again, so a cash income's reversal goes through `CashGuard` dated today (409
`CASH_INSUFFICIENT`); a bank income's reversal may take the bank below zero.

## Cash counts (`CashCounts`)

A count (`countedAt`, its Kyiv day `countedOn`, the counted cash): the **book balance** is the cash balance at the end
of `countedOn` with every line entered so far (`CashCountFigures`, pure, over `AccountBalances`); the **difference** is
counted − book. Checks, in order: 400 `counted` `INVALID` (below 0.00 or more than 12 integer digits), `countedAt`
`DATE_IN_FUTURE` (more than 5 minutes after the server's now, or on a later Kyiv day), `explanation` `TOO_LONG`
(> 500); the cash account (`FOR UPDATE` on a save; a preview takes no lock); 409 `OPENING_BALANCE_MISSING`, 400
`BEFORE_OPENING_BALANCE` on `countedAt`; on a save a difference without an explanation is 400 `explanation`
`REQUIRED`. The save computes the figures again under the lock (a line entered after the preview counts) and stores the
book balance: a line entered later for an earlier day never changes a saved count. Status `MATCHES` (0.00),
`UNRESOLVED`, `BOOKED`. Audit `CASH_COUNTED`. The explanation is user content in the clear (V32 has a plain
`explanation` column, no sealed one): it describes the cash, like a reversal's reason, and is not treated as personal
data.

**Lines since the previous count.** The previous count is the newest saved count at or before `countedAt` (for a saved
count, the one just before it in the list order). The lines are those this book balance counts and the previous count's
did not: cash lines dated from the opening date to `countedOn`, entered up to now (for a saved count up to its save),
and dated after the previous count's day or entered after it was saved; so previous book + Σ lines = book (a member
payment saved at the very moment of a count is the one race where this can be off, since its line carries the time of
the payment row). Oldest first, at most 500, with `movementsSinceTotal`.

**The cash card** (`GET /money-accounts`): `lastCount` is the newest count by `countedAt`; `unresolvedDifference` is
its difference while it is `UNRESOLVED`. An earlier unresolved count is superseded by a later count (which already sees
the cash as it is).

**Booking a difference** (`CASHBOOK_APPROVE`): reason required (400); `bookFor(countedOn)`, the cash locked, the count
read again; 409 `NO_DIFFERENCE` for a matching count, 409 `DIFFERENCE_ALREADY_BOOKED` for a booked one (the trigger
`cash_count_guard` is the backstop: without the check the second booking fails in the database, 500); a shortage goes
through `CashGuard` on the count's day (409 `CASH_INSUFFICIENT`). Then a `COUNT_DIFFERENCE` line of the difference
(source `CASH_COUNT`, the count) dated the count's day, `booked_movement_id` and `booking_reason` written together, and
`COUNT_DIFFERENCE_BOOKED`. The booking reason lives on the count because `account_movement.reason` is only for
reversals (V33).

**Reversing a booked difference line** (`POST /money-accounts/{id}/movements/{movementId}/reverse`): reason required;
`bookFor(today)`, the account locked (404), the line of that account (404); 409 `MOVEMENT_NOT_REVERSIBLE` unless it is
an original `COUNT_DIFFERENCE` or `BANK_DIFFERENCE` line (expenses, handovers and other income have their own reverse
endpoints, payments are reversed in 015); 409 `ENTRY_ALREADY_REVERSED`; the reversal of a cash surplus goes through
`CashGuard` on today (a bank difference is never guarded). The reversal (source `MOVEMENT`, the reversed line) is dated
today; `COUNT_DIFFERENCE_REVERSED` about the count, or `BANK_DIFFERENCE_REVERSED` about the reconciliation of the
line's month (its source). The count stays `BOOKED` (`booked_movement_id` is written once): its card and act show the
reversal, and the next count sees the cash as it is.

## Bank reconciliation (`BankReconciliations`)

A month is a **calendar month by the date of the lines** (`moved_on`), not the billing month; path `{month}` =
`YYYY-MM`. Every request checks, in order: the month (not after the current month in Europe/Kyiv, else 400 `month`
`INVALID`); its fields; on a write the bank account `FOR UPDATE`; 409 `OPENING_BALANCE_MISSING` without the bank's
opening balance; 400 `BEFORE_OPENING_BALANCE` (`errors[{field: month}]`) for a month that ends before the opening date.

**The books** (`BankComparison`, pure, over `BankReconciliationQueries.flows`: the bank lines per day grouped by sign
and by whether they are reversals): `opening` the balance at the start of the 1st (for the month of the opening date:
the opening amount), `incoming` and `outgoing` the month's receipts and spendings from the opening date on, **both
positive** (as a statement shows them), `closing` = opening + incoming − outgoing. A reversal counts against the
direction of the line it reverses (the reversal of a receipt lowers `incoming`, of a spending `outgoing`), so a
mistaken entry and its reversal in one month cancel out; lines before the opening date count in nothing. The
differences are statement − books per figure; `balanced` when there is a statement and all four are 0.00. `GET` also
lists the month's bank lines (`JournalLines`, at most 1000, with `movementsTotal`), the last reopening (the line's id,
kind, date, amount and when) and who reconciled it.

**The statement** (`StatementRules`, pure): `incoming`, `outgoing` ≥ 0, every figure at most 12 integer digits (400
`INVALID`), and opening + incoming − outgoing = closing (400 `STATEMENT_INCONSISTENT`, field `closing`). The first
statement creates the month's row (`NOT_RECONCILED`); later ones replace the figures while `NOT_RECONCILED` or
`NEEDS_RECHECK`; a `RECONCILED` month is 409 `BANK_MONTH_RECONCILED`. Audit `BANK_STATEMENT_ENTERED`.

**A bank difference** («Банківська різниця», `CASHBOOK_APPROVE`): a signed amount ≠ 0.00 (`amount` `INVALID`) and a
reason (`REQUIRED`); `bookFor(date)` first (lock order), with date = the month's last day or today when earlier; 409
`BANK_STATEMENT_MISSING` before the statement, `BANK_MONTH_RECONCILED` for a reconciled month. A `BANK_DIFFERENCE` line
on the bank (source `BANK_RECONCILIATION`, the month's row), its reason in `bank_difference` (V34:
`account_movement.reason` is only for reversals) and shown as the line's `reason`. The bank is never guarded. Audit
`BANK_DIFFERENCE_BOOKED`.

**«Звірено»** (`confirm`, `CASHBOOK_ENTER`): under the bank lock the books are computed again; 409
`BANK_MONTH_NOT_BALANCED` without a statement or with a difference, `BANK_MONTH_RECONCILED` when already reconciled;
`NOT_RECONCILED` or `NEEDS_RECHECK` → `RECONCILED` with `reconciled_by/at`. Audit `BANK_MONTH_RECONCILED`.

**Reopening** (in `MovementWriter`, the one writer, so it covers every writer: member payments through
`PaymentMovementListener`, expenses, other income, handovers, bank differences and every reversal): right after a
movement is inserted, `BankReconciliationQueries.reopen` sets the `RECONCILED` month of a bank line dated on or after
the opening date to `NEEDS_RECHECK` with `reopened_movement_id/at`, and the system's audit entry `BANK_MONTH_REOPENED`
(subject `system:treasury`, no member) is published with the line. A month already `NEEDS_RECHECK` keeps its first
reopening until it is reconciled again; the reopening stays on the row after that (the status tells whether it is
current). Because every bank writer and `confirm` hold the bank account lock, a line can never slip in between the
books `confirm` reads and its commit.

**The bank card's months** (`GET /money-accounts`, `months`): newest first, at most 12, from the month of the opening
date to the month of `asOf`: the months with a reconciliation, the months with bank lines from the opening date on, and
always the month of `asOf`; a month without a row is `NOT_RECONCILED`. Empty for the cash and without an opening
balance.

**A known limit of the four-figure rule.** A wrong line in a past month that is reversed today lowers today's month,
not the past one, so that past month's `incoming`/`outgoing` stay off (its closing difference can be booked away, the
other two cannot); and a booked bank difference adds to `incoming` or `outgoing` as well as to `closing`. Such a month
balances only if the statement figures match. Whether «Звірено» should need only the closing difference to be 0.00 is
open with the owner.

## Categories (`Categories`)

The list of one kind by `sort_order`; seeded categories come with `systemCode` and without `name` (the client names
them from the catalogs). Adding puts a category at the end of its list. A name is unique per kind ignoring case,
archived ones included (exactly the index on `lower(name)`), so a new name equal to the catalog name of a seeded
category that was never renamed does not collide (it has no stored name). 409 `CATEGORY_NAME_TAKEN` (`errors[{field:
name}]`) from a quick check, the index being the guarantee. Only `name`, `sort_order` and `archived` are written.

## Balances (`AccountBalances`, pure)

The opening balance is the balance at the start of its date. The balance at the end of day *d* is the opening amount
plus every movement with `opening_date ≤ moved_on ≤ d`; the end of the day before the opening date is the opening amount
itself and no balance is known earlier. Movements dated before the opening date are listed with `beforeOpening = true`
and count in no balance. Balances are never stored: `TreasuryQueries.dayTotals` sums the movements per day
(`account_movement_account_day_idx`). Without an opening balance an account has no balance and `negative` is false. The
server computes every amount; the client shows them.

## Member payments in the journal (ADR 0019)

`ledger` publishes `PaymentMoneyMoved` inside the payment's transaction (after flushing the rows): `PaymentService.book`
once per group with a line per payment (dated `received_on`, in the payment's month), `PaymentCorrections.reverse`
for the reversal row (dated today in Europe/Kyiv, in `openForBooking(today)`). `PaymentMovementListener` is a **plain
synchronous `@EventListener`** (never `@ApplicationModuleListener` or `@TransactionalEventListener`, guarded by
`PaymentMovementsTests`): Spring Modulith stores publications only for transactional listeners, so the event never
reaches `event_publication`, the movement commits with the payment or not at all, and a failure of the listener rolls
the payment back. It is the one exception to "cross-module side effects through the outbox": the movement is the same
money fact as the payment. The listener checks that the event's cooperative is the one bound to the transaction, then
`MovementWriter.writePaymentLines` (`MANDATORY`):

1. locks the accounts of the lines `FOR UPDATE`, cash before bank (after the billing month and the receipt counter the
   payment path holds: the lock order of the ledger README);
2. inserts one `MEMBER_PAYMENT` movement per line with `ON CONFLICT (tenant_id, payment_row_id) DO NOTHING`
   (idempotent: a row that already has its movement, from the backfill or a second delivery, is left as it is);
3. for a reversal: the account of the payment's movement, the negated amount, `reverses_movement_id` = that movement and
   the reversal's reason, read from the ledger row (`LedgerReadModel.paymentRefs`; the event itself carries no text).

A cooperative without the account, or a reversal whose payment has no movement, is a broken invariant (V32 seeds the
accounts and backfills every payment) and an `IllegalStateException`, which rolls the payment back. Member payments are
recorded whether or not the account has an opening balance, and a payment reversal is never refused for the balance
(the cash may go negative: the card warns). Logs carry ids and counts only.

## Lock order

Payment paths: … billing month `FOR SHARE` → `receipt_counter` → `money_account` `FOR UPDATE` (cash, then bank) →
`bank_reconciliation` (a bank line in a reconciled month). Treasury paths: billing month `FOR SHARE` (`ledger.BookingMonths.bookFor`) → the
cash account → the bank account → `bank_reconciliation`. See the ledger README.

## Audit

`TreasuryAuditPublisher` publishes `AuditEvent`s in the changing transaction. Entity type `MONEY_ACCOUNT` (entity id the
account, label its kind code). `OPENING_BALANCE_SET`: `kind`, `amount`, `date` (new values only). Entity type
`EXPENSE` (entity id the expense, label the expense date): `EXPENSE_RECORDED` and `EXPENSE_REVERSED` with `amount`,
`account`, `category` (id), `date`, the reversal's `reversedOn` and `reason`, and `counterparty`/`description` sealed.
Entity type `CASHBOOK_CATEGORY` (label `base:<CODE>` for a seeded category never renamed, else its name):
`CASHBOOK_CATEGORY_ADDED` (`kind`, `name`), `_RENAMED` (`name` old/new), `_REORDERED` (`position` old/new, one entry per
moved category), `_ARCHIVED` (`archived` old/new). Every action is a value of the contract's `AuditAction`
(`TreasuryAuditActionsTests`). Entity type `CASH_COUNT` (entity id the count, label the moment of the count as an ISO
instant): `CASH_COUNTED` (`countedAt`, `counted`, `bookBalance`, `difference`, `explanation` when given),
`COUNT_DIFFERENCE_BOOKED` (`amount` signed, `date` the count's day, `reason`), `COUNT_DIFFERENCE_REVERSED` (`amount`,
`date`, `reversedOn`, `reason`). Entity type `BANK_RECONCILIATION` (entity id the month's row, label `YYYY-MM`):
`BANK_STATEMENT_ENTERED` (`opening`, `incoming`, `outgoing`, `closing`; old values when replaced),
`BANK_DIFFERENCE_BOOKED` (`amount` signed, `date`, `reason`), `BANK_DIFFERENCE_REVERSED` (`amount`, `date`,
`reversedOn`, `reason`), `BANK_MONTH_RECONCILED` (`status` old/new and the four figures), `BANK_MONTH_REOPENED` by the
system (`status` old/new, `movement`, `kind`, `date`, `amount`).

## Dependencies

`ledger` (`PaymentMoneyMoved`, `PaymentSource`, `LedgerReadModel#paymentRefs`, `BookingMonths`), `settings`
(`CooperativeSettings`: the IBAN), `audit` (`AuditEvent`, `ActorNames`, `FieldChange`), `privacy`
(`PersonalDataCipher`, `FieldRef`), `identity` (`CurrentUser`, `AccessProfile`), `tenancy` (`TenantContext`),
`problem`, `money`. It never reads another module's tables. `documents` depends on it (`CashCountActs`, the cash count
act); it never depends on `documents`.

## Permissions

`CASHBOOK_VIEW` (chairman, treasurer): accounts, journals, expenses and their files, categories. `CASHBOOK_ENTER`
(chairman, treasurer): expenses, handovers, other income, cash counts, bank statements and «Звірено».
`CASHBOOK_APPROVE` (chairman): opening balances, reversals, categories, booking a count or bank difference and
reversing its line. The bank reconciliation of a month needs `CASHBOOK_VIEW`. The act of a count needs `CASHBOOK_VIEW`. Custom roles can hold any of them.

## Tests

`AccountBalancesTests` (FS 2.17 #1, #10, before-opening, no opening), `PaymentMovementsTests` (one movement per payment
and per split part, reversal today in the open month, a failing listener rolls the payment back, the movement is
written in the publishing transaction and no `event_publication` row exists, the listener is a plain `@EventListener`,
the tenant check), `MoneyAccountsTests` (list, opening once and audited, validation, before-opening payments not counted,
the journal with refs, paging and both balances, a reversal below zero flags the cash negative), `MoneyAccountsAccessTests`
(VIEW and APPROVE allowed and denied, custom roles, member, operator, 401, another cooperative 404),
`TreasuryAuditActionsTests`; the V32 tests `TreasuryTenantIsolationTests`, `TreasuryGuardsTests`,
`TreasuryBackfillTests`. Slice 2: `CashGuardTests` (FS 2.17 #2, #4, #5, the suffix minimum and its earliest day),
`CashBookExamplesTests` (one test per FS 2.17 row implemented so far: #1–#5), `FileSnifferTests`,
`JpegMetadataStripperTests` (GPS/camera/XMP/MPF/IPTC/comment fixture built in memory by `TestImages`, progressive,
ICC/Adobe kept, orientation kept, malformed), `PngMetadataStripperTests`, `AttachmentUploadsTests`, `ExpensesTests`
(photo without GPS on download, limits, validation, opening balance, cash rule, closed month, reverse and re-enter with
reused files, list filters, no plaintext in `expense`, `audit_entry`, `event_publication`, hidden in the log without
`MEMBERS_VIEW`), `ExpenseConcurrencyTests` (two 150.00 on 200.00 with a lock barrier: exactly one saved),
`CategoriesTests`, `ExpensesAccessTests`. Slice 4: `CashCountsTests` (FS 2.17 #6 end to end, «Збігається», explanation
required on a save only, the save recomputes the book balance after a preview, a saved count keeps its book balance
after a back-dated entry and the next count lists it, surplus booked and its line reversed once, shortage refused by the
cash rule, refusals, list order), `CashCountActTests` (the PDF text: balances, difference, result, booking, signers),
`CashCountsAccessTests`, `CashBookExamplesTests` row 6, `TreasuryGuardsTests` (the V33 booking reason). Slice 5:
`StatementRulesTests` (FS 2.17 #9, field rules), `BankComparisonTests` (books of the opening month and later months,
reversals against their original, opening inside a month, differences, `balanced`), `BankReconciliationTests` (FS 2.17
#7 and #8 end to end — #8 through a real bank payment entered later with an earlier date —, a cash line and a line of
another month reopen nothing, bank difference booked, listed with its reason and reversed today, a reversal reopens the
reconciled current month, the status machine, not balanced, month and opening refusals, the bank card's months),
`BankReconciliationsAccessTests`, `CashBookExamplesTests` rows 7–9, `TreasuryTenantIsolationTests` and
`TreasuryGuardsTests` (`bank_difference`, V34).
