# Review log — 013 reports

## Baseline failures

Backend `./mvnw verify` on 6c121fb (detached worktree): green, exit 0, no failing tests. Client checks and e2e were not run for the baseline.

## Slice reviews


### Slice 1 — Permissions, requisites and tenant EDRPOU (c4057b0)
- Round 1: PASS. Backend slice tests 335 green on the reviewer side.
- Minor notes (not fixed): `RequisitesPanel.tsx:7` does not strip inner IBAN spaces in the `changed` comparison, so Save may stay enabled after a save with spaces; `SettingsService.Change` has ten positional parameters; `V20__reports.sql:33` slot CHECK passes NULL by design and could carry a comment.

### Slice 2 — Shared workbook guard, xlsx module (4562a10)
- Round 1: PASS. Backend slice tests 341 run, 0 failures on the reviewer side. No weakening versus the deleted ZipGuard.
- Minor notes (not fixed): `UnsafeWorkbookException.java:12` message carries the reason name, so callers must keep mapping by `reason()`; `WorkbookGuard.java:75` rejects non-positive limits, which ZipGuard did not (harmless).

### Slice 3 — Reports section and Charges by month (c69e9b5)
- Round 1: PASS. Backend slice tests 530 run, 0 failures on the reviewer side; web code read only.
- Reviewer accepted the minimal change to the guard test in `MembersAccessAcceptanceTests` (`land-tax` stripped before matching "tax").
- Minor notes (not fixed): `ReportRunner.java:~93` writes an empty cooperative name if the tenant is missing; `LedgerReportQueries.java:~47` `group by 2` on a null-cast column is obscure; `ReportRunner.file()` ordering note only.

### Slice 4 — Plot balances and Debtors (ac306f8)
- Round 1: PASS. Backend slice tests 1216 run, 0 failures on the reviewer side; web code read only (node_modules missing in the worktree).
- Minor notes (not fixed): `LedgerReportQueries.debtsAsOf` does not null-guard `byPlot.get(...)`; `DebtorsReport.load` uses `debts.get` while `PlotBalancesReport` uses `getOrDefault` (inconsistent, both safe today).

### Slice 5 — Corrections and Land tax (e0b1a38)
- Round 1: PASS. Backend slice tests 861 run, 0 failures on the reviewer side; web diff read only.
- Minor notes (not fixed): `YearFilter` rejects a year after the current one (not stated in the spec; a treasurer preparing next year's tax gets 400); the corrections query relies on `reversed_at = created_at` coming from one `Instant` in the run reversal code (documented, no code comment in `LossesRuns`/`CommonConsumerRuns`); `LandTaxReport` has no query-level row limit (plots are bounded, runner enforces the cap).

### Slice 6 — Electricity by meter tree (5943a23)
- Round 1: PASS. Backend slice tests 941 run, 0 failures on the reviewer side; web code read only (no node_modules in the worktree).
- Reviewer judgements: TreeQueries refactor is a pure extraction (no endpoint drift); losses percent rounding mirrors `LossesRunViews.pct` (not a second rule); row cap per sheet acceptable, spec wording "each report is capped" should be clarified.
- Minor notes (not fixed): `TreeFigures.percent` duplicates `LossesRunViews.pct` (add "keep in step" comments); a blank losses percent for consumption <= 0 has no reason code; tree sheet and run queries are not bounded by `rowLimit`; no test for a reversal of an already adjusted charge (cannot occur in the current ledger).

