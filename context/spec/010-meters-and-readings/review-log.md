# Review log — 010-meters-and-readings

## Baseline failures

- Base commit: `063af9f` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 1,380 tests, 0 failures; coverage check met (line coverage 4,775 / 4,875 = 0.9795 vs floor 0.97); client tests ui-tokens 56, shared 79, api-client 7, web 345.
- `make e2e` not part of the baseline; it was 94/94 green on 009-tariff-version-correction (#33).

## Slice 1 — Data model, permissions and the consumption rules

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `d418b60..d201cfc` | MAJOR_ISSUES | M1 `parseMeterValue` ignores the locale: en "8,205" parsed as 8.205; M2 `formatMeterValue` keeps the server's trailing zeros ("8 205,000") against ADR 0014 "clients trim the zeros" | minor: `Quantity.toMeterValue()` throws `InvalidMeterValueException` above 11 integer digits (Javadoc); glossary states the Electrician demo grants before Slice 7 adds them; unused `MeterUnit` type spelled differently from the catalog keys |
| 2 | + `73e4b06` | PASS | M1, M2 resolved (locale-aware parse; trailing zeros trimmed) | minor: en grouped input has no 11-digit cap ("999,999,999,999" parses; the server rejects it); en "0,205" accepted as 205 |

## Slice 2 — Meter register

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `73e4b06..eca98e7` | MAJOR_ISSUES | M1 import problem `PLOT_HAS_METER` (kind column) has no `importProblem` catalog text; the preview falls back to "Значення некоректне" | minor: unused `initialPlot` prop in RegisterMeterDialog; PlotPicker needs PLOTS_VIEW (odd custom roles); `ValidationProblems.isMeterValue` keys on field names; `MeterQueries.forPlot` loads the plot before the ownership check |
| 2 | + `4fd731d` | PASS | M1 resolved (catalog entries uk/en, catalog parity code list, ImportPreviewPanel test) | — |

## Slice 3 — Readings and the round

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `eca98e7..c95e714` | PASS | — | minor: an unexpected non-problem exception in batch row N gives a 500 after rows 0..N-1 were committed; `ReadingQueries.used` calls each `ReadingUsage` port per reading (N calls; 011 should batch); the round's date hint always says "сьогодні" even after the round date changes; one long Javadoc line in meters `package-info` |

## Slice 4 — Replacement and the member flow

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `4fd731d..18f2ee9` | PASS | — | minor: ReplaceMeterDialog serial `maxLength` 100 vs contract 50; PendingReadingCard links to `/meters/:id` even without METERS_VIEW; `approvals.noInterval` text is misleading for a reading on the installation date; unused `meters.replacedBy` key (no successor link on the old meter's card); approve/reject does not invalidate the plot meters query; one long Javadoc line in MeterCommands |

## Slice 5 — The meter tree

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `18f2ee9..76ab842` | MAJOR_ISSUES | M1 `MeterParentRepository.hasChildrenAfter` off by one: a parent can be removed on D while a child's period ends on D, so the child's D→D+1 consumption falls under no node | minor: `TREE_PARENT_INACTIVE` text says "already removed" but is also sent for a parent installed later; audit README lacks the `parent` value row; Settings visible with METERS_MANAGE alone but the tree needs METERS_VIEW too; move dialog may preselect an inactive (replaced) parent; reused READING_* field texts under "У дереві з" |
| 2 | + `8c40cab` | PASS | M1 resolved (`>=` on the child period end; test covers 14.10 refused / 15.10 allowed and the slices sum) | minor: `hasChildrenAfter` now means "on or after" (name) |

## Slice 6 — Tariffs meter presence and the import sheet

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `8c40cab..acae94e` | PASS | — | minor: the meters-sheet duplicate check runs before plot resolution, so a row with an unknown/common/suppressed plot still claims the key and a later row gets `DUPLICATE_IN_FILE`; `importProblem.METER_VALUE_INVALID` text omits the 11-integer-digit limit; `hasMeterBetween` checks the whole month even for a mid-month meter-based version (note for 011); long javadoc lines in ImportVocabulary/TemplateWriter |

## Slice 7 — Demo data, docs, e2e flows and coverage

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `acae94e..304f683` | PASS | — | minor: e2e `cleanup` deletes only APPROVED readings, so a failure between submit and approve leaves an active `E2E-M-…` plot (the sweep hits the same 409); two pending demo readings (plots 7, 15) vs tech spec "one"; Квартал А rate 120 kWh/day vs ~118 below it (011 losses near zero or negative); `plotNineWasReplacedThirtyDaysAgo` asserts only `removed_on`; some DemoMeterDataTests queries lack a `tenant_id` filter |

## Slice 8 — Feature Testing & Regression

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `304f683..cb9877f` | PASS | — | minor: the §2.5 "Long interval" row is checked as range consumption (an extra 05.10 reading sits in between); a misleading comment on a correction call; both §2.10 tests need the demo plot-7 reading still pending (a manual approval on a local DB fails them until `down -v`); the e2e §2.6 #5 check gets 403 (member@other.local lacks METERS_VIEW) — the backend test covers the 404; a repeated Javadoc phrase |

## Fix — §2.9 the audit log lists meter tree moves (verify)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `cb9877f..997d9cb` | PASS | — | minor: no general test that every action the code writes is in `AuditAction` (one unknown stored value breaks the whole audit page — `AuditQueries` `AuditAction.fromValue`, pre-existing code); every current action string checked against the enum |
