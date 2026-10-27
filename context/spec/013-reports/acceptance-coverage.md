# Acceptance coverage: 013 Reports and report templates

Every acceptance criterion of `functional-spec.md` (sections 2.1 to 2.16, 61 criteria) mapped to the tests that check it.
`NEW` marks a test written in Slice 12: backend `ReportsAcceptanceTests` = `ACC`
(`backend/src/test/java/app/nadel/acceptance`, with the small bridge `reports/internal/ReportsFixtures`), web additions in
`SettingsPage.test.tsx`, `ReportPage.test.tsx` and `reportTemplateError.test.ts`, one e2e addition in `reports.spec.ts`.
Backend classes without a path are in `backend/src/test/java/app/nadel/reports/internal`; web tests are under `web/src`,
e2e under `web/e2e`.

Short names: CBM = `ChargesByMonthReportTests`, PBR = `PlotBalancesReportTests`, DEB = `DebtorsReportTests`,
ELE = `ElectricityTreeReportTests`, WAT = `WaterReportTests`, MIS = `MissingReadingsReportTests`,
TAX = `LandTaxReportTests`, COR = `CorrectionsReportTests`, ACS = `ReportsAccessTests`, RUN = `ReportRunnerTests`,
TPL = `ReportTemplatesTests`, TAC = `ReportTemplatesAccessTests`, UPL = `TemplateUploadChecksTests`,
FIL = `TemplateFillerTests`, GEN = `TemplateGenerateTests`, VAL = `TemplateValuesTests`, DEM = `DemoReportTemplatesTests`,
TXT = `ReportTextsTests`, PERF = `ReportsPerformanceTests`, SET = `settings/internal/SettingsTests`,
ROLES = `members/internal/RolesTests`, DEBTS = `ledger/LedgerDebtsAsOfTests`. Web: RP = `pages/ReportsPage.test.tsx`,
RPG = `pages/ReportPage.test.tsx`, TP = `pages/ReportTemplatesPage.test.tsx`, GD = `components/reportTemplates/GenerateDialog.test.tsx`,
FC = `components/reportTemplates/FieldCatalog.test.tsx`, REQ = `components/RequisitesPanel.test.tsx`,
SP = `pages/SettingsPage.test.tsx`, ERR = `reportTemplateError.test.ts`, LAY = `components/Layout.test.tsx`.

Result: 61 criteria, 59 covered, 2 partial (reasons below), 0 GAP. The gaps found while mapping (before Slice 12) and what
closed them are listed at the end.

## 2.1 The Reports section

| Criterion | Tests |
|---|---|
| Chairman/treasurer see "Reports" in the menu, a card per report they may run, the templates below | LAY `shows Reports after Charges, only with REPORTS_VIEW`; RP `lists a card per report the API returns...`, `generating from a template`; ACS `theChairmanAndTheTreasurerListTheReports`; ACC `section21_theChairmanAndTheTreasurerAreOfferedEveryBuiltInReport` (NEW: all eight codes for both roles, only Debtors flagged as personal data, a member refused on all) |
| Member and electrician: no menu item; the address of a report shows no access | LAY (menu only with REPORTS_VIEW); RPG `renders the no-access page for a code the list does not contain`; ACS `aMemberAndTheElectricianAreRefused`, `withoutReportsViewNoReportRunsAndNothingIsAudited`; e2e `reports.spec.ts` `member@demo.local / electrician@demo.local has no Reports menu item and /reports shows no access` (also `/reports/electricity-tree`; `/reports/templates` NEW, not run) |
| Platform operator sees no reports and no cooperative data | ACS `thePlatformOperatorIsRefused`, `withoutReportsViewNoReportRunsAndNothingIsAudited` (operator on every report path); TAC `aMemberTheElectricianAndThePlatformOperatorAreRefusedEverywhere` (every template endpoint) |

## 2.2 Rules common to every report

