# Technical Specification: Payments, cash desk and allocation

- **Functional Specification:** [functional-spec.md](functional-spec.md) (requirement
  `docs/features/015-payments-and-allocation.md`)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

Payments and allocations live in the `ledger` module, next to `charge`, because the debt is "charges − allocations"
and one transaction must change both (ADR 0006). One additive migration **V27** adds `payment`, `allocation` and
`receipt_counter` (append-only, RLS, composite foreign keys), `cooperative_settings.auto_allocate_prepayment` and
`plot_status.prepayment`, and seeds three permissions. Pure calculators (`OpenAmounts`, `AllocationPlanner`,
`PaymentSplitter`, `ReleasePlanner`, `PaymentDigest`) carry every worked example. One service (`PaymentService`) enters,
splits, reverses and reallocates payments and allocates prepayment, with the 011 preview/digest pattern. One
`AllocationReleaser` is called by every path that reverses or lowers a charge, in that path's transaction. An
after-commit listener (`PrepaymentAllocator`) places prepayment on new charges. `DebtCalculator`, `PlotStatusRefresher`,
the plot account, the billing months screen, the reports read model and the reports switch to the after-payments
formulas. The web gets a payments area (list, form with split, card, dialogs) and account/settings changes; i18n in
both catalogs; demo payments; docs and both guides.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Module layout (`app.nadel.ledger`)

