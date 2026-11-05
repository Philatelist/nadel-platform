# Review log — 020-go-live-polish

## Baseline failures

- Base commit: `b324146` (`main`), command: `make check`
- Result: could not run: skipped by the user's instruction for this run (no test runs until the end); baseline = green CI on `main` @ `b324146`

## Slice 1 — Clearer payment, allocation, expense and overlap screens

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2fc2c51..4ef6f02` | PASS | — | static (no tests, no node_modules); minor: eslint-disable on payer effect deps, missing blank line in PaymentForm.test, fingerprint computed before reject checks |

## Slice 2 — Delete an unused tariff

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `4ef6f02..cd9183b` | PASS | — | static; minor: TariffUsages.lastChargedDay map get, double query invalidation in DeleteTariff, LedgerTariffUsage ignores tenantId (RLS) |
