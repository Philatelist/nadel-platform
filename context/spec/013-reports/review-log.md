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

### Slice 7 — Water and Missing readings (ac01355)
- Round 1: PASS. Backend slice tests green on the reviewer side (exit 0); web code read only.
- Reviewer judgement (minor, needs owner confirmation): missing-readings treats a meter installed or removed INSIDE the range as having a reading point, so it is never listed for that range. The spec only excludes meters installed after the range or removed before it. Residual gap: a new plot meter that gets no first reading inside the range is invisible for that range. Suggested: confirm this reading or list such meters using the installation date as the last point (`initial=true` is already modelled).
- The pump share (COMMON_CONSUMER, split CONNECTED_PLOTS) appears in both electricity-tree and water: accepted, documented in the READMEs.
- Minor notes (not fixed): a long javadoc line in `LedgerReadModel.java:20`; `waterCharged` javadoc wording about cap+1.

### Slice 8 — Report templates: storage, tax slot, upload (0af4b79)
- Round 1: MAJOR_ISSUES. Backend slice tests 183 green on the reviewer side.
  - M1 `ExternalLinkStripper.java:518-526`: the RELATIONSHIP/OVERRIDE/EXTERNAL_REFERENCES regexes use a recursive lazy attribute group; a well-formed start tag with ~20 KB of attributes throws `StackOverflowError`, run on every accepted upload, so a crafted <=2 MiB file gets an unhandled 500 instead of 400 TEMPLATE_INVALID. Sent to the builder (spring-backend) for a minimal fix.
- Minor notes (not fixed unless the owner asks): entry names are not checked for `../`, absolute paths or backslashes; formulas/defined names referencing `[1]` stay after externalReferences is removed (Excel may offer to repair; check with real Excel); DOCTYPE only checked on `.xml`/`.rels` parts; on the tax card a 409 TEMPLATE_NAME_TAKEN sets `nameError` but no name field exists there, so the user sees no message (when an ordinary template already carries the tax default name); no unit test for `contentDisposition` with CR/LF, quotes or non-ASCII names.

- Fix commit 65a96af (`fix(reports): 013 slice 8 review fixes (round 1)`): non-recursive forward scanner replaces the regexes; `refusingFailures` maps IOException/RuntimeException/StackOverflowError to 400 BROKEN_ZIP. RED shown by the builder (StackOverflowError with the old code).
- Round 2: PASS. 187 tests, 0 failures on the reviewer side. M1 resolved. Minor (not fixed): `attributes()` still uses an `ATTRIBUTE` regex (no repeated group, no stack risk); a StackOverflowError mapped to BROKEN_ZIP leaves no log line.

### Slice 9 — Template filling, field catalog and Generate (15674cb)
- Round 1: MAJOR_ISSUES. Backend slice tests 400 green on the reviewer side; web code read only.
  - M1 `XmlTree.java:188` (and the direct write calls in `SheetFiller.inlineText`, `TemplateFiller.coreProperties`, `textRow`, `Copy.flush`): the JDK `XMLStreamWriter` does not reject characters forbidden by XML 1.0 (VT, FF, U+FFFE, lone surrogates). User-entered free text (street, plot number, charge type names, requisites, template name as dc:title) can carry them, so one such value makes every generated workbook corrupt (Excel repair prompt) while the server answers 200 and audits. Sent to the builder for a minimal fix at the single write sink.
- Reviewer judged sound: placeholders resolved by whitelist only; values never written into `<f>`; no regex over whole XML; DOCTYPE refused at fill time; output bounds; Content-Disposition safe; warnings headers bounded; catalog has no personal data; authorization, tenancy and audit.
- Minor notes (not fixed unless the owner asks): engine size 1912 lines (FormulaShifter 481, TemplateFiller 751, SheetFiller 367, XmlTree 313) is within ~4 % of the ADR 2000-line POI trigger, and supporting files add ~960 more: record the decision in the ADR; orphaned `list` javadoc in `ReportTemplateService`; a table lying only on the repeating row gets `ref="#REF!"` with an empty row set; `reportTemplateWarnings.ts` casts `reason` without validating; the Generate button shows for manage-only roles (generate needs REPORTS_VIEW); the 20 000 x 20 performance test is a wall-clock bound; VALUE_UNKNOWN is warned once per copy per cell (warnings list can get large).

- Fix commit 8f78b3e (`fix(reports): 013 slice 9 review fixes (round 1)`): `XmlText.clean` (forbidden XML 1.0 characters become a space, valid surrogate pairs kept) applied at the single write sink (`XmlTree.write`, `Copy.flush`, both attribute writes, dc:title, warnings cells). RED shown by the builder: with the fix reverted the new test fails with "Illegal XML character ((CTRL-CHAR, code 11))" in sheet1.xml and in docProps/core.xml.
- Round 2: PASS. 404 tests, 0 failures on the reviewer side. M1 resolved. The reviewer decompiled fastexcel 0.20.2 `XmlEscapeHelper`: it keeps only XML 1.0 Char code points, so the built-in report writer has no such exposure. Minor (not fixed): no test feeds a dirty value into an attribute (one line of production code).

