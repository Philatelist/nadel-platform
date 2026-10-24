# Acceptance coverage: 012 Retroactive recalculation

Every acceptance criterion of `functional-spec.md` mapped to the tests that check it. `NEW` marks a test written in
Slice 9 (backend `RecalculationAcceptanceTests` = `ACC`; web additions in `ScopeEditor.test.tsx`,
`WaterConnectionPanel.test.tsx`, `MeterTreePage.test.tsx`). Backend classes are in `backend/src/test/java/app/nadel/ledger`
(`...internal` for the planner and the demo data), web tests under `web/src`, e2e in `web/e2e/recalculation.spec.ts`.
Short names: PLAN = `internal/RecalculationPlannerTests`, MAN = `ManualRecalculationTests`, TAR = `TariffRecalculationTests`,
INT = `IntervalRecalculationTests`, READ = `ReadingRecalculationTests`, RUN = `RunRecalculationTests`,
ACCT = `LedgerAccountTests`, QRY = `RecalculationQueriesTests`, ISO = `RecalculationTenantIsolationTests`.

No criterion is a GAP. Two have no automated test of their own and are marked "partial" with the reason.

## 2.1 The rule: expected against booked

| Criterion | Tests |
|---|---|
| 60 -> 50: September -5,00 in October; October reversal -30,00 and new 25,00; total 50,00 | PLAN `wrongRateCorrected60To50...`; TAR `correctingTheRateFrom60To50...`; ACC `theRateCorrectionIsPreviewedBooked...` (NEW, end to end with the debt 50,00); e2e `the chairman corrects a used tariff version...` |
| New version 60,00 from 01.10 entered on 20.10: October reversal -25,00 and new 30,00, September unchanged | PLAN `backDatedVersionOpenMonthOnly`; TAR `aNewVersionFromTheFirstOfTheOpenMonth...`; MAN `aNewRateFromTheOpenMonth...` |
| New version from 16.09: September 27,50 (+2,50 in October), October reversal and new 30,00 | PLAN `backDatedVersionIntoAClosedMonth`; TAR `aNewVersionFromThe16thOfTheClosedMonth...` |
| Only October version deleted: October reversal -25,00, September unchanged | PLAN `versionDeletedTheTariffNoLongerAppliesInOctober`; TAR `deletingTheOnlyVersionOfTheOpenMonth...` |
| Reading 8 205 -> 8 215, both intervals open: 324,00 and 237,60 | PLAN `readingCorrectedBothIntervalsOpen`; READ `correcting8205To8215ReversesAndChargesBothOpenIntervals...` |
| Same, first interval closed: adjustment +43,20, second reversed and 237,60 | PLAN `readingCorrectedFirstIntervalInAClosedMonth`; READ `correctingAReadingOfAClosedMonthAdjustsItsInterval...` |
| Reading deleted, both open: merged 561,60, total unchanged | PLAN `readingDeletedIntervalsMerge`; READ `deletingAChargedReadingReversesBothIntervalsAndChargesTheMergedOne` |
| Reading deleted, first closed: -280,80, -280,80, new 561,60 in the earliest open month | PLAN `readingDeletedOneIntervalInAClosedMonth`; READ `deletingAReadingWhoseFirstIntervalIsInAClosedMonth...` |
| Interval in "Не нараховано" charged after a back-dated electricity version, item resolved | PLAN `backDatedTariffChargesAQueuedInterval`; INT `aQueuedIntervalIsChargedAfterABackDatedVersionAndItsItemResolved` |
| Fixed losses 5 %, 65 -> 75 kWh: consumption 324,00, losses -14,04 and 16,20 | PLAN `fixedPercentageLossesFollowTheInterval`; INT `fixedLossesAreReversedAndChargedAgainWithTheConsumption`; READ `fixedLossesFollowTheCorrectedInterval` |
| Proportional losses run (open): reversed as a whole and booked again | PLAN `proportionalLossesRunOpenMonth...`; RUN `aLossesRunOfAnOpenMonthIsReversedAsAWholeAndBookedAgain...`; READ `correctingAReadingALossesRunOfAnOpenMonthUsedReversesTheRunAndBooksItAgain` |
| Same run closed: an adjustment per plot | PLAN `proportionalLossesRunClosedMonth...`; RUN `aLossesRunOfAClosedMonthGetsAnAdjustmentPerPlot...`; READ `correctingAReadingALossesRunOfAClosedMonthUsedAdjustsEachPlot` |
| Area 600 -> 700: September +4,17, October reversal -25,00 and new 29,17 | PLAN `manualAreaCorrected600To700`; MAN `theAreaCorrectionAdjustsTheClosedMonth...` |
| Already adjusted -5,00, rate now 40,00: effective 25,00 against 20,00, adjustment -5,00 | PLAN `alreadyAdjustedChargeCorrectedAgain`; QRY `theEffectiveAmountAddsTheAdjustmentsSoFar` |
| Preview of the same recalculation again: "Змін немає", nothing bookable | PLAN `runTwiceBooksNothingTheSecondTime`; MAN `theSameRecalculationAgainFindsNoChangesAndBooksNothing`; e2e manual page "No changes" on a second run; web `RecalculationPreview` "says there are no changes and offers no booking" |
| Direct change or delete of a booked charge is refused (adjustments included) | `LedgerAppendOnlyTests`; ISO `theApplicationCannotChangeOrRemoveRows`, `theOwnerNeedsTheMaintenanceSettingToChangeRows`; ACC `afterARecalculationNoChargeAdjustmentRecordOrLineCanBeChangedOrDeleted` (NEW, real adjustment, reversal, new charge, record and line, app role and owner) |

