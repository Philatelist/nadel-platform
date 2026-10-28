# Review log — 016-invoices-and-documents

## Baseline failures

- Base commit: `a668a03` (`main`; the spec branch is cut from `5287657`, which adds only docs/features/017 and 018), command: `make check`
- Result: 0 failing (backend 3301 tests, 0 failures; contract, format, lint, client typecheck and tests green)
- e2e not part of the baseline (`make check` excludes it).
