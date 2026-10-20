# Functional Specification: Excel Import

- **Topic:** Loading a whole cooperative — plots, members and ownership — from one Excel file, with a preview that
  saves nothing and a commit that saves everything or nothing; repeatable without creating duplicates.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/008-excel-import.md` (roadmap phase 1, feature 4 of 4)

---

## 1. Overview and Rationale (The "Why")

A cooperative joining Nadel already has its plots and members in a spreadsheet. Typing hundreds of plots, members and
owners by hand takes days and invites mistakes. This feature lets the chairman fill in one Excel file from a template
and load it in one go. Before anything is saved the chairman sees exactly what will happen — how many records are new,
changed or unchanged — and every problem in the file, row by row. Only a file without problems can be loaded, and it is
loaded completely or not at all. Loading the same file again changes nothing, so a corrected file can simply be
uploaded again.

**Success looks like:** the chairman downloads the template, fills in 30 plots and 35 members with their owners, sees
the preview, loads it, and finds every plot with its owners in the plots list — in under ten minutes, with no
duplicates after a second upload.

---

## 2. Functional Requirements (The "What")

### 2.1 Template and file format

- The import screen offers a template file to download. It has three sheets — "Plots", "Members" and "Ownership" —
  each with column headers, a short instruction row and one example row. Instruction and example rows begin with "#"
  and are never imported; any row whose first cell begins with "#" is skipped.
- **Plots:** number, street, kind ("PLOT" or "COMMON"), area in square metres **or** in sotkas (exactly one of the two),
  cadastral number, building (yes/no, default no), notes.
- **Members:** a member key (any text that is unique within the file and used only to link owners to members), last
  name, first name, middle name, phones (separated by commas), email, postal address, preferred delivery channel,
  Telegram username, Viber phone.
- **Ownership:** plot number, member key, share (for example "1/2"), owner since.
- Column headers are matched regardless of letter case, and Ukrainian header names are accepted as well (for example
  "Номер", "Вулиця", "Площа, сот."). Columns may appear in any order; unknown columns are ignored.
- Dates may be Excel dates or text in the form DD.MM.YYYY. "Yes/no" accepts yes/no, так/ні and true/false in any case.
- The same rules apply as when entering records by hand: plot number required and unique, cadastral number format,
  area greater than zero for a plot, a valid email and phone, shares as exact fractions that add up to exactly one per
  plot, an owner-since date not in the future, common land never has owners.
  - **Acceptance Criteria:**
    - [x] When the chairman opens the import screen and clicks "Завантажити шаблон" / "Download template", then a file
      with the three sheets, their headers, an instruction row and an example row is downloaded. _Evidence: `ImportsTemplateTests` (three sheets, headers, instruction and example rows); `ImportAcceptanceTests` §2.1 (downloads and re-uploads the template); e2e `imports.spec.ts` (download event, size > 0); screenshot `docs/screenshots/008-excel-import-page.png`._
    - [x] When the chairman uploads the unchanged template, then the preview shows nothing to import (the instruction
      and example rows are skipped) and no errors. _Evidence: `ImportAcceptanceTests` §2.1 (downloaded template → nothing to import, no problems); `ImportPreviewPlotsTests`/`ImportPreviewMembersTests`/`ImportPreviewOwnershipTests` (unchanged template)._
    - [x] When a file uses Ukrainian headers ("Номер", "Вулиця", "Площа, сот.") in a different order and different
      letter case, then the preview reads the columns correctly. _Evidence: `ImportAcceptanceTests` §2.1 (Ukrainian headers, other order and case); `ImportPreview*Tests.ukrainianHeadersInAnyOrderAndCaseAreRead`; `HeaderMatcherTests`; e2e `imports-acceptance.spec.ts`._
    - [x] When a plot row gives both an area in square metres and an area in sotkas, or neither, then the preview shows
      an error on that row's area column. _Evidence: `ImportAcceptanceTests` §2.1 (both or neither → AREA_BOTH_OR_NONE on the area column); `ImportFixturesTests` (errors.xlsx); screenshot `008-excel-import-preview-errors.png`._
    - [x] When a plot gives its area as 6 sotkas, then after the import the plot shows "6 соток (600 м²)". _Evidence: `ImportAcceptanceTests` §2.1 (600 m² read back via `/plots`); e2e `imports-acceptance.spec.ts` ("6 соток (600 м²)" on the plot page); `CellsTests`._

### 2.2 Upload and preview

- The chairman uploads one `.xlsx` file. Nothing is saved at this step. The preview shows, for each sheet, how many
  records are new, will be updated, and are unchanged, and a table of every problem with the sheet, row number,
  column and a message in the chosen interface language.
- Files that are not `.xlsx`, larger than 10 MB, or with more than 2,000 plots or 3,000 members are refused with a
  clear message, before any preview.
- Macros and links to other files inside the workbook are ignored.
  - **Acceptance Criteria:**
    - [x] When the chairman uploads the valid example file (30 plots, 35 members, co-owned plots), then the preview
      shows 30 new plots, 35 new members and the ownership rows, and no errors. _Evidence: `ImportAcceptanceTests` §2.2 and `ImportFixturesTests` (valid.xlsx → 30/35/35, no problems); screenshot `008-excel-import-preview-valid.png`._
    - [x] When the chairman uploads a file with typical mistakes (a duplicate plot number, a wrong cadastral number, a
      bad email, shares 1/2 + 1/3 on one plot, an owner key that is not in the Members sheet), then the preview lists each
      problem with its sheet, row, column and a message, and the commit button is disabled. _Evidence: `ImportAcceptanceTests` §2.2 and `ImportFixturesTests` (errors.xlsx → 11 problems with sheet, row, column, code); `ImportCommitTests.aPreviewWithProblemsCannotBeCommitted`; e2e `imports.spec.ts` (problems listed, Import disabled); screenshot `008-excel-import-preview-errors.png`._
    - [x] When the chairman uploads a file larger than 10 MB, then they see "Файл завеликий — максимум 10 МБ" / "The file
      is too large — the maximum is 10 MB" and no preview. _Evidence: `ImportAcceptanceTests` §2.2; `ImportUploadLimitTests`; e2e `imports.spec.ts` (10 MB + 1 → exact message, no preview); `importFile.test.ts`._
    - [x] When the chairman uploads a file that is not an Excel workbook (for example a CSV or a PDF), then they see that
      only `.xlsx` files are accepted. _Evidence: `ImportAcceptanceTests` §2.2; `ImportPreviewPlotsTests.aCsvOrAPdfIsNotAWorkbook`; e2e `imports-acceptance.spec.ts` (CSV refused); `importFile.test.ts`._
    - [x] When the chairman uploads a file with more than 2,000 plots, then they see that the limit is 2,000 plots and
      3,000 members. _Evidence: `ImportAcceptanceTests` §2.2; `ImportPreviewPlotsTests.moreThanTwoThousandPlotRowsAreRefused`; e2e `imports-acceptance.spec.ts` (2,001 plots → uk limit message)._
    - [x] When the chairman uploads a file and then opens the plots list without committing, then nothing from the file
      appears. _Evidence: `ImportAcceptanceTests` §2.2 (plots list unchanged after the preview); `ImportPreview*Tests` (preview saves nothing, no audit); e2e `imports.spec.ts` (API check after the preview)._

### 2.3 Commit

- With a preview that has no errors, the chairman clicks "Імпортувати" / "Import". Everything is saved together; if
  anything fails, nothing is saved and the chairman sees what went wrong.
- A preview can be committed for 30 minutes. After that, or if plots, members or owners of the cooperative changed in a
  way that alters the preview, the commit is refused and the chairman is asked to upload the file again.
- After a successful commit the screen shows a summary of what was created and updated, with links to the plots and
  members lists.
  - **Acceptance Criteria:**
    - [x] When the chairman commits the valid example file, then the summary shows the counts from the preview, and the
      plots list shows the 30 plots with their owners. _Evidence: `ImportAcceptanceTests` §2.3/§2.5 (summary counts, 30 plots with owners via `/plots`); `ImportCommitTests.theCommittedValidWorkbookShowsEveryPlotWithItsOwners`; e2e `imports.spec.ts` (commit, summary, plots with owners)._
    - [x] Given a preview older than 30 minutes, when the chairman clicks "Import", then the import is refused with a
      message to upload the file again, and nothing is saved. _Evidence: `ImportCommitTests.aPreviewOlderThanThirtyMinutesIsRefusedAndItsPlanIsDropped` (410 IMPORT_EXPIRED, nothing saved) and the 29-minute counterpart; `ImportPage.test.tsx` ("Upload the file again")._
    - [x] Given another person added a plot with one of the file's plot numbers after the preview, when the chairman
      commits, then the import is refused with a message that the data changed and the file must be uploaded again, and
      nothing is saved. _Evidence: `ImportCommitTests.aPlotCreatedAfterThePreviewMakesTheCommitStale` (409 IMPORT_STALE, nothing saved), `aMemberArchivedAfterThePreview…`; `PlanDigestTests`; RED-checked by skipping the digest comparison._
    - [x] When saving fails part-way (for example a rule is broken by a record created meanwhile), then nothing from
      the file is saved. _Evidence: `ImportCommitTests.aFailureAfterTheMembersWereSavedRollsEverythingBack` (members, plots, ownership and audit unchanged; RED-checked by removing atomicity); `ImportConstraintViolationTests` (four constraints at a batched flush → IMPORT_STALE, nothing persisted)._

### 2.4 Repeating an import

- Plots are matched by their number among active plots. Members are matched by email when the row has one, otherwise
  by last name, first name and first phone, among active members. Matched records are updated, never duplicated.
- An empty cell in the file leaves the existing value unchanged. The import never archives or deletes anything.
- Ownership already recorded exactly as in the file is skipped. A plot that has no current owner gets the owners from
  the file. A plot whose current owners differ from the file is an error: the chairman uses "Change owner" for it
  instead.
  - **Acceptance Criteria:**
    - [x] Given the valid example file was imported, when the chairman uploads the same file again, then the preview
      shows everything as unchanged and committing it changes nothing. _Evidence: `ImportAcceptanceTests` §2.4 (second upload all unchanged, nothing to commit); `ImportCommitTests.aSecondUploadOfTheSameFileHasNothingToCommit…`; e2e `imports.spec.ts` (re-import, Import disabled)._
    - [x] Given a plot was imported with street "Garden St", when the chairman uploads a file where the same plot number
      has street "Orchard St", then the preview counts one updated plot and, after the commit, the plot shows "Orchard
      St". _Evidence: `ImportAcceptanceTests` §2.4 (one updated plot, street read back); e2e `imports-acceptance.spec.ts`; `ImportCommitTests.anUpdateChangesThePlotAndTheMemberFoundByEmail`._
    - [x] Given a member exists with the email in the file, when the file has a new phone for that member, then the
      member is updated, not added a second time. _Evidence: `ImportAcceptanceTests` §2.4 (member updated, not duplicated; new phone read back); `ImportCommitTests.anUpdateChangesThePlotAndTheMemberFoundByEmail`._
    - [x] Given plot 9 is owned by one member, when a file gives plot 9 a different owner, then the preview shows an
      error on that ownership row saying to use "Change owner" instead. _Evidence: `ImportAcceptanceTests` §2.4 (OWNERSHIP_CONFLICT on the ownership row); `ImportPreviewOwnershipTests.aDifferentOwnerOfDemoPlot9IsAConflict…`; e2e `imports-acceptance.spec.ts` (uk message to change the owner on the plot card); screenshot `008-excel-import-preview-errors.png`._
    - [x] Given a plot has notes, when the file leaves the notes cell empty for that plot, then the notes stay as they
      were. _Evidence: `ImportAcceptanceTests` §2.4 (notes kept, read back through the plot card); `ImportCommitTests.anUpdate…` (database check); RED-checked._

### 2.5 History and audit

- An "Історія імпорту" / "Import history" list shows each import: who, when, file name, counts, and whether it was
  committed, refused or never committed.
- A committed import appears in the audit log as one entry with the file name and the counts; each created or changed
  plot, member and ownership appears too, as when entered by hand.
- Only people allowed to run imports (the chairman by default) see the import screen and the history; others get "You
  don't have access".
  - **Acceptance Criteria:**
    - [x] When the chairman commits an import and opens the import history, then the import is listed with their name,
      the time, the file name and the counts. _Evidence: `ImportAcceptanceTests` §2.5 (history row with the chairman's name, time, file, counts); `ImportHistoryTests`; e2e `imports.spec.ts` (history table); `ImportHistory.test.tsx`._
    - [x] When the chairman commits an import and opens the audit log, then there is one import entry with the file name
      and counts, plus entries for the created plots and members. _Evidence: `ImportAcceptanceTests` §2.5 (one IMPORT_COMMITTED entry with file name and counts plus PLOT_CREATED/MEMBER_CREATED entries via `/audit`); `ImportCommitTests.theImportAndEveryRecordAreAudited`._
    - [x] When the treasurer or a member signs in, then the menu has no import item, the import address shows "You don't
      have access", and an upload sent anyway is refused. _Evidence: `ImportAcceptanceTests` §2.5 (upload, template and history → 403 for treasurer and member); `ImportCommitTests.onlyThoseWithImportRunMayCommit`; e2e `imports.spec.ts` (no menu item, no access); screenshot `008-excel-import-no-access-treasurer.png`._
    - [x] When the chairman of one cooperative imports a file, then no plot, member or owner of another cooperative is
      created or changed. _Evidence: `ImportAcceptanceTests` §2.5; `ImportCommitTests.committingInOneCooperativeNeverChangesAnother`; `ImportRunTenantIsolationTests`; `ImportHistoryTests.anotherCooperativesRunsAreNeverListed`._

---

## 3. Scope and Boundaries

### In-Scope

- Template, upload with preview, commit, repeat import, import history, audit, limits, the import screen.

### Out-of-Scope

- Polygons or cadastral files (phase 6), CSV or Google Sheets, scheduled or automatic imports, mobile screens.
- Ending or changing existing ownership through a file (the change-of-owner flow does that), archiving or deleting
  records through a file, ownership history (past owners) in the file.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking). Decisions already made in
  the feature document: all-or-nothing import with a mandatory preview; the matching keys; one file with three sheets.
- The Ownership sheet lists current owners only; existing identical ownership is skipped, a plot without a current
  owner gets the file's owners, and any other current ownership is an error.
- Empty cells leave existing values unchanged; the import never archives or deletes.
- Only active plots and members are matched; archived records are never updated by an import.
- Rows whose first cell starts with "#" are skipped (the template's instruction and example rows).
- The Members sheet has optional Telegram username and Viber phone columns, so those delivery channels can be imported.
- A preview can be committed within 30 minutes; any change in the cooperative's data that would alter the preview
  makes it stale.
- Problems are shown in the interface language chosen by the person viewing the preview.
- The template's headers and instruction row are in English; Ukrainian header names are accepted on upload.
- A member row with neither an email nor a phone is a problem, because such a member could not be recognised on a
  later import; a row whose name and first phone match two existing members is a problem too.
- When comparing a file's owners with the recorded ones, only the members and their shares count, not the owner-since
  date; all owners of one plot in the file share one owner-since date.
- Running imports, downloading the template and viewing the history all need the permission to run imports.

---

## Change Log

- Verified (pipeline Phase 4): every acceptance criterion marked with its evidence; `make check` and `make e2e` green.
