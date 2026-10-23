# Acceptance coverage: 011 Ledger, charges and period closing

Matrix of every acceptance criterion of `functional-spec.md` (75 criteria in sections 2.1 to 2.13) against the tests
that prove it. Written in Slice 10 after reading the criteria in full and the tests of Slices 1 to 9.

Legend: `B` = backend (Testcontainers integration unless noted `unit`), `W` = web unit test (Vitest), `E` = Playwright
e2e (`web/e2e`). Backend tests are in `backend/src/test/java/app/nadel/`; class names are given without the package
when they are in `ledger/` (`ledger/internal/` for the unit tests marked `unit`). **NEW** marks a test added in
Slice 10. `LedgerAcceptanceTests` is the whole-feature story (two journeys over the HTTP API in a throw-away
cooperative); `ledger-acceptance.spec.ts` is the two browser flows that had no e2e.

## Summary

| Measure                                                           | Count |
| ----------------------------------------------------------------- | ----- |
| Acceptance criteria                                               | 75    |
| Covered by an existing slice test before Slice 10                 | 74    |
| Covered only at calculator level (no API/browser) before Slice 10 | 1     |
| Gaps closed in Slice 10 (new tests)                               | 1     |
| Criteria with an additional whole-feature test (cross-slice)      | 19    |
| Still uncovered                                                   | 0     |

Besides the criteria, the slice-review gaps were closed (listed at the end).

## 2.1 Charges and the plot account

| #   | Criterion                                                    | Tests                                                                                                                                                                                               |
| --- | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Plot 7: 25,00 + 17,50, debts per type, total 42,50           | B `LedgerAccountTests.theAccountShowsTheChargesAndTheDebtPerChargeType`; B `unit` `DebtCalculatorTests`; W `PlotAccountPanel.test`; **NEW** B `LedgerAcceptanceTests.theChairmanRunsAMonth...` (via the month run) |
| 2   | Reversal of 25,00 with a reason lowers the debt              | B `LedgerAccountTests.aReversalCancelsTheChargeAndLowersTheDebt`; E `charges.spec` "enters an opening balance, reverses a charge..."; **NEW** B `LedgerAcceptanceTests.theChairmanRunsAMonth...`    |
| 3   | Reversing twice: "already reversed"                          | B `LedgerAccountTests.aChargeIsReversedAtMostOnce`; B `LedgerTenantIsolationTests.aChargeIsReversedAtMostOnce` (database); E `charges.spec` (same spec); **NEW** B `LedgerAcceptanceTests`          |
| 4   | Reversing a reversal: "cannot be reversed"                   | B `LedgerAccountTests.aReversalCannotBeReversed`; **NEW** B `LedgerAcceptanceTests`                                                                                                                 |
| 5   | Reversing without a reason: "Enter a reason"                 | B `LedgerAccountTests.aReversalNeedsAReason`; W `ReverseChargeDialog.test`; **NEW** B `LedgerAcceptanceTests`                                                                                       |
| 6   | Closed-month charge: no "Сторнувати", adjustment text        | B `LedgerAccountTests.aChargeInAClosedMonthIsNotReversedByHand`; B `BillingMonthsTests.aClosedMonthGetsNothing...`; W `ChargeDetailDialog.test`; E `charges.spec` "cannot be reversed by hand"; **NEW** B `LedgerAcceptanceTests` |
| 7   | Direct UPDATE/DELETE of a charge is refused                  | B `LedgerAppendOnlyTests` (8 tests: app role, owner, trigger, maintenance setting); B `LedgerTenantIsolationTests.aValueUseBelongsToExactlyOne...`, `theApplicationRoleCannotTruncateAnyOfTheTables`   |
| 8   | Electricity charge shows kWh, rate, readings, days           | B `ReadingChargesTests.anEnteredReadingChargesTheIntervalFromThePreviousPoint` (calculation JSON); W `ChargeDetailDialog.test`; E `charges.spec` "approves an owner reading and sees the electricity charge with its calculation" |

## 2.2 Billing months and closing

