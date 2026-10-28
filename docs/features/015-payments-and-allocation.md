# 015. Payments, cash desk and allocation

Roadmap phase 3, feature 1 of 3. Input for `/feature-pipeline`. Requires 011 (ledger, billing months, plot status),
012 (adjustments), 013 (reports) and 014 (the `privacy` cipher).

Phase 3 sequence: `015 Payments, cash desk and allocation` → `016 Bank statement import and matching` →
`017 Cash book and reconciliation`.

## 1. Goal

The treasurer records money received from a member, as cash at the cooperative's cash desk or as a bank transfer
entered by hand. The system allocates it to the plot's debts and shows what is still owed and what is paid in advance.
From then on the debt, the plot colour and the reports reflect payments. Until now they showed only charges.

**Decided with the owner (28.10.2026; the prepayment rule amended in the owner's review of PR #56):**
- **Allocation order:** oldest debts first. Within the same day, the cooperative's order of charge types decides. The
  treasurer sees the proposal and can change it before saving.
- **Cash:** an internal receipt with a sequential number. The printed form (прибутковий касовий ордер, the cash receipt
  order) comes with the PDFs of phase 4. The system is not a fiscal cash register (no ПРРО, the software cash register).
- **Prepayment:** allocated automatically to every new charge of the plot, by the same order. This is a cooperative
  setting and is on by default. With auto-allocation on, prepayment never waits while the plot has open debts: on saving
  a payment (and on a reallocation) any unallocated remainder is placed at once on the oldest debts. The treasurer
  still chooses the lines; only the free remainder is placed automatically. Prepayment stays only when the plot has no
  open debt, or when auto-allocation is off.
- **One payment for several plots of one person:** the treasurer splits it when entering it. The proposed split is in
  proportion to the plots' debts. Each part is a payment of its plot. A cash payment keeps one receipt for all its parts.

## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- `docs/domain/money-rules.md` sections 1, 2, 3 (3.1–3.3), 5.4, 7, 8 and 9;
- `docs/domain/glossary.md` (Payment, Receipt, Allocation, Prepayment);
- the READMEs of `ledger`, `reports`, `privacy`, `tariffs` (the order of charge types), `plots` (status), `members` and
  `settings`;
- ADRs 0006 (append-only ledger), 0007, 0009, 0013 (money) and 0016 (personal data encryption);
- the guides in `docs/guide/`.

General rules as in earlier features:
- contract-first;
- Flyway only;
- tenant-scoped tables with RLS and an isolation test;
- permissions, not role names;
- Problem Details with a `code`;
- i18n for every text;
- audit through the outbox;
- only the server calculates money;
- every worked example is a unit test of a pure calculator;
- the verification levels and the guides rules of `CLAUDE.md`.

**Module.** Payments and allocations live in `ledger`. The debt is "charges − allocations", and one transaction must
change both, under the same append-only rules (ADR 0006). The pipeline may propose a separate module in Phase 1 only if
it keeps one transaction and no dependency cycle; the decision goes into the ledger README.

## 2. Domain

### 2.1 Payment

A **payment** is money received for one plot. It holds:
- `plot_id`;
- `amount > 0`, two decimals;
- `received_on`: the day the money arrived, in Kyiv time, not after today;
- `source`: `CASH` or `BANK` (`BANK_STATEMENT` comes with 016);
- for `CASH`: a `receipt_number`;
- for `BANK`: an optional bank reference (the document number, up to 64 characters);
- `payer_member_id`: optional; one of the plot's owners or any member;
- `payer_note`: optional, up to 200 characters. It is personal data (a name of a person who is not a member, a phone),
  so it is encrypted with the `privacy` cipher;
- `group_id`: shared by the parts of one split payment;
- `billing_month_id`, `created_by`, `created_at`.

The payment table is append-only, like `charge`.

**Billing month.** A payment is booked in the month of `received_on` with `BillingCalendar.bookingMonth`, as charges
are (money-rules 3.1). A payment received in a closed month and entered late goes to the earliest open month and keeps
its `received_on`.

**Reversal.** A mistaken payment is reversed by a `PAYMENT_REVERSAL` row: the negative amount, a reason and a pointer to
the payment. The reversal is booked in the earliest open month. It also releases every allocation of the payment (2.3).
A payment is reversed once. A reversed cash payment keeps its receipt number, shown as reversed (money-rules 7). A part
of a split payment is reversed on its own. The other parts stay.

**Receipt numbers.** One counter per cooperative (`receipt_counter`, row lock):
- the counter has no gaps from rollbacks, because the number is taken inside the saving transaction;
- numbers are never reused and never changed;
- the format is a plain integer shown as `№ 123`;
- a split cash payment takes one number for the group.

### 2.2 Allocation

An **allocation** links a part of a payment to one charge: `payment_id`, `charge_id`, `amount`. An allocation is never
changed. A **release** is a negative allocation row that points to the allocation it cancels, in full or in part.

**Open amount of a charge** = its effective amount (the charge plus its adjustments, money-rules 5.4) − its allocations
(net of releases). Only `REGULAR` and `OPENING` charges that are not reversed can receive allocations. Reversals and
adjustments are never allocated to directly. An adjustment changes the effective amount of its original charge.

**The proposal** (pure, `AllocationPlanner`). Given an amount and the plot's charges with an open amount above 0.00, it
fills them in this order:
1. `accrued_on`, oldest first;
2. then the order of charge types in the cooperative (the order set on the charge types page, 009);
3. then the service period start;
4. then the charge id.

Each charge receives at most its open amount. What is left is the **prepayment**.

**The treasurer's change.** Before saving, the treasurer can change the amount of any line of the proposal or add
charges of the plot. The total of the lines must not exceed the payment
(400 `ALLOCATION_EXCEEDS_PAYMENT`), and no line may exceed the charge's open amount (400
`ALLOCATION_EXCEEDS_CHARGE`). What is not allocated is the remainder; 2.4 says where it goes (amended 28.10.2026).