## 2.2 Preview and booking

| Criterion | Tests |
|---|---|
| Preview lines grouped by plot and month with type, booked, expected, difference, action, cause; totals per type, overall, number of plots | MAN `theAreaCorrection...` (one type); ACC `aPreviewListsTheLinesByPlotAndMonthWithTheTotalsPerChargeTypeAndTheNumberOfPlots` (NEW, 2 types, 2 plots, 8 lines, totals per type, plot count, ordering); web `RecalculationPreview` "groups the lines by plot, then month..." and "shows the booked amount with the adjustments so far...", `RecalculationDetailPage` "groups lines by plot then month" |
| Booking without a reason: "Вкажіть причину" | MAN `aBookingNeedsAReasonOfAtMost500Characters`; TAR `aStalePreviewAndAMissingReason...`; READ `aStalePreviewAMissingReason...`; web `RecalculationPreview` "shows 'Enter a reason' instead of booking when the reason is empty" |
| Reason over 500 characters: refused, nothing booked | MAN `aBookingNeedsAReasonOfAtMost500Characters` (manual only); ACC `aReasonOfMoreThan500CharactersBooksNothingAndChangesNothingForEveryCause` (NEW, manual and all six cause types: charges, record, version and readings unchanged; 500 accepted); web `RecalculationPreview` "requires a reason of at most 500 characters" |
| Data changed after the preview: "Нарахування змінилися..." and nothing booked | MAN `aChangeBetweenThePreviewAndTheBookingRefusesTheBooking...`; TAR `aStalePreviewAnd...`; READ `aStalePreviewAnd...`; web `RecalculationStep` "previews again at once when the preview is outdated and says so". Note: a reading appended after the preview does not change the plan (its interval is charged by the 011 listener at the same amount), so the digest correctly stays valid; staleness is tested with the data changes that do alter the plan (area, rate) |
| Every reversal and adjustment shows the reason | MAN `theAreaCorrection...`; TAR `correctingTheRateFrom60To50...`; READ `correcting8205To8215...`; ACC story (NEW: every REVERSAL and ADJUSTMENT row of the recalculation carries the reason; the database also requires it, `ISO anAdjustmentHasTheRecalculationSourceAReasonAndARecalculation`) |

## 2.3 Adjustments on the account