| Criterion | Tests |
|---|---|
| More than 20 000 rows: "too large", no file | ACC `section22_aReportOfMoreThanTwentyThousandRowsIsRefusedAndOneOfTwentyThousandIsProduced` (NEW: 20 001 real plots give 422 `REPORT_TOO_LARGE` for preview and file and no audit entry, 20 000 are produced); RUN `aReportOverTheCapIsRefusedWithoutAnAuditEntry`; CBM `aReportOverTheRowCapIsRefusedAndNotAudited`; ELE, WAT, MIS, GEN, VAL cap tests; RPG `says the report is too large for REPORT_TOO_LARGE`, NEW `offers no file and no table for a report that is too large` (download disabled, no table), NEW `says the same when the file itself is refused as too large, and saves nothing`. The wording is the catalog's ("has more than 20 000 rows on a sheet. Narrow the filters and try again"), the intended meaning of the spec |
| English: column headers and header block in English, plot numbers and names unchanged | CBM `theWorkbookTextsFollowTheLanguageAndPlotDataStaysAsStored` (street `Лісова`, renamed charge type as stored, `Accept-Language`); DEB `theFileListsTheOwnersAnd...` (uk and en); TXT `bothLanguagesHaveTheSameKeys`, `theEnglishBundleHasNoUkrainianText`, `everyFilterOfEveryReportNamesItselfInBothLanguages` (all eight reports); RPG `downloads the file in the UI language under a name built from the period`; ACC `section22_noReportHasAPaidColumnOnScreenOrInTheFile` (NEW, reads all eight files in en and uk) |
| Over 200 rows on screen: the file has all rows and the same totals | ACC `section22_thePreviewShowsTwoHundredRowsAndTheFileAllOfThemWithTheSameTotals` (NEW: real report, 250 plots, preview 200 rows with `rowCount` 250, truncated, natural order, totals of all 250, file with 250 plot rows and the same total); RUN `thePreviewShowsTheFirst200RowsAndTheTotalsOfAll`, `theFileHoldsEveryRowAndTheSameTotals`; RPG `notes the row count only when the preview is truncated` |
| Chairman runs a report: audit entry with name, report, filters, row count, no content | CBM `thePreviewAndTheFileAreAuditedWithTheFiltersAndRowCountButNoContents` (amounts absent from the entry); DEB `theFileListsTheOwnersAndTheAuditEntryIsPersonalDataWithoutNamesOrContacts`; PBR `theListOffersTheReportWithItsFiltersAndTheRunIsAuditedWithoutPersonalData`; WAT, MIS, ELE audit checks; RUN `aFailureWhileWritingTheFileLeavesNoAuditEntry` |
| 300 plots with 12 months of charges: within 5 seconds | PERF `everyReportPreviewAndFileRunsUnderFiveSeconds` (all eight reports, preview and file, fixture asserted to be 300 plots and over 14 000 charges), `thePlotBalancesAndDebtorsPreviewsRunAFixedNumberOfStatementsWhateverThePlotCount` |

Rules from the section's text that are not acceptance criteria are tested too: header block, frozen header, numbers as
numbers and the file name (CBM `theWorkbookHasTheHeaderBlock...`, RUN, `XlsxReportWriterTests`), natural order of plots
(PBR, ACC), unknown values empty with the reason (ELE `anUnknownValueIsNeverShownAsZero...`, RPG `shows an unknown value as an
empty marker with its reason, never as 0`), no "paid" column (ACC `section22_noReportHasAPaidColumnOnScreenOrInTheFile`, NEW).

## 2.3 Charges by month

| Criterion | Tests |
|---|---|
| One month: effective total equals the billing months screen to the kopeck | CBM `everyMonthOfTheBillingMonthsScreenHasTheSameTotalsCentForCent` (every month and every charge type of `GET /billing-months`) |
| Reversal shown in the reversals column, effective total smaller | CBM `theMainSheetSplitsEveryMonthAndTypeAndTotalsTheMonthsAndTheRange` (February: -30,00, effective 30,00) |
| March adjustment of a January charge: "By service period" under January, main sheet under March | CBM `theServiceSheetPutsAnAdjustmentUnderTheMonthOfTheChargeItCorrects`, `theMainSheetSplits...` |
| Street filter: only plots on that street counted and summed | CBM `theStreetFilterCountsAndSumsOnlyThePlotsOfThatStreet` (and a street with no plots: zero totals) |