| #   | Criterion                                                | Tests                                                                                                                                                                        |
| --- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | "Нарахування" lists the months with status and totals    | B `BillingMonthsTests.theListRunsFromTheFirstMonthToTheCurrentOneNewestFirstWithTotalsPerChargeType`; W `ChargesPage.test`; E `charges.spec` "sees the months..."              |
| 2   | Closing October while September is open: refused         | B `BillingMonthsTests.monthsCloseOnlyInOrder`; B `unit` `BillingCalendarTests`; **NEW** B `LedgerAcceptanceTests.theChairmanRunsAMonth...` (409 `PERIOD_CLOSE_ORDER`)          |
| 3   | Closing September after the check list; nothing added    | B `BillingMonthsTests.aClosedMonthGetsNothingAndALateChargeGoesToTheEarliestOpenMonth...`, `aClosedMonthNeverChangesAndNoMonthIsDeleted`; W `CloseMonthDialog.test`; E `zz-closing.spec` |
| 4   | Reading dated 28.09 approved 05.10: booked in October    | B `ReadingChargesTests.aReadingOfAClosedMonthIsBookedInTheNextOpenMonthWithItsOwnServicePeriod`; B `BillingMonthsTests.aLateChargeGoesTo...`; **NEW** B `LedgerAcceptanceTests` (late reading, own service days) |
| 5   | Future month cannot be charged                           | B `MonthRunTests.aFutureMonthIsRefused`; B `BillingMonthsTests.aFutureMonthOrOneBeforeTheFirstIsNotClosed`; **NEW** B `LedgerAcceptanceTests` (`PERIOD_IN_FUTURE`)           |
| 6   | Check list shows run-not-done, queue items, stale meters | B `BillingMonthsTests.theMonthPageShowsTheTotalsAndTheCheckList`; W `ChargesMonthPage.test`; **NEW** B `LedgerAcceptanceTests.whatCouldNotBeChargedIsListedBlocksNoClosing...` (count 1, then closed) |

## 2.3 The month run

| #   | Criterion                                        | Tests                                                                                                                                                             |
| --- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Preview 25,00; booking charges 01.10-31.10       | B `MonthRunTests.thePreviewShowsThePlotsAndTheTotalsPerChargeTypeAndWritesNothing`, `bookingChargesPlot7TwentyFiveForTheWholeMonthWithItsVersion`; W `RunPreviewDialog.test`; E `charges.spec` |
| 2   | Second run creates nothing                       | B `MonthRunTests.aSecondRunCreatesNothingNew`; E `charges.spec` "a rerun adds nothing"; **NEW** B `LedgerAcceptanceTests`                                         |
| 3   | New plot: rerun creates only its charges         | B `MonthRunTests.aPlotAddedAfterTheRunGetsOnlyItsOwnChargesOnTheRerun`; E `charges.spec`                                                                          |
| 4   | Plot 12 in two membership tariffs: queue, both named | B `MonthRunTests.twoMembershipTariffsOnPlot12ChargeNeitherAndQueueThePlotNamingBoth`; B `TariffConflictWarningTests`; B `NotChargedQueueTests.theListShowsThePlotTheChargeTypeTheReasonAndBothTariffs` |
| 5   | A tariff of 0,00 creates no charge               | B `MonthRunTests.aTariffAmountOfZeroCreatesNoCharge`                                                                                                              |

## 2.4 Electricity (and water) by readings