| Kind | Types |
|---|---|
| Public API (new) | `PaymentsChanged(tenantId, Set<UUID> plotIds)` (never empty, ≤ 500 plots, ids only); `PaymentSource` (`CASH`, `BANK`); `PaymentRow` (reports); `PaymentNotes` (decrypts payer notes for the payments report, `@PreAuthorize(MEMBERS_VIEW)`, `MANDATORY` tx — a documented exception to the read model's "no personal data" rule) |
| Public API (changed) | `LedgerReadModel` (new methods, 2.7); `PlotDebts` gains `charged`, `paid`, `debt` per type and `prepayment`; `MonthTypeTotals` gains `paid` |
| internal: records | `PaymentRecord` (`@Immutable`; `payment(...)`, `reversalOf(...)`), `PaymentKind`, `PaymentRepository`; `AllocationRecord` (`@Immutable`; `allocate(...)`, `releaseOf(...)`), `AllocationOrigin` (`ENTRY`, `REALLOCATION`, `PREPAYMENT_MANUAL`, `PREPAYMENT_AUTO`), `ReleaseCause` (`CHARGE_REVERSED`, `CHARGE_ADJUSTED`, `PAYMENT_REVERSED`, `REALLOCATED`), `AllocationRepository` |
| internal: pure | `OpenAmounts`, `AllocationPlanner` (`propose`, `validate`, `pairWithPayments`), `PaymentSplitter`, `ReleasePlanner`, `PaymentDigest`, `PaymentRules` (amount, Kyiv date, note ≤ 200, reference ≤ 64 BANK only, reason 1–500) |
| internal: services | `ReceiptCounter` (native, `MANDATORY`), `PlotLedgerLock` (2.4), `PlotLedgerQueries.load(plotIds)` (one `UNION ALL` statement → `PlotLedgerState` per plot), `AllocationReleaser`, `PrepaymentAllocator`, `PaymentService`, `PaymentQueries`/`PaymentViews`, `PaymentNoteCipher` (`FieldRef("payment","payer_note",id)`, modelled on `members/internal/ContactCipher`), `PaymentsController implements PaymentsApi` (also `POST /plots/{id}/prepayment/allocate`) |
| internal: changed | `DebtCalculator`, `PlotStatusRefresher`, `AccountQueries`/`ChargeViews`, `BillingMonthQueries`, `LedgerReportQueries`, `LedgerAuditPublisher`, `LedgerPlotGuard` (a plot with payments cannot be deleted), `Reversals`, `LossesRuns`, `CommonConsumerRuns`, `RecalculationService` |
| Other modules | `money`: `Money.allocate(List<Money> weights)` (cents down, leftover cents by largest remainder, ties by list order — rounding stays in `Money`, ADR 0013 note); `plots`: `PlotDebtCache.record(plotId, debt, oldestDebtDate, prepayment)`; `settings`: `CooperativeSettings.autoAllocatePrepayment()`; `identity`: three `Permission` values; `reports`: 2.7 |

No new module; `ledger → members` (payer check through the members public API) adds no cycle. `ModularityTests` guards it.

### 2.2 Data model (V27, one file)

`SET LOCAL lock_timeout = '10s'`. Every table: `tenant_id uuid not null`, RLS + `tenant_isolation` policy, indexes
leading with `tenant_id`, composite FKs on `(tenant_id, id)`, `ON DELETE RESTRICT`.

| Table | Key columns and rules |
|---|---|
| `payment` | `id`, `plot_id`, `kind` `PAYMENT`/`PAYMENT_REVERSAL`, `source` `CASH`/`BANK` (016 widens the CHECK), `amount numeric(14,2)` (> 0 for a payment, < 0 for a reversal), `received_on date`, `billing_month_id`, `group_id uuid not null` (a single payment is its own group), `receipt_number bigint` (`(kind='PAYMENT' AND source='CASH') = (receipt_number IS NOT NULL)`; every part of a cash group carries the group's number), `bank_reference` (1–64, BANK payments only), `payer_member_id` (FK member), `payer_note_enc bytea` (payments only, size-bounded), `reverses_payment_id`, `reason` (required 1–500 on a reversal), `created_by` (FK member, nullable), `created_at`. `UNIQUE (tenant_id, id, plot_id)`. Partial unique `payment_reversed_once_uq`. Exclusion `EXCLUDE USING gist (tenant_id WITH =, receipt_number WITH =, group_id WITH <>) WHERE receipt_number IS NOT NULL` (`btree_gist` exists since V8). Trigger `payment_target_guard`: a reversal targets a `PAYMENT` of the same plot with the negated amount. Append-only: `ledger_append_only()` triggers + `REVOKE UPDATE, DELETE, TRUNCATE FROM nadel_app` |
| `allocation` | `id`, `payment_id`, `charge_id`, `plot_id`, `amount numeric(14,2)` (≠ 0; negative = release), `releases_allocation_id` (`(amount < 0) = (releases_allocation_id IS NOT NULL)`), `origin`, `release_cause`, `created_by` (null = system), `created_at`. FKs `(tenant_id, payment_id, plot_id) → payment(tenant_id, id, plot_id)` and `(tenant_id, charge_id, plot_id) → charge(tenant_id, id, plot_id)` (new `UNIQUE (tenant_id, id, plot_id)` on `charge`) make "same plot and cooperative" structural; a release FK `(tenant_id, releases_allocation_id, payment_id, charge_id)` keeps its payment and charge. Trigger `allocation_guard`: a positive row targets a `REGULAR`/`OPENING` charge with no reversal and a non-reversed `PAYMENT`; a release targets a positive allocation and never exceeds what is left of it. Append-only like `payment` |
| `receipt_counter` | `tenant_id` PK, `last_number bigint not null ≥ 0`. A `BEFORE UPDATE` trigger allows only `+1`. `nadel_app`: SELECT/INSERT/UPDATE, no DELETE/TRUNCATE |
| `cooperative_settings` | `+ auto_allocate_prepayment boolean not null default true` |
| `plot_status` | `+ prepayment numeric(14,2) not null default 0 CHECK ≥ 0` |

**Deferred backstops** (constraint triggers, `DEFERRABLE INITIALLY DEFERRED`, check at commit): on `allocation` insert —
the payment's net allocations ≤ its amount (0 once reversed) and the charge's net allocations ≤ its effective amount;
on `charge` insert of a `REVERSAL` or `ADJUSTMENT` — the target's net allocations ≤ its new effective amount; on
`payment` insert of a `PAYMENT_REVERSAL` — the reversed payment's net allocations = 0. A booking path that forgets the
releaser fails at commit instead of leaving a negative open amount. The service still checks everything first, under the
plot lock (requirement §5).

**Indexes:** `allocation (tenant_id, charge_id) INCLUDE (amount)`, `(tenant_id, payment_id) INCLUDE (amount)`,
`(tenant_id, plot_id) INCLUDE (amount)`, `(tenant_id, releases_allocation_id) WHERE NOT NULL`; `payment (tenant_id,
plot_id, received_on) INCLUDE (amount, kind)`, `(tenant_id, billing_month_id)`, `(tenant_id, received_on DESC, id)`,
`(tenant_id, group_id)`. `charge_adjusts_idx` exists (V19).

**Permissions:** V27 redefines `seed_base_roles` (the V20 body + `PAYMENTS_VIEW`, `PAYMENTS_ENTER` for `CHAIRMAN` and
`TREASURER`, `PAYMENTS_REVERSE` for `CHAIRMAN`) and inserts them for existing cooperatives with `ON CONFLICT DO NOTHING`
(the V19/V20 pattern). Member and custom roles get nothing.

### 2.3 Algorithms (pure, unit-tested)

- **Open amount** (`OpenAmounts`): only `REGULAR` and `OPENING` charges that are not reversed. effective = amount + Σ
  adjustments; open = effective − Σ net allocations. A reversed charge has effective 0.
- **Planner order:** open > 0 charges by `accrued_on`, then the charge type position (`ChargeTypeCatalog.all()`, unknown
  last), then `service_from` (fallback `accrued_on`), then booking order (`created_at`, then id). Each gets
  `min(remaining, open)`; the rest is prepayment. `validate`: distinct charges, amounts > 0, each an allocatable charge of
  the plot (else 400 `VALIDATION_FAILED`, field `NOT_FOUND`), Σ ≤ payment (400 `ALLOCATION_EXCEEDS_PAYMENT`), each ≤ open
  (400 `ALLOCATION_EXCEEDS_CHARGE`, field `parts[i].lines[j].amount`).
- **Splitter:** the payer's current plots (not common land, not archived) in natural plot order, weighted by debt.
  amount ≥ total debt → each plot its debt, the excess to the first plot; total debt 0 → all to the first plot;
  otherwise `Money.allocate(debts)`.
- **Releases** (`ReleasePlanner`): a reversed charge or a reversed payment → release what is left of every live
  allocation; a charge whose net allocations exceed its effective amount → release the excess from the latest
  allocations first (`created_at` desc, id desc).
- **Prepayment** = Σ payments (reversals negative) − Σ net allocations, never negative. Allocating it pairs the planner
  lines with the payments that still have money left, oldest first (`received_on`, `created_at`, id), since every
  allocation names a payment.
- **`DebtCalculator.of(charges, allocations, payments)`**: debt per type = Σ charges of the type − Σ net allocations to
  them; prepayment as above; oldest unpaid = oldest `accrued_on` among allocatable non-reversed charges with open > 0.
  `Debts` gains `prepayment`. The old overload stays for charge-only callers.
- **Value date of an allocation** (reports "as of"): `GREATEST(payment.received_on, charge.accrued_on)`; a release has
  its allocation's payment and charge, so it shares the date. No extra column.
- **Billing month:** a payment is booked with `BillingCalendar.bookingMonth(received_on)` through
  `BillingMonths.openForBooking`; a payment reversal with `bookingMonth(original.received_on)`, i.e. the original's
  month while open, otherwise the earliest open month (the 011 rule for charge reversals).

### 2.4 Locking, releases and auto-allocation

- **Per-plot lock** `PlotLedgerLock`: a transaction advisory lock per plot
  (`pg_advisory_xact_lock(hashtextextended('ledger.plot:'||tenant||':'||plot, 0))`), taken for all plots at once in
  sorted order, remembered in a transaction-bound set (`assertHeld`). It plays the role of the requirement's "plot locked
  `FOR UPDATE` in the ledger" without touching the plots module's row (which booking paths already hold `FOR SHARE`).
- **Lock order:** charging lock → network/queue/charge type/tariff `FOR SHARE` → plot `FOR SHARE` → **plot ledger
  locks** → billing month `FOR SHARE` → `receipt_counter` row.
- **`AllocationReleaser.settle(tenantId, plotIds, cause)`** (`MANDATORY`, asserts the locks): reloads the state and writes
  releases. Callers, each in its own transaction: `Reversals.reverse` (plot lock moved before the month `FOR SHARE`),
  the losses and common consumer run reversals, `RecalculationService.book` (lock the plots of the lines and rebooked runs
  first, settle after the adjustments), `PaymentService.reverse` and `reallocate`. Paths that only add charges (month
  run, retries, readings, opening balances and their import) take no plot ledger lock. README rule: "a path that
  reverses or lowers a charge locks the plots and calls `settle`".
- **Events:** `ChargesBooked` stays for charge bookings; the new `PaymentsChanged` is published by every payment,
  reversal, reallocation, manual or automatic allocation. `PlotStatusRefresher` listens to both (`lockAll` → `load` →
  `DebtCalculator` → `record(..., prepayment)`).
- **`PrepaymentAllocator`**: `@Async @TransactionalEventListener` on `ChargesBooked`, `TenantContext.runAs`, own
  transaction: setting off → nothing; else lock the event's plots that have prepayment and open charges, recompute,
  allocate (`PREPAYMENT_AUTO`, `created_by` null), publish `PaymentsChanged`. Idempotent (only the current prepayment
  under the lock); no loop (it never publishes `ChargesBooked`). Entry, reallocation and manual allocation never trigger
  it. Releases inside a charge path are followed by that path's `ChargesBooked`, so released money is re-placed.

### 2.5 Receipt numbers

Inside the saving transaction, after the plot ledger locks:
`INSERT INTO receipt_counter VALUES (tenant, 0) ON CONFLICT DO NOTHING; UPDATE … SET last_number = last_number + 1
RETURNING last_number`. A rollback undoes the increment (no gaps); a concurrent save waits on the row lock (no
duplicates). A cash group takes one number, stored on every part.

### 2.6 API contract (`api/openapi.yaml`, tag `payments`; contract first, then `pnpm gen:api`)

| Endpoint | Permission | Notes |
|---|---|---|
| `POST /payments?preview=true\|false` | `PAYMENTS_ENTER` | Body: `amount`, `receivedOn`, `source`, `bankReference?`, `payerMemberId?`, `payerNote?`, and either `plotId` + `lines?` (absent = use the proposal) or `split: { payerMemberId, parts: [{plotId, amount?, lines?}] }`; `digest?`. Preview response: per part the plot, its amount, **all** open charges of the plot in planner order (`chargeId`, charge type, period, `accruedOn`, `open`, `proposed` — 0.00 for charges not proposed), `allocatedTotal`, `prepayment`; the split parts with their debts; `digest`. Save response: the payments (ids, `groupId`, `receiptNumber`) |
| `GET /payments?from&to&source&plotId&month&page&size` | `PAYMENTS_VIEW` | newest first |
| `GET /payments/{id}` | `PAYMENTS_VIEW` | payment, allocations and releases (charge, period, amount, origin, cause), group parts, `payerNote` only with `MEMBERS_VIEW`, author, `reversible`, `reversedBy` |
| `POST /payments/{id}/reverse` | `PAYMENTS_REVERSE` | `{reason}` |
| `POST /payments/{id}/reallocate?preview` | `PAYMENTS_ENTER` | `{lines?, digest?}`; preview shows current allocations and the proposal as if released |
| `POST /plots/{id}/prepayment/allocate?preview` | `PAYMENTS_ENTER` | `{lines?, digest?}` |
| `GET /plots/{id}/account` | as 011 | + `payments[]` (date, source, receipt/reference, amount, allocations, reversed; payer note only with `PAYMENTS_VIEW`+`MEMBERS_VIEW`, author only with `CHARGES_VIEW`), per charge `paid` and `open`, `prepayment`, debts after payments, filter `kind=PAYMENTS` |
| `GET\|PUT /settings` | as now | + `autoAllocatePrepayment` |
| `GET /charges/months…` | as now | + `received` per month |

**Digests** (SHA-256 hex like `RecalculationDigest`; optional; mismatch → 409 `PREVIEW_OUTDATED`, nothing written):
entry — amount, the parts (plot, amount), each plot's open charges in planner order as `chargeId:open`; reallocate —
payment id, live allocations `id:remaining`, open charges as if released; prepayment — plot id, prepayment, open charges.

**Problem codes:** 400 `VALIDATION_FAILED` (fields `AMOUNT_NOT_POSITIVE`, `DATE_IN_FUTURE`, `REQUIRED`, `TOO_LONG`,
`NOT_FOUND`), 400 `ALLOCATION_EXCEEDS_PAYMENT`, `ALLOCATION_EXCEEDS_CHARGE`, `SPLIT_TOTAL_MISMATCH`; 409
`PAYMENT_ALREADY_REVERSED` (also reallocating a reversed payment), `PLOT_COMMON_LAND`, `PLOT_ARCHIVED` (new payments
only), `PREVIEW_OUTDATED`; 404 `NOT_FOUND` (unknown, another cooperative's, a member's other plot); 403
`PERMISSION_DENIED`. `AMOUNT_NOT_POSITIVE`, `DATE_IN_FUTURE`, `PLOT_ARCHIVED`, `PLOT_COMMON_LAND`, `PREVIEW_OUTDATED`
already exist in `ProblemCodes`.

### 2.7 Reports and read model (013)

- `LedgerReadModel`: `debtsAsOf` returns charged/paid/debt per type and prepayment (debtors follow it); `monthTotals(...,
  asOf)` gains `paid` (allocations by value date ≤ as-of to the charges of the group; `effective` unchanged, so it still
  equals the billing months screen); `paidByType(from, to)` = allocations as of `to` to the charges counted by
  `chargedByType(from, to)` (same accrual basis as `totals.charged.<TYPE>`); `prepaymentAsOf(asOf)`;
  `payments(filter, limit)` → `PaymentRow` without the note.
- `PlotBalancesReport`/`DebtColumns`: charged, paid, debt per type and total, prepayment. `ChargesByMonthReport`: a paid
  column. New `PaymentsReport` (`payments`; `REPORTS_VIEW` + `PAYMENTS_VIEW`; filters months or dates, new
  `SourceFilter`, street; totals per source; note column only with `MEMBERS_VIEW` via `PaymentNotes`).
- Templates: `totals.paid.<TYPE>`, `totals.prepayment`, row set `payments` (no note). Filling a template that uses payment
  fields needs `PAYMENTS_VIEW`.
- `ReportsAcceptanceTests.section22_noReportHasAPaidColumn…` is rewritten to assert the new columns; the
  `OLDEST_UNPAID_IS_OLDEST_CHARGE` header note and "until payments" wording are updated (bundles, i18n, openapi).

### 2.8 Audit

Entity type `PAYMENT` (entity id = plot id, label = plot number, as `CHARGE`). Actions added to `AuditAction` and
`AuditEntityType` in openapi: `PAYMENT_RECORDED` (one per part: amount, source, receivedOn, billingMonth, receiptNumber or
bankReference, `payerNote` sealed with the 014 `FieldChange` mechanism), `PAYMENT_REVERSED` (amount, receiptNumber,
reason), `PAYMENT_REALLOCATED` (old and new lines as `<CODE> YYYY-MM amount`), `PREPAYMENT_ALLOCATED` (lines, total).
Automatic allocations and releases are ledger rows, not audit entries. Logs carry ids and counts only.

### 2.9 Web (`web/`, `packages/shared`)

- Routes (`App.tsx`, `RequirePermission`): `payments` (`PAYMENTS_VIEW`), `payments/new?plotId=` (`PAYMENTS_ENTER`),
  `payments/:id` (`PAYMENTS_VIEW`). Menu entry in `Layout.tsx` after charges. Pages `PaymentsPage`, `PaymentNewPage`,
  `PaymentPage`; components in `components/payments/`: `PaymentForm` (single/split), `ProposalTable`, `SplitPayerStep`,
  `PaymentSaved` (large «Квитанція № N», «Ще один платіж»), `ReversePaymentDialog`, `ReallocateDialog`,
  `AllocatePrepaymentDialog`; hooks `usePaymentPreview` (debounced ~400 ms, keyed by request, `keepPreviousData`) and
  the digest save with 409 re-preview copied from `RecalculationStep`; `paymentInvalidation.ts` (payments, plot account,
  plots/map, billing months, `me/account`).
- **No client money arithmetic:** totals, prepayment, per-line validity and split sums come from the server preview;
  edits re-preview; «Зберегти» is disabled while a preview is pending or failed. Amount inputs are decimal strings.
- Date: today by default, an inline warning `Notice` when it differs (Kyiv day helper from shared).
- Plot account (`PlotAccountPanel`, `ChargeList`, `DebtByType`): payments interleaved by date, charged/paid/open,
  prepayment row with «Розподілити аванс» (`PAYMENTS_ENTER`, setting off), «Лише платежі». Member account
  (`MyChargesPanel`, `AccountPage`): payments with receipt numbers, prepayment, debt after payments. Billing months
  (`MonthTotals`, `ChargesMonthPage`): «Надійшло». Settings: the switch (`SETTINGS_MANAGE`). Plot card: «Внести
  платіж». Reports: generic `ReportPage`, only i18n for `payments` and the new columns.
- i18n (uk and en, same keys — `Messages` type parity): `nav.payments`, `payments.*`, account and months additions,
  `settings.autoAllocatePrepayment`, permissions, audit actions, problem codes, `reports.payments.*` and new columns;
  `language-purity.spec.ts` route list gets the new routes.

### 2.10 Demo data (`R__demo_data.sql`, demo ledger block)

Deletes in FK order before `DELETE FROM charge`: allocation, payment reversals, payments, receipt counter. Deterministic
ids (`md5('demo-payment:'||…)::uuid`). About half of the owned plots: fully paid (cash), partly paid, a few bank
payments, one with a prepayment and no open charges (a fixed point of auto-allocation), a cash split over plots 7 and 15
of the demo member with one receipt, one reversed with its releases; debtors stay red, plot 1 stays unpaid. Allocations
computed in SQL in planner order. `receipt_counter` set to the max. The closing `plot_status` UPDATE is rewritten to the
after-payments formulas (debt, oldest unpaid, prepayment). No payer notes (SQL cannot encrypt). `DemoLedgerDataTests`
compares the demo against `AllocationPlanner` and `DebtCalculator`.

### 2.11 Docs and guides

`money-rules.md` §§2, 7, 9 rewritten (and §1 for `Money.allocate`), ADR 0013 note, glossary (release, open amount,
payment group; permission names), ledger README (tables, locks, releaser rule, events, endpoints), reports README,
settings README. Guides (uk, en): board guide chapter «Платежі та каса» (Part I) and the allocation rule with examples
1, 2, 4, 6, 12 (Part II); owner manual screens-and-buttons table and permissions matrix; `capture-screens.mjs` entries
`payments`, `payment-form`, `payment-card`, `payment-reverse`, plot account with payments; PDFs rebuilt.

---

## 3. Impact and Risk Analysis

- **System dependencies:** ledger (charges, reversals, runs, recalculation), plots (status cache), settings, identity,
  members (payer, permissions), privacy (cipher), audit, reports, web account and months screens, demo data, guides.
- **Risks and mitigations:**
  - *Deadlocks* between the plot ledger lock, the charging lock and the month `FOR SHARE` → one documented lock order;
    `Reversals.reverse` reordered; concurrency tests (entry vs entry, entry vs auto-allocator, entry vs recalculation).
  - *A booking path forgets the releaser* → `assertHeld` in tests and the deferred DB backstops fail the commit.
  - *Async auto-allocation races* → it runs under the plot lock and allocates only the current prepayment; idempotent.
  - *Receipt gaps or duplicates* → counter in the saving transaction, `+1` trigger, exclusion constraint, concurrency test.
  - *Test churn* (`ReportsAcceptanceTests.section22`, `PlotDebts`/`MonthTypeTotals` fixtures, `PlotDebtCache.record`
    callers, permission lists, settings, templates, billing months, demo ledger, audit actions) → updated in the slice
    that changes the type.
  - *e2e churn from demo payments* (`plots-acceptance` colour counts, plot 1 debt, ownership/reports colour asserts) →
    fixed in the demo-data slice; new payment specs use fresh plots and never assert a fixed receipt number.
  - *The treasurer's deliberate remainder* is placed by the next booking (requirement §2.4); *turning the setting on*
    places existing prepayments only at the next booking for the plot (no sweep job) → documented in the guide.
  - *Personal data*: payer note encrypted in the table and the audit; the payments report decrypts only with
    `MEMBERS_VIEW`; never logged.

---

## 4. Testing Strategy

- **Unit (pure, every worked example of the requirement §3):** `AllocationPlannerTests` (1, 2, 3, 10, 11, 18,
  tiebreaks), `PaymentSplitterTests` + `MoneyAllocateTests` (12, 13, 14), `ReleasePlannerTests` (6, 7, 9),
  `DebtCalculatorTests` (4, 5, 8 at state level, prepayment, oldest unpaid), `PaymentDigestTests`, `PaymentRulesTests`.
- **Integration (Testcontainers):** `PaymentEntryTests` (1, 2, 10, 11, 16, common land, archived, future date, stale
  digest), `PaymentSplitTests` (15, mismatch), `PaymentReversalTests`, `ReallocationTests`, `PrepaymentAllocationTests`,
  `AutoReleaseTests` (hand reversal, run reversals, recalculation reversal and −/+ adjustments: 7, 8),
  `AutoAllocationTests` (4, 5, 6, after a month run, both settings), `PaymentAuditTests` (sealed note, no plaintext in DB
  or captured logs), `LedgerPaymentsReadModelTests`, report and template tests, `PlotStatusRefreshTests` (cache equals a
  rebuild).
- **Concurrency:** `ReceiptNumberConcurrencyTests` (17, rollback leaves no gap), `AllocationConcurrencyTests`,
  `AllocationInvariantTriggerTests`.
- **Tables:** `PaymentTenantIsolationTests`, `PaymentAppendOnlyTests` (grants, triggers, checks, guards, release cap,
  counter trigger, exclusion constraint).
- **Permissions:** `PaymentsPermissionTests`, every endpoint × chairman, treasurer, member own/other plot, electrician,
  other cooperative, operator.
- **Web:** Vitest for the form (re-preview on edit, digest 409 retry, Save disabled while pending), list, card, account
  changes; `pnpm -r typecheck && pnpm -r lint && pnpm -r test`.
- **e2e** `web/e2e/payments.spec.ts`: cash payment with proposal and receipt, account shows it (`@smoke`); split over two
  plots of one payer; chairman reverses with a reason; member sees the payment. Fresh plots per test; receipt asserted as
  `№ \d+` and equal between success screen and card. Fixes to `plots-acceptance` and colour-dependent specs.
- `coverage.minimum` raised to the new measured floor.

---

## Assumptions

Recorded by the pipeline (autonomy `minimal`; see `pipeline-log.md`):

1. **Plot lock is a transaction advisory lock per plot** (`PlotLedgerLock`), not a `FOR UPDATE` on the plots module's
   row: same serialisation, no cross-module row locks, no upgrade deadlock with the `FOR SHARE` held by booking paths.
   The ledger README and money-rules state it.
2. **Planner tiebreak** "then the charge id" is booking order (`created_at`, then id), since ids are random UUIDs.
3. **Payment reversal month** follows the 011 charge-reversal rule (the original's month while open, otherwise the
   earliest open month), so "received" nets out in the same month when possible.
4. **Receipt number on every part** of a cash group, enforced by a gist exclusion constraint.
5. **Deferred DB backstops** for payment/charge caps and releases at commit, in addition to the service checks.
6. **Value date of an allocation** for "as of" reports = later of the payment date and the charge's debt date.
7. **`totals.paid.<TYPE>`** = paid on the charges counted by `totals.charged.<TYPE>` (accrual basis).
8. **Payer notes in the payments report** through a separate `PaymentNotes` port gated by `MEMBERS_VIEW`.
9. **`Money.allocate`** keeps the split's "cents down, largest remainder" rounding inside `Money` (ADR 0013 note).
10. **The preview returns every open charge** with `proposed` 0.00 for the ones not proposed, so the web adds charges
    without client logic.
11. **No sweep** when auto-allocation is turned on; existing prepayment is placed at the plot's next booking.
12. **Mobile** is unchanged (shell until phase 5).