## 2.4 Plot balances

| Criterion | Tests |
|---|---|
| Today: each plot's debt and status equal its account and the plot map | PBR `todayEveryActivePlotShowsItsDebts...AndTheStatusOfTheMap` (status against `GET /plots`); DEBTS `asOfTodayTheDebtsAreThoseOfThePlotAccount`, `asOfTodayTheTotalAndTheOldestUnpaidDateAreThoseOfThePlotStatusCache`; ACC `section24_thePlotBalancesDebtsAreThoseOnThePlotAccountsWithReversalsAndAdjustments` (NEW: report total and per-type debt against `GET /plots/{id}/account` with a reversal, an adjustment and an opening balance) |
| Past date: later charges not counted, header says today's thresholds | PBR `aPastDayLeavesOutLaterChargesAndWorksOutTheStatusWithTodaysThresholds` (note `PAST_DATE_STATUS`), `aDayAfterTodayIsAFilterError`; DEBTS `chargesDatedAfterTheDayAreLeftOut...`; RPG `shows the plot status as its text, ... and the new notes` |
| Status "red": only red plots | PBR `theStatusFilterKeepsOnlyThoseColoursAndTheTotalsSumTheRowsListed` |
| No owner names | PBR `theOwnersAreCountedAndNeverNamedInThePreviewOrTheFile` (preview and file; only the count) |

## 2.5 Debtors

| Criterion | Tests |
|---|---|
| Default filters: only plots over the red amount or older than the red days, with names and contacts | DEB `withTheDefaultsOnlyRedPlotsAreListedWithEveryCurrentOwnerAndTheirContacts`, `theDefaultsFollowTheCooperativesThresholds` |
| "View reports" without "view members": no Debtors card, address shows no access | DEB `withoutMembersViewTheReportIsNotListedAndRefused`, `theDefinitionItselfRefusesACallerWithoutMembersView`; ACC `section214_aCustomRoleWithReportsViewButWithoutMembersViewSeesEveryReportExceptDebtors` (NEW, preview and file 403, list without Debtors); RP `shows no Debtors card when the API leaves it out...`; RPG `renders the no-access page for /reports/debtors...`; e2e `reports-acceptance.spec.ts` `REPORTS_VIEW without MEMBERS_VIEW: no Debtors card, /reports/debtors shows no access` |
| Two owners: both names and contacts in the row | DEB `withTheDefaultsOnly...` (plot 1: two current owners, three phones, the former owner absent); RPG `keeps line breaks of a multi-owner text cell` |
| Lower amount: more plots | DEB `aLowerAmountListsMorePlotsAndTheAgeAloneIsEnough` (200,00 gives plots 1, 2, 3; "or" with the age; a debt exactly at the amount is not above it) |

## 2.6 Electricity by meter tree

| Criterion | Tests |
|---|---|
| Fully known tree: start, end, consumption, children's sum, losses = consumption minus children | ELE `theConsumptionSheetShowsEveryNodeWithItsValuesConsumptionChildrenLossesAndPlotsFedIndentedByLevel` (a 1 100 kWh quarter meter with 1 030 kWh of children and 70 kWh, 6,36 %); `TreeFiguresTests`, `MeterReportsTests` |
| Unknown value: derived cells empty with the reason, nothing shows 0 | ELE `anUnknownValueIsNeverShownAsZeroOnScreenOrInTheFile`; RPG `shows an unknown value as an empty marker...`; `TemplateGenerate` `warningsComeInTheHeaders...AndAnUnknownTreeValueStaysEmpty` |
| "Charged" sheet equals the electricity charges on the plot accounts | ELE `theAmountsOfTheChargedSheetAreTheElectricityChargesOnThePlotAccounts` (against `GET /plots/{id}/account`, 424,44 less the charge outside the range), `theFileHoldsBothSheetsWithTheSameNumbersAsTheScreen` |
| Common-consumer run: split, consumption, amount, plots, plot-days | ELE `theChargedSheetListsEachSourceWithItsCorrectionsTheRunAndTheTotals`, `proportionalLossesShowEveryBookedRunPerNode...`; `ledger/LedgerElectricityRunsTests`, `LedgerElectricityChargedTests` |
| Adjusted charge: separate row with the original service period | ELE `theChargedSheetListsEachSourceWithItsCorrections...` (adjustment A1 booked in November for the October interval) |

