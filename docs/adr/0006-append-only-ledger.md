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