| #   | Criterion                                           | Tests                                                                                                                                                  |
| --- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | 112,4 to 205,7: 403,06 for 12 days                  | B `ReadingChargesTests.anEnteredReadingChargesTheIntervalFromThePreviousPoint`; B `unit` `ReadingChargeCalculatorTests.readingChargeIsTheIntervalConsumptionTimesTheRateForItsDays`; **NEW** B `LedgerAcceptanceTests` (403,06 end to end) |
| 2   | Rate change inside the interval: 1 398,00           | B `unit` `ReadingChargeCalculatorTests.tariffChangeInsideTheIntervalSplitsTheConsumptionByDays`                                                       |
| 3   | 7 kWh x 4,3250 + 1 kWh x 4,3350 = 34,61             | B `unit` `ReadingChargeCalculatorTests.theSumOfThePartsIsRoundedOnce`                                                                                  |
| 4   | Replacement: 280,80 and 285,12                      | B `ReadingChargesTests.aReplacementChargesTheLastIntervalOfTheOldMeterAndTheFirstOfTheNewOne`; B `unit` `ReadingChargeCalculatorTests.aReplacementGivesOneChargePerMeter` |
| 5   | Initial value 0: no charge                          | B `ReadingChargesTests.aRemovalChargesTheIntervalUpToTheFinalValueAndTheInitialValueChargesNothing`; B `unit` `ReadingChargeCalculatorTests.theInitialValueAloneIsNoInterval` |
| 6   | Pending member reading: no charge until approved    | B `ReadingChargesTests.aMembersReadingChargesNothingUntilItIsApproved`; E `charges.spec` "approves an owner reading"                                  |
| 7   | Tariff starts 01.10: "No tariff for every day"      | B `ReadingChargesTests.anIntervalWithoutATariffForEveryDayIsQueuedAndChargedByARetryOnceTheVersionExists`; **NEW** B `LedgerAcceptanceTests.whatCouldNotBeCharged...` |
| 8   | "Вода" inactive: no charge, no queue item           | B `ReadingChargesTests.waterReadingsChargeAndQueueNothingWhileWaterIsInactive`; B `unit` `ReadingChargePlanningTests.anInactiveChargeTypeChargesAndQueuesNothing` |
| 9   | 5 % losses: 280,80 and 14,04                        | B `ReadingChargesTests.fixedPercentageLossesAddALossesChargeOverTheSameParts`; B `unit` `ReadingChargeCalculatorTests.fixedPercentageLossesAreASecondAmountOverTheSameParts` |

## 2.5 Losses in the network

| #   | Criterion                                           | Tests                                                                                                                                                                     |
| --- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Quarter A: P1 60 kWh = 259,20, P2 40 kWh = 172,80   | B `LossesRunsTests.thePreviewShowsTheQuarterWithItsPlotsAndAmountsAndWritesNothing`; B `unit` `LossesCalculatorTests.quarterLossesAreSharedByThePlotsConsumption`; E `charges.spec` "runs losses for a past range" |
| 2   | Substation: B gives P3 50, substation 100 shared    | B `LossesRunsTests.theSubstationSharesItsLossesAmongEveryPlotUnderIt`; B `unit` `LossesCalculatorTests.substationLossesAreSharedByEveryPlotUnderIt`                         |
| 3   | Negative losses: nothing charged, warning "-50"     | B `unit` `LossesCalculatorTests.negativeLossesChargeNothing`; W `LossesPage.test` (warning text); API level was **a gap**, closed by **NEW** B `LossesRunsTests.aQuarterShowingLessThanItsChildrenChargesNothingAndWarnsWithTheNumbers` |
| 4   | Quarter B unread: B and substation skipped, A charged | B `LossesRunsTests.anUnknownNodeIsSkippedWithTheNodesAboveItQueuedAndChargedLaterByARetry`; E `charges.spec` (quarter with an unknown value is a warning)                |
| 5   | Overlap with a booked run refused                   | B `LossesRunsTests.aNodeChargedForSomeOfTheDaysIsShownAndItsBookingRefused`; B `LossesRunTenantIsolationTests.twoBookedRunsNeverChargeANodeForTheSameDay` (database)      |
| 6   | Reversing a run reverses every charge               | B `LossesRunsTests.aReversalReversesEveryChargeWithTheRunsReasonAndFreesTheDays`; W `ReverseRunDialog.test`; E `charges.spec` (the run is reversed)                        |
| 7   | Value between readings marked "розрахунковий"       | B `LossesRunsTests.aValueBetweenReadingsIsMarkedCalculated`; B `unit` `LossesCalculatorTests.aValueBetweenReadingsIsCalculatedAndMarked`                                   |

## 2.6 Common consumers

