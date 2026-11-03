# Review log — 018-cash-book-expenses-reconciliation

## Baseline failures

- Base commit: `c7419a6` (`main`), command: `make check`
- Result: 0 failing (backend 3952 tests green, coverage floor met; clients typecheck, lint and tests green: web 908, shared 130, ui-tokens 56, api-client 9)

## Slice 1 — Money accounts, opening balances and member payments in the journal

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2ad657e..64b6fcf` | PASS | — | minor: movements `closingBalance` not capped at today for a future `to` (harmless, no future movements); offset pagination with a self left-join for `reversed` — revisit at scale |
