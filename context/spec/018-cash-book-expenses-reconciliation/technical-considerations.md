# Technical Specification: Cash book, expenses and reconciliation

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Implemented — pending manual check
- **Author(s):** Alex Usov (design input: spring-backend, postgres-database, react-frontend agents)

---

## 1. High-Level Technical Approach

A new Spring Modulith module `app.nadel.treasury` owns money accounts, the append-only movement journal, expenses with
attachments, categories, handovers, other income, cash counts and bank reconciliations. It reads the ledger through new
public ledger APIs and never writes ledger tables; members' debts do not change.

Member payment movements are written **in the payment's own transaction** by a synchronous listener in `treasury` on a
new ledger event (`ledger` never depends on `treasury`). The PDFs (cash count act, reconciliation act) are rendered by
`documents` reading `treasury` and `ledger` facades; the four reports live in `reports` and read a `treasury` facade.
Dependency direction: `documents → treasury → ledger`, `reports → treasury`; no cycles (`ModularityTests`).

Database: one migration `V32__treasury.sql` (9 tables with RLS, append-only guards, column-level grants for the narrow
updates, three permissions, `seed_treasury`, backfill of existing payment movements). Contract-first in
`api/openapi.yaml` (tag `treasury`; the two PDFs under tag `documents`). Web: the «Каса і банк» area, expense screens,
count and reconciliation pages, the categories panel, «Акт звірки» on the plot card and the member account. ADR 0019
records the decisions below.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module layout

| Package | Contents |
|---|---|
| `treasury` (public) | `TreasuryReadModel` (`cashBook`, `bankMovements`, `expenses`, `incomeExpense`; `MANDATORY`, `CASHBOOK_VIEW`), `CashCountActs.act(countId)`, records `MoneyAccountKind`, `MovementKind`, `MovementLine`, `CashBookDay`, `ExpenseLine`, `IncomeExpense`, `CashCountAct`, `BankMonthStatus` |
| `treasury.internal` | `TreasuryController` (generated `TreasuryApi`), `MoneyAccounts`, `MovementWriter` (sole writer of `account_movement`), `Expenses`, `Handovers`, `OtherIncomes`, `Categories`, `CashCounts`, `BankReconciliations`, `PaymentMovementListener`, `TreasuryCipher`, `TreasuryAuditPublisher`, `TreasuryQueries`, `DemoTreasury`; attachments `FileSniffer`, `JpegMetadataStripper`, `PngMetadataStripper`; pure calculators `AccountBalances`, `CashGuard`, `StatementRules`, `BankComparison`, `IncomeExpenseCalculator` |
| `documents.internal` | `CashCountActLayout`, `ReconciliationActLayout`, `ReconciliationActs`; `DocumentsFacade.reconciliationActPdf(plotId, from, to)` (so 017 can send it) |
| `reports.internal` | `CashBookReport`, `BankMovementsReport`, `IncomeExpenseReport`, `ExpensesReport` |
| `ledger` (public, new) | `BookingMonths`, `PaymentMoneyMoved`, `PlotStatement` + lines, `PaymentSplit`, `PaymentRef` |

`treasury` depends on `ledger`, `settings` (IBAN), `privacy`, `audit`, `identity`, `tenancy`, `problem`, `money`.

### 2.2 Payment → movement in one transaction (ADR 0019)

- New ledger event `PaymentMoneyMoved(tenantId, List<Line>)`, `Line(ledgerRowId, paymentId, reversal, source, signed
  Money amount, movedOn, bookingMonth, createdBy, createdAt)`; ids, codes and amounts only (no personal data).
- `PaymentService.book` publishes it once per group after flushing the payment rows: `movedOn = received_on`,
  month = the payment's month. `PaymentCorrections.reverse` publishes it with `movedOn = today (Kyiv)` and
  month = `openForBooking(today)` (taken after the original's month; both `FOR SHARE`, months close in order, no
  deadlock). `PaymentRecorded`/`PaymentReversed` stay unchanged.
