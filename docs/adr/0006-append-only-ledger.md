# 0006. Append-only money ledger

**Status:** accepted

## Context
Retroactive recalculations, disputes with members, audits by the cooperative's review committee. A stored balance drifts away from history and cannot explain where an amount came from.

## Decision
Charges, payments and allocations are immutable entries. Balances are calculated. Corrections are reversals and adjustments with a reason. A closed period never changes. Details: `docs/domain/money-rules.md`.

## Consequences
- Full history and an answer to "where does this debt come from".
- Reports are queries over the ledger; the map uses a rebuildable status cache.
- In the DB, `nadel_app` is denied UPDATE/DELETE on ledger tables (added in phase 2).

## Implementation notes (2026-10)
The ledger arrived with feature 011 (module `ledger`). Payments and allocations follow in phase 3.

- **Two locks on the append-only tables.** `charge` and `charge_part` (`V13__ledger_core.sql`): `nadel_app` has no
  `UPDATE`, `DELETE` or `TRUNCATE`, and a `BEFORE UPDATE OR DELETE` row trigger plus a `BEFORE TRUNCATE` statement
  trigger (`ledger_append_only`) refuse them to every other role too, the table owner included. In Java the entities
  are `@Immutable` and only ever persisted. `LedgerAppendOnlyTests` proves every refusal.
- **The maintenance bypass.** The triggers let a change through only for `nadel_owner` with the transaction setting
  `app.ledger_maintenance = 'on'` (`SET LOCAL`): the demo data (`R__demo_data.sql`, block `demo ledger`, which books the
  demo cooperative's ledger again on every run) and test helpers clear ledger rows that way. `nadel_app` is refused even
  with the setting, so the application can never use it.
- **The same rule for the history around the ledger.** A closed `billing_month` never changes and no month is deleted
  (trigger `billing_month_guard`, V14); a losses or common consumers run and its nodes or lines change only by their
  reversal, `BOOKED` -> `REVERSED` (trigger `run_reversal_guard`, V17, V18). Both use the same owner-only bypass.
- **Corrections.** A wrong charge is reversed once, with a reason, in an open month (`CHARGE_ALREADY_REVERSED`,
  `REVERSAL_NOT_REVERSIBLE`, `CHARGE_IN_CLOSED_MONTH`); a run is reversed as a whole. Adjustments for closed months and
  recalculation after a change of a used tariff version or reading are feature 012; until then such versions and
  readings cannot be corrected (`TARIFF_VERSION_IN_USE`, `READING_IN_USE`).
- **Rebuildable cache.** Balances are never stored: the plot account sums the charges per charge type
  (`DebtCalculator`). The map colour reads `plot_status`, a cache of the debt and the oldest unpaid debt date that
  `PlotStatusRefresher` rebuilds from the ledger after every booking or reversal (event `ChargesBooked`); the colour is
  derived when read, from that cache, the red threshold of the settings and today.