| Criterion | Tests |
|---|---|
| Account shows "Коригування за вересень: -5,00 - причина" in October, links to original and recalculation, membership debt 50,00 | ACCT `anAdjustmentCountsInTheDebtAndLinksToTheChargeItAdjusts`; ACC story (NEW, real booking); web `PlotAccountPanel` adjustments: "shows the month of the original, the amount and the reason...", "links to the recalculation and opens the original charge"; e2e `the adjustment of the demo recalculation is on the account of plot 2` |
| Adjustment detail: service period, booked month, reason, recalculation; "Сторнувати" not offered | ACCT `anAdjustmentCountsInTheDebtAnd...`, `anAdjustmentIsNeverReversedByHand`; ACC story (NEW: detail fields and 409 `ADJUSTMENT_NOT_REVERSIBLE`); web `PlotAccountPanel` "offers no reversal for an adjustment...", "detail lists the adjustments of a charge, and an adjustment never claims a closed month" |
| "Лише коригування" lists adjustments only | ACCT `theKindFilterLimitsTheListButNotTheDebts`; ACC story (NEW); web `PlotAccountPanel` "keeps the 'adjustments only' filter in the URL..." and "starts filtered when the URL says so" |
| Member sees the adjustment and the lowered debt in "Мої нарахування", no recalculation link | ACCT `aMemberSeesTheAdjustmentsOfTheirOwnPlotWithoutTheRecalculation`; ACC story (NEW, real booking: debt 50,00, no `recalculationId`, no access to `/recalculations`); web `PlotAccountPanel` "shows no recalculation link without its id". Partial: `MyChargesPanel` has no test of its own; it shares `ChargeList` with the plot account |
| September closed: its charges and totals unchanged, adjustment in the earliest open month | MAN `theAreaCorrectionAdjustsTheClosedMonth...` (month total and count unchanged); TAR `correctingTheRateFrom60To50...`; ACC story (NEW: the closed charge still 30,00) |

## 2.4 Correcting a tariff version

| Criterion | Tests |
|---|---|
| Edit 60 -> 50 of a used version: preview, "Провести перерахунок", after booking the version shows 50,00 and the accounts the adjustment and new charge | TAR `correctingTheRateFrom60To50...`; web `TariffPage.recalculation` "opens step 2 for a used version on 409 and books the update..."; e2e `the chairman corrects a used tariff version...` (@smoke) |
| Delete a used version: version gone, charges reversed or adjusted | TAR `deletingTheOnlyVersionOfTheOpenMonth...`; web "opens step 2 for the deletion of a used version and books it" |
| Version dated before charges made: preview before saving | TAR `aNewVersionFrom...` tests; web "shows the preview first for a version that starts before next month and books it with the reason" |
| Note-only change: saved, no recalculation | TAR `aNoteOnlyCorrectionOfAUsedVersion...`, `aCorrectionThatChangesNoAmount...`; web "keeps a note-only edit a plain save" |
| Without the recalculation permission: 011 message, nothing changes | TAR `onlyAHolderOfBothPermissionsMayCorrectAUsedVersion`; ACC `onlyTheChairmanMayPreviewOrBookAnyKindOfCauseAndNothingChanges` (NEW, plain endpoints refused too); web "gives a user without RECALCULATION_RUN the 409 message and no step 2" |

## 2.5 Correcting a reading

| Criterion | Tests |
|---|---|
| Correct a charged reading: preview of the intervals, after booking the new value and new charges | READ `correcting8205To8215...`; web `ReadingRecalculation` "opens step 2 for a used reading and books the correction..."; e2e `the chairman corrects a charged reading on the meter card...` |
| Delete a charged reading: reading gone, two charges become one | READ `deletingAChargedReadingReversesBothIntervals...`; web "deletes a used reading through step 2" |
| Reading dated inside a charged interval: preview splits it before saving | READ `aReadingEnteredInsideAChargedIntervalSplitsItAndThePreviewSavesNothing`; web "opens step 2 with READING_ENTER for a back-dated reading of the round" |
| Electrician: "Показ уже нараховано - зверніться до голови", reading unchanged | READ `theElectricianGets409OnTheReadingAnd403...`; web "shows the electrician the hint in the round", "shows the hint when approving a used pending reading..."; e2e (electrician refused with the hint) |

## 2.6 Manual recalculation