- `PaymentMovementListener` is a plain `@EventListener` calling a `@Transactional(MANDATORY)` service; it asserts the
  event's tenant equals the bound tenant. Spring Modulith persists publications only for transactional listeners, so
  nothing reaches `event_publication`, and a listener failure rolls the payment back. It must never become an
  `@ApplicationModuleListener` (guard test). This is the one exception to "cross-module side effects via the outbox":
  the movement is the same money fact as the payment.
- The listener locks the account row `FOR UPDATE`, inserts the movement with `ON CONFLICT (tenant_id, payment_row_id)
  DO NOTHING`, and for a bank line sets its month and every later month that is `RECONCILED` to `NEEDS_RECHECK` (their
  opening balances change; owner decision on PR #66), each naming the line, and publishes the system audit
  `BANK_MONTH_REOPENED` for each. No cash check (member payments are exempt).
- **Lock order** (added to the ledger README): payment path … billing month `FOR SHARE` → `receipt_counter` → money
  account `FOR UPDATE` → `bank_reconciliation` rows (in month order). Treasury paths: billing month `FOR SHARE` (via
  `BookingMonths`) → cash account → bank account → `bank_reconciliation` rows (in month order).

### 2.3 New public ledger API

| Member | Purpose |
|---|---|
| `BookingMonths.bookFor(LocalDate) → YearMonth` | `MANDATORY`; wraps `BillingMonths.openForBooking` (share lock, earliest open month for a closed date) |
| `LedgerReadModel.plotStatement(plotId, from, to)` | Reconciliation act: opening = `debtsAsOf(from − 1)`, closing = `debtsAsOf(to)`, lines (charges, adjustments, charge reversals by debt date; payments by `received_on`, payment reversals at the original's day — the 015 debt dating), each with charged or paid, charge type, receipt or bank reference |
| `LedgerReadModel.firstDebtDate(plotId)` | Lower bound of the act period |
| `LedgerReadModel.paymentSplits(paymentIds, recordedBy)` | Net allocations by charge type of allocation rows created up to `max(recordedBy, payment.created_at)`; unallocated = amount − sum |
| `LedgerReadModel.paymentRefs(ledgerRowIds)` | plot, receipt number, bank reference for journal lines (reversal rows included) |

`recordedBy` = end of the period's last day (Kyiv), so the October picture is fixed even if releases later carry
earlier value dates.

### 2.4 Data model (V32)

All tables: `tenant_id uuid not null`, `id uuid` PK, `UNIQUE(tenant_id, id)`, `created_at`, `created_by`, RLS enabled
with the `tenant_isolation` policy (as existing tables), index leading with `tenant_id`, grants `SELECT, INSERT` to
`nadel_app`.

| Table | Key columns | Writes |
|---|---|---|
| `money_account` | `kind` CASH/BANK (`UNIQUE(tenant_id, kind)`), `opening_amount numeric(14,2)`, `opening_date`, `opening_set_by/at` (all null or all set); deterministic id | column grant on `opening_*` + guard trigger: write once (null → value) |
| `account_movement` | `account_id`, `kind`, signed `amount <> 0`, `moved_on`, `booking_month date`, `source_type`, `source_id`, `payment_row_id` (partial unique), `reverses_movement_id` (composite self-FK, partial unique), `reason` | append-only (`ledger_append_only()` triggers + `REVOKE UPDATE, DELETE, TRUNCATE`); guard: a reversal has the negated amount and the same account |
| `handover` | `amount > 0`, `handed_on`, `document_number`, `out_movement_id`, `in_movement_id`, `reverses_handover_id` | append-only |
| `expense_category` | `kind` EXPENSE/INCOME, `system_code` (null for user-added), `name` (null while a seeded category keeps its catalog name), `sort_order`, `archived`; unique index `(tenant_id, kind, lower(name))` | column grant `name, sort_order, archived`; no delete |
| `expense` | `account_id`, `category_id`, `amount > 0`, `expense_date`, `counterparty_enc bytea`, `description_enc bytea`, `document_number`, `document_date`, `movement_id` (unique), `reverses_expense_id` (unique), `reason` | append-only |
| `expense_attachment` | `expense_id`, `position 1..5` (`UNIQUE(expense_id, position)`), `content_type` IN (jpeg, png, pdf), `file_name`, `size_bytes ≤ 5 242 880`, `sha256`, `content bytea` (`SET STORAGE EXTERNAL`) | append-only |
| `other_income` | `account_id`, `category_id`, `amount > 0`, `received_on`, `description_enc`, `movement_id`, `reverses_other_income_id` | append-only |
| `cash_count` | `counted_at timestamptz`, `counted_on`, `counted_amount`, `book_balance`, `difference`, `explanation`, `booked_movement_id` | column grant + guard: `booked_movement_id` null → value once |
| `bank_reconciliation` | `month date` (`UNIQUE(tenant_id, month)`), four statement figures, `status` NOT_RECONCILED/RECONCILED/NEEDS_RECHECK, `reconciled_by/at`, `reopened_movement_id`, `reopened_at` | column grants + guard trigger on status transitions |

- Indexes: `account_movement (tenant_id, account_id, moved_on) INCLUDE (amount)`, `(tenant_id, booking_month)`;
  `expense (tenant_id, expense_date desc, id)`, `(tenant_id, category_id)`; `expense_attachment (tenant_id,
  expense_id)`; `other_income (tenant_id, received_on)`; `cash_count (tenant_id, counted_at desc)`.
- **No DB running-balance trigger:** the cash rule lives in `CashGuard` under the account row lock (a trigger would
  duplicate the member-payment-reversal exemption). Backed by a concurrency test.
- **Seeding:** `seed_treasury(tenant)` (idempotent, deterministic ids) inserts the two accounts (no opening) and the
  seeded categories by `system_code` (expense: `ELECTRICITY_SUPPLIER, WATER, PAYROLL_TAXES, NETWORK_REPAIR, MATERIALS,
  WASTE, BANK_FEES, TAXES_FEES, OFFICE_COMMS, OTHER`; income: `BANK_INTEREST, GRANTS, SALES, OTHER`). Display names
  come from the i18n catalogs (the `DefaultChargeTypeNames` pattern); a rename stores the user's name. `seed_base_roles`
  is redefined with the full V29 body plus CHAIRMAN all three new permissions, TREASURER `CASHBOOK_VIEW` and
  `CASHBOOK_ENTER`, and a call to `seed_treasury`. Existing cooperatives: targeted inserts of the role permissions and
  `seed_treasury(id)` for every tenant.
- **Backfill** (V32, as `nadel_owner`, the one sanctioned migration-time read of `payment`, stated in the SQL comment and
  ADR 0019): every `PAYMENT` row → `MEMBER_PAYMENT` movement on `received_on` in the row's billing month; every
  `PAYMENT_REVERSAL` row → movement on `(created_at at time zone 'Europe/Kyiv')::date` in
  `greatest(row's month, the reversal day's month)` (equals `openForBooking(reversal day)` at reversal time);
  `ON CONFLICT DO NOTHING`. ADR 0010 deploys with downtime, so no old instance writes payments after V32.
- **Permissions:** `identity.Permission` 28 → 31; openapi enum, `PermissionCatalog`, `BaseRoles`, i18n, and the counting
  tests (`LedgerMigrationTests`, `RolesTests`, `SeedBaseRolesTests`, `DemoAccessChecks`).

### 2.5 Balances and the no-negative-cash rule

- **Balance on day d** = opening + Σ movements with `opening_date ≤ moved_on ≤ d`; never stored (daily sums by
  `moved_on`). Movements before the opening date are returned with `beforeOpening = true`.
- **`CashGuard.check(opening, dayTotals, t, delta < 0)`:** running balance per movement day; m = min of the balance at
  t and every later day; refuse with 409 `CASH_INSUFFICIENT {balance, date}` (the earliest day of the minimum) if
  m + delta < 0. Applies to cash expenses, handovers, booked shortages, and reversals of cash income or surpluses; not to
  payment reversals. The cash account row is locked `FOR UPDATE` before any movement is read (READ COMMITTED).
- **Opening balance:** `POST …/opening-balance` once (409 `OPENING_BALANCE_ALREADY_SET`); non-payment entries dated
  before it → 400 `BEFORE_OPENING_BALANCE`; any non-payment entry without it → 409 `OPENING_BALANCE_MISSING`.
- **Closed months:** every entry takes its month from `BookingMonths.bookFor(date)`; dates later than today → 400
  `DATE_IN_FUTURE`.
- **Reversals** (`CASHBOOK_APPROVE`): a new movement of the opposite amount dated today, booked by `bookFor(today)`;
  once (409 `ENTRY_ALREADY_REVERSED`). Difference movements are reversed through
  `POST /money-accounts/{id}/movements/{movementId}/reverse`.
- **Counts:** `POST /cash-counts?preview=true` returns `{bookBalance, difference, movementsSince}` without saving
  (client never subtracts); saving recomputes under the lock and stores `book_balance`. Booking a difference
  (`CASHBOOK_APPROVE`) is dated the count day (409 `DIFFERENCE_ALREADY_BOOKED`, `NO_DIFFERENCE`); a shortage goes through
  `CashGuard`.
- **Bank reconciliation:** calendar months by `moved_on`. `StatementRules`: opening + in − out = closing else 400
  `STATEMENT_INCONSISTENT`. `BankComparison`: books opening (balance at the start of the 1st), Σ positive, Σ negative,
  closing, and four differences. `confirm` needs the closing difference 0.00 (409 `BANK_MONTH_NOT_BALANCED`; owner
  decision on PR #66, the opening, in and out differences are a warning and `balanced` follows the same rule). A bank
  difference (`CASHBOOK_APPROVE`) is dated the month's last day, or today if earlier. Any bank movement dated in a
  `RECONCILED` month (payments included, also via the listener) sets `NEEDS_RECHECK` with the movement and time.

### 2.6 Expenses and attachments

- `POST /expenses` multipart: part `expense` (JSON: account, date, amount, categoryId, counterparty?, description?,
  documentNumber?, documentDate?, reuseAttachments?[{expenseId, n}]) + `files[]` (0–5 incl. reused).
- **Sniff by magic bytes:** JPEG `FF D8 FF`, PNG signature, PDF `%PDF-`; else 400 `FILE_TYPE_NOT_ALLOWED`. 6+ files →
  400 `TOO_MANY`; a file over 5 MB → 413 `FILE_TOO_LARGE` (field `files[i]`).
- **JPEG:** strip segments without re-encoding — keep APP0, APP14, APP2 ICC, DQT/DHT/SOF/SOS/DRI and scans; drop APP1
  (Exif, XMP), APP2 MPF, APP3–13, APP15, COM; re-add a minimal APP1 Exif carrying only Orientation if the original had
  one; cut after the first EOI. **PNG:** keep critical and colour chunks (IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM,
  sRGB, iCCP, sBIT, bKGD, pHYs, acTL/fcTL/fdAT), drop eXIf, tEXt, iTXt, zTXt, tIME and other ancillary chunks. **PDF:**
  stored as received. Malformed → 400 `FILE_TYPE_NOT_ALLOWED`. JDK only, no new backend library.
- Two JPA entities (metadata vs `AttachmentContent`) so lists never load bytes; downloads stream with
  `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `CASHBOOK_VIEW`.
- `spring.servlet.multipart.max-request-size` 11MB → 27MB; `max-file-size` stays 10MB (imports); the 5 MB cap is
  per attachment in the service.
- Counterparty and description sealed at `FieldRef("expense", "counterparty_enc" | "description_enc", id)` and
  `FieldRef("other_income", "description_enc", id)`; in the audit with `FieldChange.newValueRef` (`sealed`), revealed
  only with `MEMBERS_VIEW` (014 rule).
- «Сторнувати і внести заново»: the client reverses, then opens the form prefilled; `reuseAttachments` copies the old
  files server-side.

### 2.7 Reports (013 mechanism)

- `cash-book`, `bank-movements`, `income-expense`, `expenses`: `requiredPermissions() = {CASHBOOK_VIEW}`;
  `personalData() = true` for `cash-book` and `expenses` (counterparty). New raw filters `account` and
  `expenseCategoryIds`; 20 000-row cap applies.
- `ReportsController` list/preview/download: `hasAnyAuthority('PERM_REPORTS_VIEW','PERM_CASHBOOK_VIEW')`; the catalog
  still filters per definition (403 per report). Template filling keeps `REPORTS_VIEW`; the treasury template fields
  also need `CASHBOOK_VIEW`. The web shows «Звіти» with either permission.
- Template: row set `INCOME_EXPENSE("incomeExpense")` (`section`, `name`, `amount`; sections `MEMBER_BY_TYPE`,
  `ADVANCES`, `PAYMENT_REVERSALS`, `OTHER_INCOME`, `EXPENSE`, `DIFFERENCES`) and values
  `totals.cashbook.{cashStart, cashEnd, bankStart, bankEnd, income, expenses, differences, result}`.
- **Identity (example 12, restated):** member income = payments received in P not reversed by the end of P, split by
  `paymentSplits` (unallocated → «Аванси»), minus reversals made in P of payments received before P; other income by
  category; expenses by category net of their reversals in P; differences (count + bank, net). result = income −
  expenses + differences = Δ(cash + bank); handovers cancel. A payment received and reversed within P contributes 0.
  Needs both opening balances (409 `OPENING_BALANCE_MISSING`); only movements on/after each opening date count.

### 2.8 Reconciliation act and cash count act (documents)

- `GET /plots/{id}/reconciliation-act.pdf?from&to` and `GET /cash-counts/{id}/act.pdf`, tag `documents`
  (`DocumentsController`, like the receipt). Act access: `CHARGES_VIEW` any plot; others only plots they own now
  (`OwnedPlotsSource`), else 404; operator 403. Period: `firstDebtDate ≤ from ≤ to ≤ today`, else 400 `from`/`to`
  `INVALID`. `REQUISITES_MISSING` as for invoices. Not stored, not audited (016 downloads rule).
- Act content: requisites, plot, current owners (`PlotOwners` + `MemberDirectory`), opening/closing from
  `plotStatement`, lines by date with «Нараховано»/«Сплачено», turnover totals, the closing sentence by net balance
  (debt − prepayment: «заборгованість … становить», «переплата … становить», «заборгованість відсутня»), signature
  blocks («Від кооперативу»: the first active holder of the treasurer base role from `members.RoleHolders`, else an
  empty line). Ukrainian bundle (ADR 0012).
- Cash count act (`CASHBOOK_VIEW`): cooperative name, date and time, book, counted, difference, explanation, how it was
  booked, signature lines treasurer and chairman.

### 2.9 Audit

Entity types `MONEY_ACCOUNT`, `EXPENSE`, `HANDOVER`, `OTHER_INCOME`, `CASH_COUNT`, `BANK_RECONCILIATION`,
`CASHBOOK_CATEGORY`. Actions: `OPENING_BALANCE_SET`, `EXPENSE_RECORDED/REVERSED`, `HANDOVER_RECORDED/REVERSED`,
`OTHER_INCOME_RECORDED/REVERSED`, `CASH_COUNTED`, `COUNT_DIFFERENCE_BOOKED/REVERSED`, `BANK_STATEMENT_ENTERED`,
`BANK_DIFFERENCE_BOOKED/REVERSED`, `BANK_MONTH_RECONCILED`, `BANK_MONTH_REOPENED` (system subject, movement id, kind,
date, amount), `CASHBOOK_CATEGORY_ADDED/RENAMED/REORDERED/ARCHIVED`. Published inside the changing transaction
(`AuditEvent`), added to the openapi enums, i18n, `auditValue.tsx`, `audit/README.md`, and a `TreasuryAuditActionsTests`
guard.

### 2.10 API contract (`api/openapi.yaml`)

Tag `treasury`:

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /money-accounts?asOf` | VIEW | `[{id, kind, iban?, opening?{amount,date}, balance?, negative, unresolvedDifference?, lastCount?, months?[{month,status}]}]` |
| `POST /money-accounts/{id}/opening-balance` | APPROVE | `{amount, date}` |
| `GET /money-accounts/{id}/movements?from&to&page&size` | VIEW | `{openingBalance, closingBalance, items[{id, date, kind, amount, bookingMonth, beforeOpening, source{type,id}, plotId?, receiptNumber?, documentNumber?, categoryId?, reversed, reason?, createdByName, createdAt}]}` |
| `POST /money-accounts/{id}/movements/{movementId}/reverse` | APPROVE | differences only, `{reason}` |
| `POST /expenses` (multipart), `GET /expenses?from&to&account&categoryId&page&size`, `GET /expenses/{id}`, `GET /expenses/{id}/attachments/{n}`, `POST /expenses/{id}/reverse` | ENTER / VIEW / VIEW / VIEW / APPROVE | |
| `POST /handovers`, `POST /handovers/{id}/reverse` | ENTER / APPROVE | `{date, amount, documentNumber}` |
| `POST /other-income`, `POST /other-income/{id}/reverse` | ENTER / APPROVE | `{account, date, amount, categoryId, description?}` |
| `GET /expense-categories?kind&includeArchived`, `POST`, `PUT /expense-categories/{id}`, `PUT /expense-categories/order` | VIEW / APPROVE | name and `systemCode` (client translates seeded ones) |
| `POST /cash-counts[?preview=true]`, `GET /cash-counts`, `GET /cash-counts/{id}`, `POST /cash-counts/{id}/book-difference` | ENTER / VIEW / VIEW / APPROVE | `{countedAt, counted, explanation?}` |
| `GET /bank-reconciliations/{month}`, `PUT …/{month}`, `POST …/{month}/book-difference`, `POST …/{month}/confirm` | VIEW / ENTER / APPROVE / ENTER | month `YYYY-MM` (016 style) |

Tag `documents`: the two PDFs. All amounts are strings. Problem codes: 409 `CASH_INSUFFICIENT` (extension fields
`balance`, `date`), `OPENING_BALANCE_MISSING`, `OPENING_BALANCE_ALREADY_SET`, `BANK_MONTH_NOT_BALANCED`,
`ENTRY_ALREADY_REVERSED`, `DIFFERENCE_ALREADY_BOOKED`, `NO_DIFFERENCE`, `CATEGORY_NAME_TAKEN`; 400 `STATEMENT_INCONSISTENT`,
`BEFORE_OPENING_BALANCE`, `FILE_TYPE_NOT_ALLOWED`, `TOO_MANY`, field `REQUIRED`/`TOO_LONG`/`ARCHIVED`/`INVALID`; existing
`AMOUNT_NOT_POSITIVE`, `DATE_IN_FUTURE`, 413 `FILE_TOO_LARGE`, 409 `REQUISITES_MISSING`, 404 `NOT_FOUND`.

### 2.11 Web (`web/src`)

- Nav `{to: '/cashbook', label: 'cashbook', permission: 'CASHBOOK_VIEW'}`; `RequirePermission CASHBOOK_VIEW` route group
  in `App.tsx`.
- `pages/CashbookPage.tsx` (`/cashbook`): `MoneyAccountCard` ×2 (balance, warnings, last count, unresolved difference,
  months with status), `MovementsTable` per account with a period, entry buttons (`useCan('CASHBOOK_ENTER')`) opening
  `HandoverDialog`, `OtherIncomeDialog`, the expense form and the count page; opening balance form for APPROVE.
- `pages/ExpensesPage.tsx`, `ExpenseFormPage.tsx` (`/cashbook/expenses/new`, prefill from a reversed expense),
  `ExpenseCardPage.tsx` (`/cashbook/expenses/:id`, attachments, «Сторнувати і внести заново» for APPROVE).
- `pages/CashCountPage.tsx` (`/cashbook/counts/new`, `/cashbook/counts/:id`): server preview (debounced), save, act
  download, «Оформити різницю» (APPROVE).
- `pages/BankReconciliationPage.tsx` (`/cashbook/bank/:month`): four inputs, comparison and movements from the server,
  «Звірено», bank difference (APPROVE).
- `components/cashbook/*`: `ExpenseForm`, `AttachmentPicker` (`accept="image/jpeg,image/png,image/heic,image/heif,
  application/pdf"`, plus a camera button with `capture="environment"`), `AttachmentList`, `MovementsTable`,
  `MoneyAccountCard`, `ReasonDialog`; `lib/heicToJpeg.ts`.
- **HEIC:** a HEIC/HEIF file is converted to JPEG in the browser with the browser's own decoder only (`createImageBitmap` → canvas → JPEG 0.85), which Safari/iOS support; iOS usually hands over JPEG anyway. Where the browser cannot decode HEIC, the user sees the type error. No new client dependency: the HEIC libraries on npm (`heic-to`, `heic2any`) bundle LGPL-3.0 libheif, which needs the owner's licence decision (amended in Slice 2).
- Settings: `components/settings/ExpenseCategoriesPanel.tsx` (expense and income lists; rename, reorder, archive, add;
  `CASHBOOK_APPROVE`), the `ChargeTypesPage` pattern.
- Plot card: `ReconciliationActForm` tab in `PlotTabs` (`CHARGES_VIEW`); member account: «Акт звірки» per own plot.
- Reports: the four codes in `reportFilters.ts` / `reportTexts.ts`; «Звіти» visible with `REPORTS_VIEW` or
  `CASHBOOK_VIEW`.
- i18n (`uk.ts` default, `en.ts`): `nav.cashbook`, `cashbook.*`, `expenses.*`, `expenseCategories.*` (seeded names by
  system code), `reconciliationAct.*`, `permission.CASHBOOK_*`, `audit.*`, problem codes, report names and columns.
  Client shows only server amounts; no money arithmetic.

### 2.12 Demo data

- `R__demo_data.sql` ledger block deletes the demo tenant's treasury rows (attachments, handovers, counts,
  reconciliations, other income, expenses, movements — reversals first) before `payment`, re-runs `seed_treasury`, and
  writes the payment movements with the backfill `INSERT … SELECT`.
- `DemoTreasury` (`ApplicationRunner`, same condition as `DemoDocuments`, ordered after it, `TenantContext.runAs` as the
  demo chairman, through the services so encryption is real; skips when an opening balance exists): opening balances on
  the 1st of the closed month; expenses in four categories in the open month (one with a small bundled JPEG); two
  handovers; a count with a booked 20.00 shortage; the closed month `RECONCILED`; the open month with a statement that
  includes an unentered bank fee. Relative months; `DemoTreasuryTests` across `DemoDay`.

### 2.13 Documentation

ADR 0019 (synchronous in-transaction listener; attachments in the DB with size note; JDK-only metadata stripping; HEIC
in the browser; migration-time backfill read of `payment`); `treasury/README.md`; ledger README (event, new API, lock
order); reports, documents, audit READMEs; `money-rules.md` §14 «Каса і банк» (balances, no-negative cash, closed
months, counts, reconciliation, example 12 identity); glossary; `architecture.md`; guides uk/en (board guide chapters
«Каса і банк» and «Звіт для загальних зборів», Part II rules; owner manual: permissions, attachments, the payment
backfill; the «planned» row becomes «works»; fix the duplicate chapter number 26 in the planned table);
`capture-screens.mjs` entries `cashbook`, `cashbook-phone`, `expense-form`, `expense-card`, `cash-count`,
`bank-reconciliation`, `settings-expense-categories`, `plot-reconciliation-act`, `account-reconciliation-act`.

---

## 3. Impact and Risk Analysis

- **System dependencies:** ledger (new event and read API; `PaymentService`, `PaymentCorrections` publish one more
  event), documents (two layouts), reports (four definitions, controller permission rule, row set), identity (three
  permissions), audit (actions), web, demo, guides.
- **Risks and mitigations:**
  - Synchronous listener: a treasury failure fails the payment; every bank payment also locks the bank account row. →
    Narrow listener, guard tests (no publication row, rollback), lock order documented.
  - Retroactive ledger dates (payment reversals keep the original date; releases keep their allocation's date) can
    change a past act. → Documented; `paymentSplits` uses a recorded-by cut-off for the report.
  - Cash blocked after a payment reversal makes it negative. → Warning text points to a count; a surplus booking
    restores it.
  - DB size (up to 25 MB per expense). → `STORAGE EXTERNAL`, metadata-only list queries, note in ADR 0019 and the owner
    manual.
  - JPEG/PNG parser edge cases. → Fixture tests (GPS, progressive, MPF, CMYK, trailing data, malformed), refuse
    malformed files.
  - `seed_base_roles` redefinition. → Copy the V29 body; `SeedBaseRolesTests` matrices.
  - Column grants vs Hibernate updates. → Entities update only granted columns; integration tests.
  - HEIC from browsers without a native decoder (Chrome/Firefox desktop files) is refused with the type error. → Owner may later accept an LGPL library.
  - Coverage floor 0.977. → Unit tests for parsers and calculators with all error branches.
  - e2e state (reconciled months are sticky). → Specs pick months at runtime, assert deltas, run serially, never before
    `zz-closing` ordering is broken.

---

## 4. Testing Strategy

- **Unit:** `CashBookExamplesTests` (one test per FS 2.17 row on the pure calculators), `CashGuardTests`,
  `StatementRulesTests`, `BankComparisonTests`, `IncomeExpenseCalculatorTests` (identity), `FileSnifferTests`,
  `JpegMetadataStripperTests`, `PngMetadataStripperTests` (output carries no Exif/GPS/tEXt).
- **Integration (Testcontainers):** payment → movement and reversal → movement today; backfill exactly once per
  payment row; append-only triggers and grants; guard triggers (write-once opening, status machine, booked once);
  concurrency (two 150.00 cash expenses on 200.00 with a barrier: exactly one saved); closed-month booking; reopen on a
  back-dated bank payment; cash book closing balance = calculator; act ends = plot balances report (example 11);
  example 12 identity on demo data; no plaintext counterparty/description in tables, `audit_entry`, `event_publication`;
  attachment limits (5 MB, 6 files, renamed text file, GPS photo); `TenantIsolationTests` per table; permission matrix
  (VIEW/ENTER/APPROVE allowed and denied; member own plot vs other; CASHBOOK_VIEW-only custom role sees four reports);
  `TreasuryAuditActionsTests`; `ModularityTests`.
- **Clients:** vitest for the new components (permission gating, server amounts shown verbatim, HEIC path mocked),
  i18n catalog parity.
- **e2e (Playwright):** `cashbook-expense-handover.spec.ts` (`@smoke`: cash expense with a photo + handover, balances
  follow), `cash-count.spec.ts` (difference booked by the chairman), `bank-reconciliation.spec.ts` (month to «Звірено»),
  `reconciliation-act.spec.ts` (member downloads their act). Months chosen at runtime, deltas asserted, serial mode.
- **Full suite:** `make check` (coverage floor), `make e2e` in Phase 4.

---

## 5. Assumptions (pipeline mode, not confirmed by the owner)

1. One synchronous in-transaction listener for payment movements (ADR 0019 exception to ADR 0007).
2. The PDFs are rendered by `documents`; the two PDF endpoints are tag `documents`.
3. Backfilled reversal movements: date = reversal row's creation day (Kyiv), month = `greatest(row's month, that day's
   month)`.
4. Seeded categories carry a system code and are translated by the catalogs until renamed (i18n rule).
5. Categories are managed with `CASHBOOK_APPROVE`, not `SETTINGS_MANAGE`, although the panel sits on the settings page.
6. No HEIC library (LGPL-3.0); browser-native decoding only; no backend image library (amended in Slice 2).
7. A bank difference is dated the month's last day (or today if earlier); a count difference the count day.
8. Difference reversals get their own audit actions (`COUNT_DIFFERENCE_REVERSED`, `BANK_DIFFERENCE_REVERSED`).
9. The act's closing sentence uses the net balance (debt − prepayment).
10. No DB-level running-balance trigger; service guard under the row lock.
