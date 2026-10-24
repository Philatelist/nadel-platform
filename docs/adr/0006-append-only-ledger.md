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
  `REVERSAL_NOT_REVERSIBLE`, `CHARGE_IN_CLOSED_MONTH`); a run is reversed as a whole.
- **Adjustments and recalculation (feature 012).** A recalculation corrects the past with new rows only: in an open
  month the booked charge gets a reversal (source `RECALCULATION`) and a new charge, in a closed month an `ADJUSTMENT`
  (either sign, never zero) is booked in the earliest open month, pointing at the original charge (`adjusts_charge_id`)
  and keeping its service period and debt date. The effective amount of a charge is the charge plus its adjustments;
  nothing is ever updated. `V19__recalculation.sql` keeps this in the database: CHECK constraints on the new kind and
  source, the trigger `charge_target_guard` (an adjustment targets only a `REGULAR` charge that is not reversed, a
  reversal never targets an adjustment or a reversal), one adjustment per charge and recalculation
  (`charge_adjustment_once_uq`), and the records `recalculation` and `recalculation_line` under the same append-only
  triggers as `charge` (function `ledger_append_only`). An adjustment is never reversed by hand
  (`ADJUSTMENT_NOT_REVERSIBLE`); a mistake is corrected by another recalculation. A used tariff version is deleted
  softly (`tariff_version.deleted_at`), because the append-only `charge_part` rows keep pointing at it. A version or
  reading that charges used is changed only together with a recalculation; without it the 011 refusals stay
  (`TARIFF_VERSION_IN_USE`, `READING_IN_USE`). Rules: `docs/domain/money-rules.md` section 5. A recalculation runs
  synchronously under the cooperative's charging lock (a manual range is capped at 24 months), not as a JobRunr job.
- **Rebuildable cache.** Balances are never stored: the plot account sums the charges per charge type
  (`DebtCalculator`). The map colour reads `plot_status`, a cache of the debt and the oldest unpaid debt date that
  `PlotStatusRefresher` rebuilds from the ledger after every booking or reversal (event `ChargesBooked`); the colour is
  derived when read, from that cache, the red threshold of the settings and today.