| #   | Criterion                                               | Tests                                                                                                                                                         |
| --- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | New cooperative has "Спільні споживачі"                 | B `ChargeTypesTests.aBrandNewCooperativeGetsTheTypesAndThePermissionsFromSeedBaseRoles`; B `LedgerTenantIsolationTests.everyCooperativeHasTheCommonConsumersTypeAndTheNewPermissions`; B `LedgerMigrationTests` |
| 2   | Street lighting equally: 12,5 kWh = 54,00 each          | B `CommonConsumerRunsTests.streetLightingEquallyPerPlotPreviewsFourPlotsAt54AndWritesNothing`; B `unit` `CommonConsumerSplitCalculatorTests`, `CommonConsumerPlanningTests` |
| 3   | Well pump among connected plots: 20 kWh = 86,40         | B `unit` `CommonConsumerPlanningTests.theWellAmongConnectedPlotsCharges86ForEachConnectedPlotAndNothingForTheOther`; B `unit` `CommonConsumerSplitCalculatorTests.theWellPumpAmongConnectedPlotsLeavesAPlotThatIsNotConnectedOut` |
| 4   | P3 connected from 16.10: 24 / 24 / 12 kWh               | B `unit` `CommonConsumerPlanningTests.aPlotConnectedFromTheMiddlePaysForItsPlotDays`; B `unit` `CommonConsumerSplitCalculatorTests.aConnectionInsideTheRangeSharesPerPlotDay` |
| 5   | Service users: 10 kWh = 43,20 each                      | B `CommonConsumerRunsTests.serviceUsersPay43AndOtherPlotsNothingWhileANotChargedConsumerIsOnlyListed`; B `unit` `CommonConsumerPlanningTests.serviceUsersPay43AndOtherPlotsNothing` |
| 6   | "Not charged" consumer is listed, no charges            | B `CommonConsumerRunsTests.serviceUsersPay43...` (same test); B `unit` `CommonConsumerPlanningTests.aConsumerThatIsNotChargedIsListedWithItsConsumptionAndChargesNothing` |
| 7   | No tariff: "Немає тарифу", nothing bookable             | B `CommonConsumerRunsTests.withoutACommonConsumersTariffThePreviewSaysSoAndNothingCanBeBooked`; B `unit` `CommonConsumerPlanningTests.withoutACommonConsumersTariffThePlotsAreListedWithoutAmounts` |
| 8   | Overlap for the same consumer refused                   | B `CommonConsumerRunsTests.aConsumerChargedForSomeOfTheDaysIsShownAndItsBookingRefused`; B `CommonConsumerTenantIsolationTests.twoBookedRunsNeverChargeAConsumerForTheSameDay` |
| 9   | Reversing a run reverses every charge                   | B `CommonConsumerRunsTests.aReversalReversesEveryChargeWithTheRunsReasonAndFreesTheDays`; W `CommonConsumersPage.test`                                         |

## 2.7 "Не нараховано"

| #   | Criterion                                           | Tests                                                                                                                                                                     |
| --- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Add the tariff, "Повторити": charged, item gone     | B `NotChargedQueueTests.aRetryAfterTheFixChargesThePlotAndTheItemDisappears`; B `ReadingChargesTests.anIntervalWithoutATariff...`; **NEW** B `LedgerAcceptanceTests.whatCouldNotBeCharged...`; E `charges.spec` "retries it" |
| 2   | Cause not fixed: item stays with its reason         | B `NotChargedQueueTests.aRetryWithoutAFixKeepsTheItemWithItsReason`; **NEW** B `LedgerAcceptanceTests.whatCouldNotBeCharged...`                                           |
| 3   | Item shows plot, type, reason, link                 | B `NotChargedQueueTests.theListShowsThePlotTheChargeTypeTheReasonAndBothTariffs`; W `NotChargedPage.test`; E `charges.spec` "board sees what could not be charged"         |

## 2.8 Water connection

