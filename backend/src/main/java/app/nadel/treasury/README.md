# treasury

The cooperative's own money (feature 018, «Каса і банк»): the **cash account** («Каса», the cash the treasurer
holds) and the **bank account** («Банківський рахунок»), their **opening balances**, the **journal** of every move of
money and the **balance** of an account on any day. Members' debts never change here: the module only shows where the
money they paid went. Draft: slice 1 of the feature (accounts, opening balances, member payments in the journal);
expenses, handovers, other income, cash counts, bank reconciliations, the reports and the acts come with later slices.
The money rules are `docs/domain/money-rules.md` §14 (written with the feature); the decisions are ADR 0019.

Tables (`V32__treasury.sql`, all under RLS `tenant_isolation`, an index leading with `tenant_id`, `UNIQUE (tenant_id,
id)` and composite foreign keys on `(tenant_id, x_id)`):

| Table | What it holds | Writes |
|---|---|---|
| `money_account` | One `CASH` and one `BANK` account per cooperative (`seed_treasury`, called by `seed_base_roles`; deterministic id `md5(tenant || ':ACCOUNT:CASH' / ':ACCOUNT:BANK')`), the opening balance `opening_amount` (≥ 0), `opening_date`, `opening_set_by`, `opening_set_at` (all null or all set) | `nadel_app` may update only the `opening_*` columns (column grant), and the trigger `money_account_guard` lets them go from null to a value once |
| `account_movement` | The journal: `account_id`, `kind` (`MovementKind`), signed `amount` (in positive, out negative, never 0), `moved_on`, `booking_month` (the first day of the billing month, mapped to `YearMonth`), `source_type`/`source_id` (what caused it), `payment_row_id` (a `MEMBER_PAYMENT` line: the ledger payment row, a payment or its reversal; unique), `reverses_movement_id` + `reason` (a reversal: same kind and account, negated amount, reversed once), `created_by`, `created_at` | append-only (`ledger_append_only` triggers, `UPDATE`/`DELETE`/`TRUNCATE` revoked); only `MovementWriter` inserts |
| `expense`, `expense_attachment`, `expense_category`, `other_income`, `handover`, `cash_count`, `bank_reconciliation` | Created by V32 with their guards; no writer yet (later slices) | see V32 |

V32 also backfilled one movement per payment row that existed when the feature was installed (the one sanctioned
migration-time read of `payment`, between the `BACKFILL BEGIN`/`END` markers): a payment on its `received_on` in its
billing month, a reversal on the Kyiv day it was made in `greatest(its month, that day's month)`.

## Public API

| Type | Use |
|---|---|
| `MoneyAccountKind` (`CASH`, `BANK`) | The two accounts |
| `MovementKind` (`MEMBER_PAYMENT`, `EXPENSE`, `OTHER_INCOME`, `HANDOVER_OUT`, `HANDOVER_IN`, `COUNT_DIFFERENCE`, `BANK_DIFFERENCE`) | What moved the money of a journal line; a reversal has the kind of the line it reverses |

Everything else is in `internal`.

## Endpoints (tag `treasury`)

| Endpoint | Operation | Who |
|---|---|---|
| `GET /money-accounts?asOf` | `listMoneyAccounts`: both accounts, cash first: id, kind, the bank's IBAN from the requisites (`settings.CooperativeSettings`), `opening {amount, date}`, `balance` on `asOf` (absent without an opening balance), `negative`, `asOf` (default today in Europe/Kyiv; a later day is 400 `asOf` `INVALID`). Later slices add optional fields (last count, unresolved difference, months) | `CASHBOOK_VIEW` |
| `POST /money-accounts/{id}/opening-balance` | `setOpeningBalance` `{amount, date}`: once (409 `OPENING_BALANCE_ALREADY_SET`); `amount` 0.00 or more with at most 12 integer digits (400 `amount` `INVALID`), `date` not after today (400 `date` `DATE_IN_FUTURE`); 404 `NOT_FOUND`. Audit `OPENING_BALANCE_SET`. Answers the account with today's balance | `CASHBOOK_APPROVE` |
| `GET /money-accounts/{id}/movements?from&to&page&size` | `listAccountMovements`: the lines dated `from..to` (default the current month up to today; `from` after `to` is 400 `from` `INVALID`), oldest first (date, time entered, id), with `openingBalance` (end of `from − 1`) and `closingBalance` (end of `to`) over the whole period, `negative` (closing below 0.00). Each line: date, kind, amount, `bookingMonth`, `beforeOpening`, `source {type, id}`, `reversed`, `reversesMovementId`/`reason` (a reversal), `createdByName` (the author's current name through `audit.ActorNames`, as the payment card shows it to the board), `createdAt`; a member payment line adds `plotId` and `receiptNumber` (cash) or `documentNumber` (the bank reference) through `LedgerReadModel.paymentRefs`. 404 `NOT_FOUND` | `CASHBOOK_VIEW` |

A member and the platform operator hold neither permission (403); another cooperative's account is 404 (RLS).

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
`bank_reconciliation` (a later slice). Treasury paths: billing month `FOR SHARE` (`ledger.BookingMonths.bookFor`) → the
cash account → the bank account → `bank_reconciliation`. See the ledger README.

## Audit

`TreasuryAuditPublisher` publishes `AuditEvent`s in the changing transaction. Entity type `MONEY_ACCOUNT` (entity id the
account, label its kind code). `OPENING_BALANCE_SET`: `kind`, `amount`, `date` (new values only). Every action is a
value of the contract's `AuditAction` (`TreasuryAuditActionsTests`).

## Dependencies

`ledger` (`PaymentMoneyMoved`, `PaymentSource`, `LedgerReadModel#paymentRefs`; the entries of later slices
`BookingMonths`), `settings`
(`CooperativeSettings`: the IBAN), `audit` (`AuditEvent`, `ActorNames`), `identity` (`CurrentUser`, `AccessProfile`),
`tenancy` (`TenantContext`), `problem`, `money`. It never reads another module's tables. No module depends on it yet.

## Permissions

`CASHBOOK_VIEW` (chairman, treasurer): accounts and journals. `CASHBOOK_ENTER` (chairman, treasurer): entries of later
slices. `CASHBOOK_APPROVE` (chairman): opening balances (later: differences, reversals, categories). Custom roles can
hold any of them.

## Tests

`AccountBalancesTests` (FS 2.17 #1, #10, before-opening, no opening), `PaymentMovementsTests` (one movement per payment
and per split part, reversal today in the open month, a failing listener rolls the payment back, the movement is
written in the publishing transaction and no `event_publication` row exists, the listener is a plain `@EventListener`,
the tenant check), `MoneyAccountsTests` (list, opening once and audited, validation, before-opening payments not counted,
the journal with refs, paging and both balances, a reversal below zero flags the cash negative), `MoneyAccountsAccessTests`
(VIEW and APPROVE allowed and denied, custom roles, member, operator, 401, another cooperative 404),
`TreasuryAuditActionsTests`; the V32 tests `TreasuryTenantIsolationTests`, `TreasuryGuardsTests`,
`TreasuryBackfillTests`.
