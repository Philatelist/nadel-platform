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

