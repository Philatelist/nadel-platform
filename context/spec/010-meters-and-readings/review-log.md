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