## 2.7 Water

| Criterion | Tests |
|---|---|
| Part of the range connected: only connected days and their cubic metres | WAT `everyConnectedPlotIsListedPerSourceWithItsDaysCubicMetresAndNetChargesByKind` (plot 2: well from 16.10, 16 days, 15 m3; plot 6: supply then well, 20,667 and 41,333 m3); `MeterReportsWaterAndMissingTests` |
| Not connected on any day: not in the report | WAT `aPlotNotConnectedOnAnyDayOfTheRangeIsNotListedEvenWhenCharged` |
| Source "well": only plots connected to a well | WAT `theWellFilterListsOnlyThePlotsConnectedToAWellWithoutTheChargesOfTheirOtherSource` |

## 2.8 Missing readings

| Criterion | Tests |
|---|---|
| Plot meter with an approved reading in the range: not listed | MIS `thePlotMetersWithoutAnApprovedReadingInTheRangeAreListedByDefault`, `readMetersUnconnectedWaterMetersAndMetersOutsideTheRangeAreNotListed` |
| Only a reading waiting for approval: listed and marked | MIS `thePlotMetersWithout...` (`... 2025-09-10 1000.000 false 51 true`); RPG `shows the missing readings codes as texts...` |
| Water meter of a plot not connected: not listed | MIS `readMetersUnconnectedWaterMetersAndMetersOutsideTheRangeAreNotListed` (M-W3) |
| Link to the "not charged" list opens the list | MIS `theHeaderCountsTheOpenNotChargedIntervalItemsOfTheListedMeters`; RPG `shows the missing readings codes ... and links the open "not charged" note to the queue` (link to `/charges/not-charged`, the route of feature 011). **partial**: the link target and the count are asserted; no test clicks the link and opens the list in a browser |

## 2.9 Land tax

| Criterion | Tests |
|---|---|
| 35,00 per sotka, 600 m2: 6 sotkas, 210,00 | TAX `everyPlotTheTariffAppliesToShowsItsAreaRateDaysExpectedAndChargedAndADifferenceIsMarked`; `ExpectedAmountsTests` |
| Charged differs: row marked, recalculation suggested (none booked) | TAX same test (`mismatch`, note `RECALCULATION_SUGGESTED`, no `recalculation` row), `whenEveryPlotIsChargedWhatIsExpectedNothingIsMarked...` |
| Two rates in the year: one row per rate with its days | TAX `twoRatesInTheYearGiveOneRowPerRateWithItsDaysAndTheChargedAmountOnce` (196 and 169 days) |
| Total equals the sum of the rows | TAX (total line 634,20 / 616,70 over the listed rows), `theFileHoldsTheSameRowsAndTotalsAsNumbers...` |

## 2.10 Corrections

| Criterion | Tests |
|---|---|
| Recalculation adjustment listed with original charge, cause, reason, who booked it | COR `everyCorrectionIsListedWithTheOriginalChargeSourceCauseReasonAndWhoBookedIt` |
| Only "reversal": adjustments not listed | COR `theKindFilterListsOnlyThatKind` |
| Month without corrections: says nothing to show | COR `aMonthWithoutCorrectionsGivesASheetWithoutRowsAndWithoutTotalsAndAFileWithTheHeadersOnly`; RPG `renders header notes from their codes and says so when a sheet has no rows` |

## 2.11 Report templates