| #   | Criterion                                           | Tests                                                                                                                                                                      |
| --- | --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Connect plot 7 to the well from 01.05.2020          | B `WaterConnectionsTests.theChairmanConnectsAPlotToAWellAndTheCardShowsIt`; W `WaterConnectionPanel.test`; **NEW** E `ledger-acceptance.spec` §2.8 (connect on the card)   |
| 2   | Connecting again: "already connected"               | B `WaterConnectionsTests.aConnectedPlotIsNotConnectedAgain`; **NEW** E `ledger-acceptance.spec` §2.8 (409 `WATER_ALREADY_CONNECTED`)                                       |
| 3   | Disconnect on 15.10: history keeps the end date     | B `WaterConnectionsTests.aDisconnectionKeepsTheHistoryAndAConnectionMayNotShareADayWithIt`; **NEW** E `ledger-acceptance.spec` §2.8 (history with end date)                |
| 4   | Bulk: 5 plots from 01.10 on the source's page       | B `WaterConnectionsTests.theChairmanConnectsFiveSelectedPlotsToTheWellAndTheSourceListsThem`, `aBulkConnectionIsAllOrNothing`; W `WaterSourcesSection.test`                |
| 5   | Water fee 300/yr: P1 25,00, P4 nothing, not queued  | B `MonthRunTests.aWaterFeeChargesOnlyTheConnectedDaysAndNeverAPlotThatIsNotConnected`                                                                                      |
| 6   | P3 connected from 16.10: 12,90                      | B `MonthRunTests.aWaterFeeChargesOnlyTheConnectedDaysAndNeverAPlotThatIsNotConnected` (12.90); B `unit` `ReadingChargePlanningTests.aWaterIntervalIsChargedForItsConnectedDaysOnly` |
| 7   | Meter reading while disconnected: no charge, no item | B `ReadingChargesTests.aWaterReadingOfAPlotNotConnectedForTheWholeIntervalChargesAndQueuesNothing`; B `WaterConnectionsTests.aWaterMeterOfAPlotThatIsNotConnectedIsNotMissingReadings` |
| 8   | "Ділянки" sheet "Вода: так" / "Water: yes"           | B `ImportPlotsWaterTests.aUkrainianSheetConnectsByYesToTheSupplyAndByAWellsNameToTheWellAndNoOrEmptyLeavesAPlotAsItIs`, `anEnglishSheetWithWaterYesDoesTheSameAndYesKeepsAPlotConnectedToAWell` |

## 2.9 Opening balances

| #   | Criterion                                          | Tests                                                                                                                                                                  |
| --- | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 1 500,00 for plot 12 as of 01.09.2026 with a reason | B `LedgerAccountTests.anOpeningBalanceIsBookedInTheMonthOfItsDateAndRaisesTheDebt`; W `OpeningBalanceForm.test`; E `charges.spec` "enters an opening balance"; **NEW** B `LedgerAcceptanceTests` |
| 2   | 0 or negative: "must be greater than zero"         | B `LedgerAccountTests.anOpeningBalanceMustBeGreaterThanZeroAndNotInTheFuture`; B `unit` `OpeningBalanceRulesTests`; **NEW** B `LedgerAcceptanceTests` (0.00 refused)  |
| 3   | "Вхідне сальдо" sheet, three rows, both languages  | B `ImportOpeningBalancesTests.aUkrainianSheetWithThreeRowsIsPreviewedAndCommittedAsThreeOpeningBalances`, `anEnglishSheetWorksTheSameWayAndTheSameFileUploadedAgainBooksNothingTwice`; B `ImportAcceptanceTests` |
| 4   | Unknown plot or charge type: row error, none booked | B `ImportOpeningBalancesTests.everyRowProblemIsReportedOnItsRowAndColumnWithACodeAndNothingIsBooked`; B `OpeningBalanceImportServiceTests` (unit, standing balance)   |

## 2.10 Plot status on the map

