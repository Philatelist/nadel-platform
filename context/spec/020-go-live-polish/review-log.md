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

## Slice 3 — Archive a used tariff; new tariff form hints

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `cd9183b..ddf8c81` | PASS | — | static + offline compile OK; minor: long javadoc line in ChargeCalculator, redundant query invalidation in ArchiveTariffDialog, comment in TariffVersionChangeService |

## Slice 4 — Invite a member and set the password (backend, realm, contract)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `ddf8c81..a2e5224` | PASS | — | static + offline compile OK; minor: accept holds locks across Keycloak calls (comment), legacy NONE window (comment), no rate limiting on public endpoints (hardening follow-up) |

## Slice 5 — Access block on the member card and the set-password page

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a2e5224..ef3b71e` | MAJOR_ISSUES | M1 public set-password calls may send a stale bearer token | minor: resend/disable wired early (slice 6 adds tests); set-password topic front matter to confirm in slice 9 |
| 2 | + `579d6ad` | PASS | M1 resolved | minor: startsWith on relative URL only |
