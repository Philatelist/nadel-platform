# Review log — 011-ledger-charges-period-closing

## Baseline failures

- Base commit: `6325ed7` (`main`), command: `make check`
- Result: 0 failing (backend 1,733 tests, line coverage 0.9794 vs floor 0.97; clients ui-tokens 56, api-client 7, shared 122, web 438; contract, format and lint green)

## Slice 1 — Ledger core: charges, reversals, opening balances, the plot account

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `0f406a9..32ed18c` | MAJOR_ISSUES | M1 negative opening amount shows the format error instead of "Сума має бути більшою за нуль"; M2 V13 `seed_charge_types` `ON CONFLICT DO NOTHING` hides a name clash with an existing custom type | minor: form needs TARIFFS_VIEW for types; empty date silent; button on archived plot; BillingCalendar next month after a closed current month; audit rows after cleanup; slice 2 DELETE trigger vs test cleanups; full suite needed for a migration |
| 2 | + `303f4ff` | PASS | M1, M2 resolved | fixes: M1 not-positive text in the form (+tests); M2 explicit conflict target and rename of clashing custom types in V13 (+migration test, RED checked); minor: archived custom type with a default name not renamed; rename not audited (mention in PR); form passes `amount` vs `trimmed` |