| #   | Criterion                                                    | Tests                                                                                                                                                                   |
| --- | ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | A 25,00 charge turns an owned plot yellow                    | B `PlotStatusRefreshTests.aChargeTurnsAnOwnedPlotYellowAndItsReversalGreenAgain`; B `unit` `PlotStatusPolicyTests`; W `plotStatus.test`; **NEW** B `LedgerAcceptanceTests` |
| 2   | Opening balance 1 500,00 above 1 000,00: red                 | B `PlotStatusRefreshTests.anOpeningBalanceAboveTheRedAmountIsRedAndItsReversalLeavesTheSmallerDebtYellow`; **NEW** B `LedgerAcceptanceTests`                            |
| 3   | Reversed below the threshold: yellow or green                | B `PlotStatusRefreshTests` (same test and `aChargeTurnsAnOwnedPlot...`); **NEW** B `LedgerAcceptanceTests`                                                              |
| 4   | Red amount to 2 000,00: saved and audited                    | B `SettingsTests.theChairmanChangesTheRedThresholdAndTheOtherSettingsKeepTheirValues`, `aChangeOfTheRedThresholdIsAuditedWithEveryChangedField`; B `PlotStatusRefreshTests.aChangeOfTheThresholdAppliesAtOnce`; W `SettingsPage.test`; **NEW** E `ledger-acceptance.spec` §2.10 (the form saves and keeps it) |

## 2.11 Tariff versions and readings in use

| #   | Criterion                                           | Tests                                                                                                                                                                 |
| --- | --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Used tariff version: cannot be corrected            | B `TariffVersionUsageTests.aVersionTheRunUsedIsNoLongerCorrectedOrDeleted`, `afterTheReversalOfItsChargesTheVersionIsCorrectedAgain`; B `TariffVersionCorrectionTests`   |
| 2   | Charged reading: cannot be corrected or deleted     | B `ReadingUsageTests.aChargedReadingCannotBeCorrectedOrDeleted`; B `LossesRunsTests.theReadingsABookedRunUsesCannotBeCorrectedUntilItIsReversed`; B `CommonConsumerRunsTests` (same rule) |
| 3   | Charge reversed: reading correction accepted        | B `ReadingUsageTests.afterTheReversalOfItsChargeTheReadingIsCorrectedAndTheNewIntervalCharged`                                                                       |

## 2.12 Permissions, visibility and the screens

| #   | Criterion                                                | Tests                                                                                                                                                                                         |
| --- | -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Treasurer sees months and accounts, no action buttons    | B `BillingMonthsTests.theMonthsNeedChargesViewAndClosingNeedsPeriodsClose`; B `LedgerAccountTests.theTreasurerSeesEveryAccountButCannotReverse`; E `charges.spec` "the treasurer sees the charges but no action"; W `ChargesPage.test` |
| 2   | Treasurer cannot run a month by any channel              | B `MonthRunTests.onlyChargesRunMayPreviewOrBook`, `theQueueIsForChargesViewOnly`; B `NotChargedQueueTests.onlyChargesRunMayRetry`; B `LossesRunsTests`/`CommonConsumerRunsTests.theTreasurerSees...`; **NEW** B `LedgerAcceptanceTests` (403 on run, close, reverse, retry) |
| 3   | "Мої нарахування": own plots only                        | B `LedgerAccountTests.aMemberSeesTheAccountOfTheirOwnPlotOnly`; E `charges.spec` "a member sees only the own plot account"; W `MyExpectedChargesPanel.test`; **NEW** B `LedgerAcceptanceTests`      |
| 4   | Another plot's account: "Ділянку не знайдено"            | B `LedgerAccountTests.aMemberSeesTheAccountOfTheirOwnPlotOnly` (404 `PLOT_NOT_FOUND`); **NEW** B `LedgerAcceptanceTests`                                                                      |
| 5   | Another cooperative sees nothing of this one             | B `LedgerTenantIsolationTests`, `LossesRunTenantIsolationTests`, `CommonConsumerTenantIsolationTests`, `NotChargedTenantIsolationTests`, `WaterConnectionTenantIsolationTests`; B `LedgerAccountTests.anotherCooperativeNeverSeesTheAccount`; B `BillingMonthsTests.anotherCooperativeNeverSeesTheMonths` |
| 6   | Audit log shows who, when and what                       | B `LedgerAuditTests`, `LedgerAuditListingTests`, `MonthRunTests.aBookingIsAuditedWithTheCountAndTheTotal`, `BillingMonthsTests.closingIsAudited`, `LossesRunsTests` (run booked/reversed), `CommonConsumerRunsTests`, `WaterConnectionsTests.connectingAndDisconnectingAreAudited`, `SettingsTests`; B `unit` `LedgerAuditActionsTests`; W `auditValue.*.test`; **NEW** B `LedgerAcceptanceTests` (run, close, reversal, opening balance with the actor) |