| Criterion | Tests |
|---|---|
| Valid upload is version 1, a new file version 2, version 1 still downloadable | TPL `aValidUploadIsVersionOneAndANewFileIsVersionTwoWhileVersionOneStaysDownloadable` (byte for byte); TP `uploads a new template...`, `uploads a new version with the file only`, `expands the versions and downloads one under its own file name`; ACC `section211_aTemplateIsGeneratedFromItsLatestVersionByAViewerAndArchivedByTheChairman` (NEW: generating uses version 2, version 1 downloaded unchanged) |
| Repeating row for plots: one row per plot with the plot's values | FIL `theRepeatingRowIsCopiedOncePerRowAndTheTotalsBelowSumEveryCopy`; GEN `theLandTaxStatementIsFilledWithTheLandTaxReportsNumbersAndAuditedOnce`; VAL `thePlotsAreThePlotBalancesAtTheEndOfThePeriodWithTheirLandTax`; ACC `section216_...` (NEW, real demo plots) |
| Cell with only `${totals.landTax.expected}`: a number, template formula still works | FIL `aPlaceholderAloneInACellBecomesATypedValueKeepingTheCellsStyle`, `referencesAroundTheRepeatingRowFollowItEverywhere`; GEN (cell E7 equals the report total, formula `SUM(D5:D6)` kept) |
| `${nonsense.field}` stays visible and is named in the warnings | FIL `anUnknownPlaceholderIsLeftAsWrittenAndListedOnce...`, `theWarningsSheetListsEveryWarning...`; GEN `warningsComeInTheHeadersAndTheWarningsSheet...`; GD `still downloads with warnings...`; e2e `report-templates.spec.ts` (unknown placeholder warns, Warnings sheet) |
| Macros, over 2 MB, not a workbook, bomb: "not valid" / "too large", nothing stored | UPL (`aVbaProjectIsMacros`, `aFileOverTwoMegabytesIsTooLargeBeforeAnythingElse`, `aTextFileNamedXlsxIsRefusedByItsSignature`, `aCompressedBombIsUnpackedTooLarge`, `tooManyPartsAreUnpackedTooLarge`, `everyReasonOfTheGuardHasItsCode`); TPL `everyRefusedFileGetsItsCodeAndNothingIsStored` (nine refusals plus size, zero templates and versions, no audit), `aRefusedNewVersionStoresNothing`; web TP `refuses a file that is not .xlsx or over 2 MiB without calling the API`, `maps a macros refusal to its text`; ERR (NEW: every reason of the server, `NOT_XLSX`, `MACROS`, `ACTIVE_CONTENT`, `BROKEN_ZIP`, `UNPACKED_TOO_LARGE`, `TWO_ROW_SETS`, `ARRAY_ACROSS_ROW`, has its own text in uk and en; 413 and `TEMPLATE_TOO_LARGE` say "too large"; no server text shown); `reportTemplateFile.test.ts` |
| Archive: cannot be generated, shows as archived, versions downloadable | TPL `anArchivedTemplateKeepsItsVersionsAndTakesNoNewOne`; GEN `thePeriodIsCheckedAndArchivedUnknown...` (409 `TEMPLATE_ARCHIVED`); TP `archives only after the confirm step`, `gives an archived template no upload or archive button`; GD `is not there for an archived template`; ACC `section211_...` (NEW: list shows `ARCHIVED`, generation 409, both versions downloaded) |
| Generating is audited with template name, version and period | GEN `theLandTaxStatementIsFilledWith...AndAuditedOnce` (`template=`, `version=1`, filters, rows); ACC `section211_...` (NEW: two generations audited as version 1 and version 2 with the period; the refused one writes none); TPL `uploadsAndTheArchiveAreAuditedOnceEachWithNameAndVersionOnly` |
| View-reports-only user generates but cannot upload, archive, download versions | TAC `theTreasurerReachesTheListTheCatalogAndGeneratingAndManagesNothing`; TPL `theListPutsTheTaxSlotFirst...` (treasurer archive 403); RP `offers Generate on active templates, and no upload, versions or archive, without REPORT_TEMPLATES_MANAGE`; ACC `section211_...` (NEW: upload, new version, archive, version list and version file all 403 for the treasurer, who generates) |
| Neither permission: templates page and addresses show no access | TAC `aMemberTheElectricianAndThePlatformOperatorAreRefusedEverywhere` (all eight operations); ACC `section211_...` (NEW: list, catalog, generate 403 for a member); TP `is not reachable without REPORT_TEMPLATES_MANAGE`; route gate `reports/templates` inside `REPORTS_VIEW` in `App.tsx`; e2e `reports.spec.ts` `/reports/templates` for the member and the electrician (NEW, **not run**) |