| Criterion | Tests |
|---|---|
| Area corrected, manual Sep-Oct, membership, plot 7: September adjustment, October reversal and new charge | MAN `theAreaCorrectionAdjustsTheClosedMonth...`; e2e `the manual page previews a changed plot area, books it...`; web `RecalculationNewPage` "previews the chosen months, then books..." |
| Nothing differs: "Змін немає", "Провести перерахунок" not offered | MAN `theSameRecalculationAgain...`; web `RecalculationPreview` "says there are no changes and offers no booking"; e2e |
| "To" month after the current month: refused, no preview | MAN `aFutureMonthAMonthBeforeTheStart...`; web `RecalculationNewPage` "rejects a first month after the last and a future month without calling the server" |
| Hint "Минулі нарахування не змінено..." on the screens of area, tariff plots/exclusions, water connection, meter tree | web: `PlotForm` (existing), `RecalcHint`; NEW `ScopeEditor` (after saving the scope, with and without the permission), NEW `WaterConnectionPanel` (with, without, archived plot), NEW `MeterTreePage` move dialog (with and without) |

## 2.7 Recalculation records

| Criterion | Tests |
|---|---|
| List newest first with cause, author, time, reason, total | QRY `theListShowsTheRecalculationsNewestFirst`, `theListIsPaged`; ACC `aPreviewLists...` (list item total, plot and line counts, NEW); web `RecalculationsPage` "lists recalculations with cause, author, reason, total and a link to the detail" |
| Record shows scope and lines with original charge, expected, difference, action, links to created charges | QRY `aRecordShowsItsScopeLinesAndTotals`; MAN `theAreaCorrection...` (record and lines); web `RecalculationDetailPage` "shows the scope, the reason as user content and the totals", "groups lines by plot then month..." |

## 2.8 Permissions, visibility and audit

| Criterion | Tests |
|---|---|
| Treasurer opens "Перерахунки": records, no "Переглянути", "Провести перерахунок" or form | QRY `theTreasurerAndChargesViewReadTheRecords`; web `RecalculationsPage` "offers a new recalculation only to holders of RECALCULATION_RUN"; the form route is behind `RequirePermission RECALCULATION_RUN` (`RequirePermission.test`). Partial: the wiring of that route in `App.tsx` has no router-level test |
| Treasurer or member preview or book through any channel: refused | QRY `onlyRecalculationRunMayPreviewOrBook`; MAN `onlyTheChairmanMayPreviewAndBook...`; TAR/READ permission tests; ACC `onlyTheChairmanMayPreviewOrBookAnyKindOfCauseAndNothingChanges` (NEW: treasurer, member, operator, anonymous, for all seven cause types, preview and booking, and the plain tariff and reading endpoints; nothing changes) |
| Member asks for the recalculations: refused; other cooperative never sees this one's recalculations or adjustments | QRY `aMemberAndThePlatformOperatorCannotReadTheRecords`, `anotherCooperativeNeverSeesTheRecords`; ISO (RLS, links never cross); MAN `onlyTheChairmanMayPreviewAndBook...`; ACC story (NEW: other chairman and other member get 404 on the plot account, a 4xx on the record, the other chairman gets 404 on it and a list without it, the operator is refused) |
| Audit: "Перерахунок проведено" with cause, scope, reason, totals; for a cause-driven one the change with old and new values | MAN `theAreaCorrection...` (RECALCULATION_BOOKED fields); TAR `correctingTheRateFrom60To50...` (TARIFF_VERSION_UPDATED old/new, RECALCULATION_BOOKED), `deleting...`; READ `correcting8205To8215...` (READING_CORRECTED old/new), deletion and entry audit; web `auditValue.recalculation` |

## 2.9 Demo data and guides

| Criterion | Tests |
|---|---|
| Demo cooperative: one recalculation listed, the touched plot shows the adjustment | `internal/DemoLedgerDataTests.theDemoRecalculationIsBookedLikeARealOne`, `theScreensShowTheFeature`; e2e `the adjustment of the demo recalculation is on the account of plot 2` |
| Board guide in uk and en: chapter marked working, with the screens | No automated test (documentation). Checked by reading `docs/guide/{uk,en}/board-guide.html`: the table of contents entry "Виправлення і перерахунок" / "Corrections and recalculation" carries the tag `done` ("працює" / "works") and the chapter has screenshots. Partial |