**Reallocation** of a saved payment releases its allocations and writes new ones in one transaction. The treasurer uses
it to move money between charges. It is audited.

### 2.3 When allocations are released automatically

Allocations are released when the money they cover is no longer owed:
- **A charge is reversed** (manually, by a run reversal or by a recalculation): all its allocations are released.
- **A negative adjustment** makes a charge's effective amount smaller than its allocations: the excess is released,
  starting from the latest allocation.
- **A payment is reversed:** all its allocations are released.

The released money becomes the plot's prepayment again. The auto-allocation of 2.4 then places it on other debts, if
there are any. Every release is written in the same transaction as its cause. The ledger's booking paths call one
`AllocationReleaser`, and every later booking path must call it too (like `ChargesBooked`).

### 2.4 Prepayment and auto-allocation

**Prepayment of a plot** = its payments (net of reversals) − its allocations (net of releases). It is never negative.

**Auto-allocation.** With the new setting `autoAllocatePrepayment` (default on), the plot's prepayment is allocated by
the same order (2.2) whenever:
- a payment is saved with an unallocated remainder (amended 28.10.2026): the treasurer's lines are saved as chosen, and
  the remainder is placed at once, in the same transaction, by the standard order on the plot's open charges that are
  left after the treasurer's lines. Prepayment never waits while the plot has open debts. If the plot has no open debt,
  the remainder stays as prepayment and waits for new charges;
- a payment is reallocated and some lines are left at 0.00 (amended 28.10.2026): the same rule, so the lines left at
  0.00 do not create a waiting prepayment while open debts exist;
- charges are booked for the plot (the `ChargesBooked` event, in its own transaction after the commit, idempotent: it
  allocates only what is still unallocated);
- allocations are released (2.3).

Auto-allocation writes allocations with `created_by` = the system. The plot account shows them as «Зараховано з авансу»
("Allocated from prepayment").