## 2.12 The tax report slot

| Criterion | Tests |
|---|---|
| Nothing uploaded: slot shows "template not added yet" | TPL `theTaxSlotIsNotAddedUntilATemplateIsUploadedIntoItAndOneActiveTemplateHoldsIt`, `theListPutsTheTaxSlotFirst...`; TAC `theTreasurerReaches...` (`NOT_ADDED`); TP `shows the empty tax slot and uploads into it with the slot name and TAX`; e2e `report-templates.spec.ts` (empty slot) |
| Upload into the slot: status becomes the template's version, the slot can generate | TPL (slot `ACTIVE`, version 1, second template refused with `TEMPLATE_SLOT_TAKEN`, freed by archiving); TP `shows the active tax template with its version instead of the empty slot`; e2e `report-templates.spec.ts` (upload into the slot, generate, read the file back); ACC `section212_theTaxSlotIsEmptyUntilATemplateIsUploadedIntoItAndThenGenerates` (NEW: the treasurer generates from the slot, a new version of the slot template is the one generated) |

## 2.13 Cooperative requisites

| Criterion | Tests |
|---|---|
| Valid IBAN saved, audit shows the changed fields | SET `theChairmanSavesValidRequisitesAndTheAuditShowsTheChangedFields`; `IbanTests`; REQ `trims and upper-cases the IBAN...` |
| Invalid IBAN: "The IBAN is not valid", nothing saved | SET `anInvalidIbanIsRejectedAndNothingIsSaved` (checksum, length, other country, characters); REQ `shows the IBAN field error and does not report a save`; ACC `section213_...` (NEW, 400 `IBAN_INVALID` and the saved IBAN stays) |
| ЄДРПОУ shown, not editable | SET `theEdrpouIsShownButCannotBeChangedThroughTheSettings`; REQ `shows the four fields and a read-only EDRPOU code`; SP NEW `shows the requisites block with SETTINGS_MANAGE: four fields and a read-only EDRPOU code` |
| Without the permission to manage settings the requisites cannot be changed | SET `withoutSettingsManageTheRequisitesCannotBeChanged`, `onlySettingsManageChangesTheLedgerSettings`; SP NEW `offers the requisites to nobody without SETTINGS_MANAGE`; ACC `section213_...` (NEW, treasurer 403) |
| Template with the IBAN placeholder: the file contains the saved IBAN | VAL `theCooperativeThePeriodAndTheFileAreSingleValues`, `aRequisiteThatIsNotEnteredIsNotKnown...` (values layer); ACC `section213_theSavedIbanIsInTheGeneratedFileAndARefusedChangeLeavesItAlone` (NEW, end to end: before saving the cell is empty with a warning, after `PUT /settings` with `ua21 3223 ...` the file holds the normalised IBAN in a whole-cell and in a text placeholder) |

## 2.14 Permissions

| Criterion | Tests |
|---|---|
| Treasurer sees all reports including Debtors and no template management | ACC `section21_...` (NEW, all eight for the treasurer); DEB `withoutMembersViewTheReportIsNotListedAndRefused` (treasurer lists and runs Debtors); TAC `theTreasurerReaches...`; RP, `members/internal/SeedBaseRolesTests`, `BaseRolesMatrixTests`, ROLES `listsBaseRolesFirstThenCustomRoles...` (treasurer holds `REPORTS_VIEW`, not the manage permission); e2e `reports-acceptance.spec.ts` `the Treasurer sees the Debtors report and its preview` |
| Roles screen: "View reports" and "Manage report templates" with descriptions, chairman has both | ROLES `holderOfRolesManageListsTheTwentyTwoPermissionsInOrder`, `listsBaseRolesFirstThenCustomRoles...` (chairman 22 permissions); ACC `section214_theTwoPermissionsHaveNamesAndDescriptionsAndTheBaseRolesHoldThemAsStated` (NEW: both permissions with their names and a non-empty description; chairman has both, treasurer only the first, member neither) |
| Custom role with "View reports" but not "View members": every report except Debtors | DEB `withoutMembersViewTheReportIsNotListedAndRefused`; ACC `section214_aCustomRoleWithReportsViewButWithoutMembersViewSeesEveryReportExceptDebtors` (NEW: exactly the seven others listed and run, with members view also Debtors, members view alone no reports); e2e `reports-acceptance.spec.ts` |

