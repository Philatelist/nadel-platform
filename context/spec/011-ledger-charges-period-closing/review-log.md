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

## Slice 5 — round 1 (5e21bfb): PASS
- Minor: `WaterConnectionPanel.tsx:51-56` buttons follow `current`; a future `until` or a future-start lasting connection gives a wrong button and 409. → carry into the Slice 6 web task.
- Minor: uk texts differ from spec wording ("Водопровід кооперативу", "Ділянка вже підключена до води"). → carry into the Slice 6 web task.
- Minor: `WATER_SOURCE_IN_USE` text says "disconnect them first" though a meter with any past connection can't be deleted. → carry into the Slice 6 web task.
- Minor: a removed well meter's lasting connections can't be reached from Settings; archiving a plot / making it common land is not blocked by a lasting connection. → Slice 9 (README note or guard).
- Minor: `WaterConnectionCommands.disconnectAll:1030` stops at the first INVALID. → noted.
- Minor: `MeterQueries.connectedToday:850-855` IN list not chunked. → noted.

## Slice 6 — round 1 (f2844d6): MAJOR_ISSUES
- M1 `web/src/pages/LossesPage.tsx:46-50`: the overlap re-preview keeps only CHARGED nodes, so UNKNOWN/SKIPPED_ABOVE nodes are never queued; Book hidden when no node is CHARGED. → fixing (react-frontend, review worktree).
- Minor 1 `LossesRuns.java:365-372`, `:539-540`: a LOSSES_NODE item is resolved by a run that covers only part of its range. → fixing.
- Minor 2 `LossesRuns.java:315-327` / V17:92: a tiny negative loss rounds to -0.000 and breaks the CHECK (500). → fixing.
- Minor 3 lock order `lockSharedNetwork` vs `MeterParents` parent replacement can deadlock (Postgres aborts one). → README note / small fix.
- Minor 4 `LossesCalculator.java:186-191` SKIPPED_ABOVE checks the whole range, not the days below. → noted.
- Minor 5 digest leaves out not-charged plot reasons. → noted.
- Minor 6 money-rules §6.4 still `valueAt(to) − valueAt(from)`. → Slice 9.

## Slice 6 — round 2 (5d03827): PASS
- M1 and minors 1-3 resolved.
- Minor: `LossesPage.tsx:46-49` a preview with no nodes shows Book and books an empty run (history row + audit, no ledger data). → carry into the Slice 7 web task.
- Minor: `LossesRunsTests.aRetryOfAnItemOtherRunsChargedSomeDaysOfNarrowsItToTheDaysLeft` does not assert the original item RESOLVED / the audit outcome. → noted for Slice 10.

## Slice 7 — round 1 (645c371, combined with dce08db/4cbd139): PASS
- Minor 1 `CommonConsumerRuns.java:390-397`, `:554-556`: a COMMON_CONSUMER item is resolved by a run over part of its range (Slice 6 fixed the same in LossesRuns). → fixing in the review worktree.
- Minor 2 `CommonConsumerSettings.ofChain` vs `CommonConsumerBillings.get`: after a meter replacement the settings screen shows the default while the run uses the old meter's settings. → fixing.
- Minor 3 `CommonConsumerPlanning.plan:255`: a consumer whose shares all fail to price is CHARGED 0.00 and blocks its range, no queue item (same as losses). → README note.
- Minor 4 `CommonConsumerSettings.ofChain`: a newer meter with only service users hides an older split. → fixing with minor 2.

## Slice 7 — round 2 (7ff6122): PASS
- Minors 1, 2, 4 resolved; 3 documented.
- Minor: `JpaMeterCatalog.java:38-43` `chainOf` has no direct test. → Slice 10.
- Minor: `UnchargedDays` has no unit test. → Slice 10.
- Minor: `CommonConsumerBillings.java:84-86` chain read before the advisory lock (harmless). → noted.
- Minor: openapi description line wrap. → noted.