## 2.13 Demo data

| #   | Criterion                                            | Tests                                                                                                                                                            |
| --- | ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | September closed, October open with all totals       | B `DemoLedgerDataTests.theScreensShowTheFeature`, `everyMonthRunChargeIsWhatTheMonthRunPlans`, `runningTheBlockAgainChangesNothingAndClearRemovesIt`; E `charges.spec` |
| 2   | "Не нараховано": exactly one "no tariff" item        | B `DemoLedgerDataTests.theScreensShowTheFeature` (plot 25), `everyReadingChargeAndTheQueueItemAreWhatTheIntervalsPlan`                                           |
| 3   | Demo map shows red, yellow or green by debt          | B `DemoLedgerDataTests.thePlotDebtsAreWhatTheStatusRefresherRecords`, `theScreensShowTheFeature` (both now **date-independent**: each plot is compared with the rule for today) |

## Slice-review gaps closed in Slice 10

| Gap                                                                                                  | Test                                                                                                                          |
| ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Losses retry that narrows an item: original RESOLVED and `NOT_CHARGED_RETRIED` audited               | B `LossesRunsTests.aRetryOfAnItemOtherRunsChargedSomeDaysOfNarrowsItToTheDaysLeft` (extended)                                 |
| `MeterCatalog.chainOf`                                                                               | B `MeterCatalogTests.theChainOfAReplacedMeterIsTheSameOldestFirstFromAnyMeterOfItAndEmptyForAnUnknownOne` (NEW)               |
| `UnchargedDays` (gaps at start, middle, end; ranges crossing the days; full coverage)                | B `unit` `UnchargedDaysTests` (NEW, 9 tests)                                                                                  |
| Opening balances import: closed-month row goes to the earliest open month; per-month cache in `apply` | B `ImportOpeningBalancesTests.aRowDatedInAClosedMonthIsBookedInTheEarliestOpenMonthKeepingItsOwnDate`; B `unit` `OpeningBalanceImportServiceTests.aRowDatedInAClosedMonthIsBookedInTheMonthTheCalendarGivesAndEachMonthIsAskedOnce` (NEW) |
| Equal `created_at` falls back to the id tie-breaker                                                  | B `unit` `OpeningBalanceImportServiceTests.aTieOnTheTimeOfBookingFallsBackToTheSmallerIdWhateverTheOrder` (NEW)               |
| Demo status test depended on today's date                                                            | `DemoLedgerDataTests` now compares stored and map statuses with the rule applied to today, and keeps plot 12 red and grey plots |
| e2e title promised "Борг понад поріг" only                                                           | `plots-acceptance.spec.ts` title names both labels                                                                            |
| e2e `pastRange()` counted back from today                                                            | `support/api.ts` `pastRange(page)` anchors on the latest losses run's `to` (demo anchor - 5)                                  |
| e2e `demoMonths` took the first OPEN/CLOSED                                                          | `support/api.ts` takes the latest of each                                                                                     |
| e2e `ownership.spec` filter count                                                                    | the filtered row count is compared with the number of rows carrying the label                                                 |

## Not covered, and why

Nothing in the 75 criteria is uncovered. Deliberate limits:

- Bulk water connection, common consumer settings, the losses mode and the opening-balance form are proven in the
  browser only by web unit tests (Vitest with mocked API) and in the backend by API tests; a Playwright walk of each
  would repeat them without a new rule. The two flows that had neither a browser spec nor a unit-level user story
  (water connection on the card, the red threshold form) got `ledger-acceptance.spec.ts`.
- The audit entry of a changed red threshold (§2.10 #4) is proven by the backend (`SettingsTests`), not through the
  audit screen in a browser.