## 2.15 Links from other screens

| Criterion | Tests |
|---|---|
| Report link on a billing month opens Charges by month for that month | `pages/ChargesMonthPage.test.tsx` `links to the charges report of the month with REPORTS_VIEW` (`/reports/charges-by-month?fromMonth=2026-09&toMonth=2026-09`), `has no report link without REPORTS_VIEW`; RPG `runs the preview with the filters of the URL...` |
| Report link on the plot opens Plot balances filtered to the street | `pages/PlotPage.test.tsx` `PlotPage report link` (`opens plot balances filtered to the encoded street`, `omits the street parameter...`, `has no link without REPORTS_VIEW`); RPG `sends asOf, statuses and minDebt as the query parameters` |

## 2.16 Sample template and demo data

| Criterion | Tests |
|---|---|
| Demo chairman generates "Land tax statement" for the current year: every plot, area, rate, yearly amount | DEM `theDemoCooperativeGetsTheLandTaxStatementWithAConsistentVersion`, `theTaxSlotStaysEmptyAndTheChairmanCanGenerate`, `runningTheScriptAgainKeepsWhatTheChairmanChanged`; ACC `section216_theDemoLandTaxStatementIsFilledWithEveryPlotItsAreaRateAndYearlyAmount` (NEW: the real demo script, generate for the current year, one row per active plot, area, rate and expected amount equal the Land tax report row by row, no placeholder left, warnings only for the requisites the test database does not hold). **partial**: backend only; no browser test generates the demo template (the e2e generate flow uses a template uploaded into the tax slot) |

## Gaps found in the mapping and how they were closed

| Weak spot before Slice 12 | Closed by |
|---|---|
| 2.2 #3: preview 200 rows vs file was tested only on a fake report (RUN) | ACC `section22_thePreviewShows...` on the real Plot balances report |
| 2.2 #1: the default cap 20 000 was never asserted by value | ACC `section22_aReportOfMoreThanTwentyThousandRowsIsRefused...`; RPG too-large tests (no file offered) |
| 2.11 #1/#2: "generating always uses the latest version" had no test | ACC `section211_...` and `section212_...` |
| 2.11 #5: web mapped only `MACROS`; the other six reasons and the size refusals had no test of their text | ERR (new file) |
| 2.11 #8/#9: treasurer and member refusals proven only in the demo cooperative of TAC | ACC `section211_...` (throw-away cooperative, every management operation) |
| 2.12 #2: a slot used to generate had no backend test (only e2e) | ACC `section212_...` |
| 2.13 #4: web had no test that the requisites block is hidden without SETTINGS_MANAGE | SP (two new tests) |
| 2.13 #5: IBAN placeholder tested on the values layer only, not setting-to-file | ACC `section213_...` |
| 2.14 #2: only the first permission's description was asserted | ACC `section214_theTwoPermissions...` |
| 2.14 #3: the full list of seven reports for a custom role was not asserted | ACC `section214_aCustomRole...` |
| 2.16: the demo template was generated but its content never checked | ACC `section216_...` |
| 2.4 #1: report debts never compared with the plot account through the report | ACC `section24_...` |

## Not run in Slice 12

- The e2e addition in `web/e2e/reports.spec.ts` (`/reports/templates` for the member and the electrician): type-checked
  and formatted only; the e2e stack is run in Phase 4.
- Every existing e2e spec is mapped, not re-run.
