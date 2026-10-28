# Review log — 015-payments-and-allocation

## Baseline failures

- Base commit: `75db154` (`main`), command: `make check`
- Result: 0 failing (backend 2969 tests, 0 failures; contract, format, lint, client typecheck and tests green)
- e2e not part of the baseline (`make check` excludes it).

## Slice 1 — ledger tables, permissions and the pure allocation rules

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `6a3d4a3..23557e8` | PASS | — | minor: release-cap check in `allocation_guard` relies on the per-plot lock of later slices (service must lock before releasing); allocation to a reversed payment caught by the deferred backstop when the reversal is in the same transaction |