With the setting off, prepayment stays until the treasurer allocates it by hand («Розподілити аванс», "Allocate
prepayment"). The button is on the plot account and on the payment card whenever the prepayment is above 0.00 and the
plot has an open charge. The plot account shows «Аванс X ₴» ("Prepayment X") next to the debt total whenever the
prepayment is above 0.00.

### 2.5 Debt and status (money-rules 2 and 9 updated)

```
debt per charge type = Σ open amounts of the plot's charges of that type
plot debt            = Σ debts per charge type
prepayment           = Σ payments − Σ allocations
oldest unpaid charge = the oldest accrued_on among the charges with an open amount > 0.00
```

`DebtCalculator` and `PlotStatusRefresher` switch to these formulas. Every payment, reversal, allocation and release
refreshes the status of its plots (the event `ChargesBooked` is generalised to `LedgerChanged(tenantId, plotIds)`, or a
second event is added; the pipeline decides). A plot whose debt is 0.00 is GREEN, also with a prepayment.

### 2.6 Splitting one payment over several plots

- The treasurer chooses a payer member. The form lists the member's current plots with their debts.
- The proposed split is in proportion to the debts. Cents are rounded down, and the leftover cents go to the largest
  remainders, ties broken by the plot order.
- An amount above the total debt: the excess goes to the first plot (in the plot order) as prepayment. The treasurer can
  move it.
- The treasurer can change any part. The parts must add up to the amount (400 `SPLIT_TOTAL_MISMATCH`).
- Each part is saved as a payment of its plot with the shared `group_id`. A cash group takes one receipt number.

## 3. Worked examples (each one a unit test of `AllocationPlanner`, the splitter or the release rules)

Charge type order: Membership, Electricity, Land tax.

| # | Situation | Expected |
|---|---|---|
| 1 | Debts: membership 600.00 (Jan), electricity 250.00 (Feb). Payment 700.00 | membership 600.00; electricity 100.00; open electricity 150.00; prepayment 0.00 |
| 2 | Same debts, payment 1000.00 | membership 600.00, electricity 250.00; prepayment 150.00 |
| 3 | Membership 300.00 and electricity 200.00 both accrued on 01.03. Payment 400.00 | membership 300.00 (it comes first in the order), electricity 100.00 |
| 4 | Example 2, then a new land tax charge of 120.00 is booked, auto-allocation on | land tax 120.00 from the prepayment; prepayment 30.00 |
| 5 | Example 4 with auto-allocation off | land tax open 120.00; prepayment 150.00 until the treasurer allocates it |
| 6 | Example 1, then the January membership is reversed | release 600.00; prepayment 600.00; auto-allocation moves 150.00 to the open electricity; prepayment 450.00 |
| 7 | Membership 600.00 fully paid; a recalculation adjusts it by −100.00 (012) | release 100.00 of the latest allocation; prepayment 100.00 |
| 8 | Membership 600.00 paid 600.00; an adjustment of +50.00 | open 50.00; debt 50.00; the plot is no longer GREEN |
| 9 | Payment 700.00 of example 1 is reversed | releases 600.00 and 100.00; debts back to 600.00 and 250.00 |
| 10 | Treasurer allocates a 700.00 payment as electricity 250.00 and membership 400.00 | saved as chosen; the remainder 50.00 goes on membership at once with auto-allocation on (open membership 150.00), or stays as prepayment with it off (open membership 200.00) |
| 11 | Treasurer allocates 900.00 of a 700.00 payment | 400 `ALLOCATION_EXCEEDS_PAYMENT` |
| 12 | Member owns plots 7 (debt 300.00) and 15 (debt 100.00), pays 200.00 | proposal 150.00 and 50.00 |
| 13 | Plots with debts 100.00, 100.00 and 100.00, payment 100.00 | 33.34, 33.33 and 33.33 (the first plot gets the extra cent) |
| 14 | Plots 7 (300.00) and 15 (100.00), payment 500.00 | 300.00 and 100.00, plus 100.00 prepayment on plot 7 |
| 15 | Cash split over two plots | two payments, one receipt number, the same `group_id` |
| 16 | A cash payment of 31.01 entered on 03.02, January is closed | booked in February with `received_on` 31.01; January's totals unchanged |
| 17 | Two treasurers save cash payments at the same time | receipts 41 and 42, no gap, no duplicate |
| 18 | Opening balance (011) membership 1200.00, payment 500.00 | allocated to the opening charge; open 700.00 |
| 19 | Debts: membership 600.00 (Jan), electricity 250.00 (Feb). Payment 700.00; the treasurer allocates electricity 250.00 only; auto-allocation on | the remainder 450.00 is placed at once on membership; open membership 150.00; prepayment 0.00 |
| 20 | Example 19 with auto-allocation off | electricity 250.00; the remainder 450.00 stays as prepayment; open membership 600.00; «Розподілити аванс» is shown |

## 4. API (`api/openapi.yaml`, tag `payments`)

**Endpoints:**
- `POST /payments?preview=true|false`: one plot or a split over several. The request carries the amount, the date, the
  source, the bank reference, the payer, the payer note, and either the allocation lines or "use the proposal". The
  preview returns the proposal (or the split and the proposals) with a digest; saving with a stale digest is 409
  `PREVIEW_OUTDATED`, as in 011.
- `GET /payments?from&to&source&plotId&month`: the list.
- `GET /payments/{id}`: the payment with its allocations and its group.
- `POST /payments/{id}/reverse`: with a reason.
- `POST /payments/{id}/reallocate?preview=true|false`.
- `POST /plots/{id}/prepayment/allocate?preview=true|false`.
- `GET /plots/{id}/account`: extended with payments, allocations, releases, the open amount per charge, the prepayment
  and the debt after payments.
- `GET|PUT /settings`: `autoAllocatePrepayment`.

**Error codes:**
- `AMOUNT_NOT_POSITIVE`;
- `DATE_IN_FUTURE`;
- `ALLOCATION_EXCEEDS_PAYMENT`;
- `ALLOCATION_EXCEEDS_CHARGE`;
- `SPLIT_TOTAL_MISMATCH`;
- `PAYMENT_ALREADY_REVERSED`;
- `PLOT_COMMON_LAND`;
- `PLOT_ARCHIVED`: refused for new payments, but a reversal is still allowed.

## 5. Data

**New tables:**
- `payment` (append-only: trigger and REVOKE as on `charge`): kind `PAYMENT`/`PAYMENT_REVERSAL`, `reverses_payment_id`,
  and a partial unique index so a payment is reversed once.
- `allocation` (append-only): `amount` positive for an allocation, negative for a release with `releases_allocation_id`.
  Its CHECK constraints and a trigger keep the target a `REGULAR` or `OPENING` charge of the same plot and cooperative.
- `receipt_counter`.

**Changed:**
- `cooperative_settings.auto_allocate_prepayment`;
- `plot_status` gets `prepayment`.

All tables are tenant-scoped with RLS, composite foreign keys on `(tenant_id, id)` and isolation tests. `payer_note` is
stored as `payer_note_enc bytea` (ADR 0016).

**Database invariant tests:**
- the allocations of a payment never exceed it;
- the allocations of a charge never exceed its effective amount.

Both are checked in the service, and a test that runs concurrent allocations on one plot proves it (the plot is locked
`FOR UPDATE` in the ledger for every payment, allocation and release).

## 6. Reports (013) and other screens

- **Plot balances:** charged, paid (allocated), debt and prepayment per charge type and in total. The "paid" columns
  promised in 013 section 2.
- **Debtors:** the debt after payments.
- **Charges by month:** a "paid" column (allocations to charges of that month), as of the report date.
- **New report "Payments" (`payments`):**
  - filters: billing months or dates, source, street;
  - one row per payment: receipt or reference, date, plot, payer (member short name; the note only with
    `MEMBERS_VIEW`), amount, allocated, prepayment, reversed;
  - totals per source.

  The full cash book and reconciliation come with 017.
- **Template catalog:** `totals.paid.<TYPE>`, `totals.prepayment`, and the `payments` row set without the payer note.
- **Billing months screen:** "received" next to "charged" per month.
- **Member account:** payments of their plots with receipt numbers and what each paid, the prepayment, the debt after
  payments.

## 7. Permissions

- **New `PAYMENTS_VIEW`** (Chairman, Treasurer): payments, allocations and the payments report.
- **New `PAYMENTS_ENTER`** (Chairman, Treasurer): enter payments, split them, reallocate, allocate prepayment.
- **New `PAYMENTS_REVERSE`** (Chairman): reverse a payment. A treasurer who made a mistake asks the chairman, the same
  rule as reversing a charge.
- **Changing `autoAllocatePrepayment`** needs `SETTINGS_MANAGE`.
- **A member** sees the payments of their own plots on the account.
- **The demo electrician** gets nothing.

Seeding is the same as in V19 and V20.

## 8. Web UI (style of 003)

1. **Платежі (Payments)** in the menu (with `PAYMENTS_VIEW`): the list with filters and «Внести платіж» ("Enter
   payment") (with `PAYMENTS_ENTER`).
2. **The payment form:**
   - one plot, or «Кілька ділянок одного платника» ("Several plots of one payer");
   - source;
   - date, defaulting to today, with the gentle warning for another date, as for readings in 010;
   - amount;
   - payer;
   - note;
   - for a bank payment, the reference;
   - then the proposal table (charge, period, open amount, to allocate, editable) and the prepayment line. With
     auto-allocation on, a note under the prepayment line says «Залишок буде зараховано автоматично на найстаріші
     борги» ("The remainder will be placed automatically on the oldest debts"), and the preview shows the resulting
     lines, the automatic ones marked. The table stays on screen with per-line problems and «Розподілено X з Y»
     ("Allocated X of Y");
   - «Зберегти» ("Save").

   After a cash payment the receipt number is shown large, with «Ще один платіж» ("Another payment").
3. **The payment card:**
   - «Зараз зараховано» ("Currently allocated") and, separately, «Історія розподілу» ("Allocation history") with
     allocations, releases and who did them;
   - the group;
   - «Розподілити аванс» ("Allocate prepayment") when auto-allocation is off and the prepayment is above 0.00 with an
     open charge;
   - «Перерозподілити» ("Reallocate") with `PAYMENTS_ENTER`;
   - «Сторнувати» ("Reverse") with `PAYMENTS_REVERSE`, with a reason.
4. **The plot account (011):**
   - payments interleaved with charges by date;
   - per charge: charged, paid, open;
   - «Аванс X ₴» ("Prepayment X") next to the debt total at the top whenever it is above 0.00;
   - the prepayment with «Розподілити аванс» ("Allocate prepayment") when it is above 0.00, an open charge exists and
     auto-allocation is off;
   - a filter «лише платежі» ("payments only").
5. **The plot card:** «Внести платіж» ("Enter payment") with the plot preset.
6. **Settings:** the switch «Автоматично зараховувати аванс» ("Allocate prepayment automatically").

## 9. Audit

- `PAYMENT_RECORDED`: plot, amount, source, date, receipt number. The payer note is encrypted as in 014.
- `PAYMENT_REVERSED`: the reason.
- `PAYMENT_REALLOCATED`: old and new lines as charge type codes and amounts.
- `PREPAYMENT_ALLOCATED`: the manual allocation of prepayment.

Automatic allocations and releases are ledger rows, not audit entries, like charges.

## 10. Docs and guides

- `money-rules.md` sections 2, 7 and 9 rewritten with 2.1–2.6 and the examples.
- Glossary: release, open amount, payment group.
- The ledger README.
- **Guides, both languages:**
  - the board guide: a new chapter «Платежі та каса» ("Payments and cash desk") in Part I, with how to enter, split,
    reallocate and reverse a payment; the allocation rule in Part II with examples 1, 2, 4, 6 and 12;
  - the owner manual: the screens-and-buttons table and the permissions matrix;
  - new screens in `capture-screens.mjs`: the payments list, the payment form with a proposal, a plot account with
    payments;
  - the PDFs rebuilt.
- **Demo data:**
  - cash and bank payments for about half of the owned plots: some paid in full, some in part, one with a prepayment,
    one split over plot 7 and plot 15 of the demo member, one reversed;
  - the debtors still have some red plots, so the map colours stay varied.

## 11. Out of scope

- bank statement import and matching (016);
- the cash book, cash handover to the bank and reconciliation (017);
- the printed cash receipt order and the debt certificate (phase 4);
- refunds of prepayment in cash;
- online payment (phase 8);
- interest or penalties on debts;
- a negative opening balance as a prepayment in the import (a follow-up, if the board needs it at go-live).

## 12. Acceptance criteria

- [ ] Every row of section 3 is a passing unit test.
- [ ] With auto-allocation on, a payment with a remainder and open debts is allocated in full at once (examples 19
      and 20; a reallocation too); prepayment stays only without open debts or with auto-allocation off.
- [ ] Booking tests cover the closed month, reversal, reallocation, automatic release on a charge reversal and on a
      negative adjustment, auto-allocation after a month run, and both settings of auto-allocation.
- [ ] `payment` and `allocation` are append-only (trigger and grant tests, as for `charge`).
- [ ] Receipt numbers have no gaps and no duplicates under concurrency.
- [ ] Allocations never exceed a payment or a charge, also under concurrent saves.
- [ ] Debt, oldest unpaid charge, status colour and prepayment follow 2.5. The `plot_status` cache equals a rebuild from
      the ledger.
- [ ] Reports: plot balances, debtors and the new payments report match the ledger on fixtures with payments. Charges by
      month still equals the billing months totals.
- [ ] Permissions as in section 7, with allowed and denied tests per endpoint. Isolation tests for the new tables. Audit
      as in section 9. No payer note in plaintext in the database or the logs.
- [ ] Docs, both guides (two languages) and the demo payments are present.
- [ ] `make check` is green. e2e:
      - the treasurer enters a cash payment for plot 9, sees the proposal and the receipt number, and the plot account
        shows the payment and the lower debt (`@smoke`);
      - the treasurer splits one payment over plots 7 and 15;
      - the chairman reverses a payment with a reason;
      - the member sees the payment on their account.
