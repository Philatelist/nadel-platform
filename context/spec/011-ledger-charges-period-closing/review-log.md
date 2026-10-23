# Review log — 011-ledger-charges-period-closing

## Baseline failures

- Base commit: `6325ed7` (`main`), command: `make check`
- Result: 0 failing (backend 1,733 tests, line coverage 0.9794 vs floor 0.97; clients ui-tokens 56, api-client 7, shared 122, web 438; contract, format and lint green)

## Slice 1 — Ledger core: charges, reversals, opening balances, the plot account

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `0f406a9..32ed18c` | MAJOR_ISSUES | M1 negative opening amount shows the format error instead of "Сума має бути більшою за нуль"; M2 V13 `seed_charge_types` `ON CONFLICT DO NOTHING` hides a name clash with an existing custom type | minor: form needs TARIFFS_VIEW for types; empty date silent; button on archived plot; BillingCalendar next month after a closed current month; audit rows after cleanup; slice 2 DELETE trigger vs test cleanups; full suite needed for a migration |
| 2 | + `303f4ff` | PASS | M1, M2 resolved | fixes: M1 not-positive text in the form (+tests); M2 explicit conflict target and rename of clashing custom types in V13 (+migration test, RED checked); minor: archived custom type with a default name not renamed; rename not audited (mention in PR); form passes `amount` vs `trimmed` |

## Slice 2 — Billing months, closing and the plot status

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `303f4ff..057fbd4` | MAJOR_ISSUES | M1 late booking skips a listed-open month without a row (BillingCalendar uses min existing open row); M2 BILLING_MONTH status values shown raw in the audit log | minor: losses/common-consumer settings fields raw in audit; JpaPlotDebtCache.record ~4 queries per plot; settings partial update without a row lock; PERIOD_IN_FUTURE wording for closing; LedgerAuditPublisher javadoc |
| 2 | + `4a0698d` | PASS | M1, M2 resolved | fixes: M1 calendar books into lastClosed+1 (+pure and API gap tests, RED checked); M2 audit status via catalog (+ losses/split settings labels); minor: BillingCalendar javadoc repeats; settings labels added beyond M1/M2 (harmless) |

## Slice 3 — round 1 (9006194): MAJOR_ISSUES
- M1 `MonthRun.java:226-262`: a booked run only re-plans open TARIFF_MONTH items of its own month; spec §2.4/§2.7 and tech §2.2 require retrying open items of earlier months. → fixing.
- Minor 1 `TariffResolver` versions never end: two FIXED_ONE_TIME tariffs of one type conflict forever. → AUTO rule: only tariffs charging > 0.00 that month take part in a conflict; fixing.
- Minor 2 `TariffMonthPlanning.java:168-173`: Conflicting queued although the plot already has this month's charge from one of the tariffs. → fixing.
- Minor 3 `MonthRun.java:114-124`: digest covers planned charges only, not conflicts. → noted.
- Minor 4 `MonthRun.java:143`: charge types locked from an unlocked snapshot. → fixing.
- Minor 5 `web/src/auditValue.tsx`: a typed reversal reason equal to a queue reason code is shown translated. → noted.

## Slice 3 — round 2 (0f8d1fe): PASS
- M1, minors 1, 2, 4 resolved.
- Minor: `api/openapi.yaml:2985` wording "that month's service period" is ambiguous (behaviour keeps the item month's period). → noted; reword in a later slice.
- Minor: `MonthRun.java:181` items closed by the run get no per-item NOT_CHARGED_RETRIED audit entry. → noted.
- Minor: `MonthRun.java:200` the run retries items of every other month, later ones too (spec says earlier). Harmless under the queue lock. → noted.
- Minor: digest still covers charges only (round 1 minor 3). → noted.

## Slice 4 — round 1 (a75bbd1 + merge parts of ff5780c): PASS
- Minor: `web/src/components/readings/ReadingHistory.tsx` delete flow does not call `invalidateAfterReading`. → carry into the Slice 5 web task.
- Minor: `web/src/pages/SettingsPage.tsx:214` "changed" compares "5" with "5.00". → carry into the Slice 5 web task.
- Minor: `MonthRun.java:170,186-195` interval charges booked by `retryAll` are not in the BILLING_MONTH_RUN audit count/total nor the run result (documented). → noted.
- Minor: `IntervalCharging.java:283` persist instead of INSERT … ON CONFLICT DO NOTHING (safe under the meter lock + unique key). → noted.
- Minor: `web/src/auditValue.tsx:188-189` `from`/`to` formatted as dates for every action. → noted.
- Minor: `settings.losses.text` wording fits PROPORTIONAL only. → carry into the Slice 5 web task.
