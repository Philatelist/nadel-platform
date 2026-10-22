# Functional Specification: Meters and readings

- **Topic:** The cooperative's register of electricity and water meters, the electricity meter tree, meter
  replacement, readings taken on any date (entered by the board or the electrician, or submitted by a member for
  approval), and the consumption the app calculates between readings and over any date range.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/010-meters-and-readings.md` (roadmap phase 2, feature 2 of 5)

---

## 1. Overview and Rationale (The "Why")

Today the electrician walks the streets with a notebook, the chairman keeps a spreadsheet of meter numbers, and
nobody can say quickly how much a plot used between two visits or how much the whole quarter used last month. Rounds
are irregular: sometimes a month apart, sometimes three. When a meter is replaced or its counter wraps around, the
numbers in the notebook no longer add up.

This feature gives the cooperative one register of its meters — plot meters, the substation, quarter meters and
common consumers such as the well pump and street lighting — arranged in a tree the chairman configures. The
electrician enters readings on a simple phone-friendly screen; a member can submit the reading of their own meter
and the board approves it. A reading is a value on a date. The app calculates the consumption between readings and,
for the board, over any date range, filling gaps by proportion of days and marking such values as calculated.
Nothing is charged yet — electricity charges, losses and the split of common consumers come with feature 011.

**Success looks like:** the electrician enters 30 readings on his phone in one round in under 15 minutes, with the
date filled in for him; a member submits his reading from "My account" and sees it approved the next day; the
chairman picks "з 01.10 по 31.10" on the meter tree and sees each quarter's consumption.

---

## 2. Functional Requirements (The "What")

### 2.1 Meters

- A meter measures electricity (in kWh) or water (in m³). Water meters can be registered now; nothing is charged for
  water while the "Вода" charge type is inactive.
- Kinds of meters: a **plot meter** (belongs to one plot), a **substation meter**, a **quarter meter** (feeds a group of
  plots) and a **common consumer** (a well pump, street lighting, the office).
- A meter has its kind, resource, serial number, installation date and initial reading, an optional note, and, once
  removed, its removal date and final reading. Non-plot meters also have a name ("Свердловина №1", "Освітлення вул.
  Вишнева").
- Readings have up to 3 digits after the decimal point (electricity is usually whole kWh; water needs litres).
- A plot has at most one active meter of each resource. Common land never has a plot meter.
- A meter with readings is never deleted: it is removed with a date and a final reading and keeps its history. A meter
  entered by mistake and without readings can be deleted.
- A plot that has a meter cannot be deleted or turned into common land, and a plot with an active meter cannot be
  archived, until the meter is removed.
  - **Acceptance Criteria:**
    - [x] When the chairman registers a plot meter for plot 7 with serial "ЕМ-104523", installation date 01.06.2026
      and initial reading 8 000, then the register lists it under plot 7 as active. _Evidence: `MetersAcceptanceTests` `section21_aPlotMeterIsRegisteredAndListedUnderItsPlotAsActive`; e2e `meters-acceptance.spec.ts` (§2.1 #1); `MetersTests`; `docs/screenshots/010-meters-and-readings-meters-register.png`._
    - [x] When the chairman registers a second active electricity meter for plot 7, then the save is refused with
      "На ділянці вже є активний лічильник цього ресурсу" / "The plot already has an active meter of this resource". _Evidence: `MetersAcceptanceTests` `section21_aSecondActiveMeterOfTheResourceIsRefused`; e2e `meters-acceptance.spec.ts` (§2.1 #2, message text); `MetersTests`._
    - [x] When the chairman tries to register a plot meter on common land, then the save is refused with "Спільна
      земля не має лічильників ділянок" / "Common land has no plot meters". _Evidence: `MetersAcceptanceTests` `section21_commonLandHasNoPlotMeters`; `MetersTests` (the UI plot picker never offers common land)._
    - [x] When the chairman registers a common consumer "Свердловина №1", then it appears in the register with its
      name. _Evidence: `MetersAcceptanceTests` `section21_aCommonConsumerAppearsInTheRegisterWithItsName`; e2e `meters-acceptance.spec.ts` (§2.1 #4); `docs/screenshots/010-meters-and-readings-meters-register.png` ("Свердловина №1")._
    - [x] When the chairman enters a reading with 4 digits after the decimal point, then the save is refused with
      "Показ — невід'ємне число з не більше ніж 3 знаками після коми" / "A reading must be a non-negative number with
      at most 3 decimal places". _Evidence: `MetersAcceptanceTests` `section21_aReadingWithFourDecimalsIsRefusedAndThreeAreKeptExactly`; e2e `meters-acceptance.spec.ts` (§2.1 #5, client message); `ReadingValidatorTests`, `meter.test.ts`._
    - [x] Given a meter without readings, when the chairman deletes it and confirms, then it disappears from the
      register; given a meter with readings, then there is no delete action and a delete request is refused with
      "Лічильник має покази — зніміть його замість видалення" / "The meter has readings — remove it instead". _Evidence: `MetersAcceptanceTests` `section21_aMeterWithReadingsIsRemovedInsteadOfDeleted`; e2e `meters-acceptance.spec.ts` (§2.1 #6: confirm dialog, no delete action, API 409); `MetersTests`._
    - [x] When the chairman removes a meter on 15.10.2026 with final reading 8 420, then it is shown as removed from
      that date, keeps its readings, and the plot can get a new meter. _Evidence: `MetersAcceptanceTests` `section21_aRemovedMeterKeepsItsReadingsAndThePlotCanGetANewMeter`; e2e `meters-acceptance.spec.ts` (§2.1 #7); `docs/screenshots/010-meters-and-readings-plot-9-card.png` (ЕЛ-9 "Знято", "Працював 25.05.2026 – 22.09.2026")._
    - [x] Given plot 7 has an active meter, when the chairman tries to archive plot 7 or turn it into common land,
      then it is refused with "Спершу зніміть лічильник ділянки" / "Remove the plot's meter first". _Evidence: `MetersAcceptanceTests` `section21_aPlotWithAnActiveMeterIsNotArchivedOrTurnedIntoCommonLand`; `PlotChangeGuardTests` (delete, archive, PLOT→COMMON, import path); e2e `meters-acceptance.spec.ts` (§2.1 #8, refusal text)._

### 2.2 The meter tree (electricity)

- The chairman builds the tree: substations at the root; under a substation, quarter meters, common consumers and
  plot meters; under a quarter meter, plot meters and common consumers. A quarter meter cannot be under another
  quarter meter.
- Moving a meter to another parent (for example a plot moved to another quarter) takes an effective date; the history
  is kept, so a past date range is shown with the tree as it was then.
- A meter installed as a replacement takes the place of the meter it replaces in the tree.
- A cooperative without a tree is fine: plot meters simply have no parent.
- Water meters have no tree.
  - **Acceptance Criteria:**
    - [x] When the chairman adds quarter meter "Квартал А" under the substation and moves plot 7's meter under it,
      then the tree shows plot 7 under "Квартал А". _Evidence: `MetersAcceptanceTests` `section22_theChairmanBuildsTheTreeAndAQuarterIsNotUnderAQuarter`; e2e `meters-acceptance.spec.ts` (§2.2, move via the dialog); `MeterTreeTests`; `docs/screenshots/010-meters-and-readings-meter-tree.png`, `…-meter-tree-move-dialog.png` (current parent preselected)._
    - [x] When the chairman tries to put a quarter meter under another quarter meter, then it is refused with
      "Квартальний лічильник не може бути під іншим квартальним" / "A quarter meter cannot be under another quarter
      meter". _Evidence: `MetersAcceptanceTests` `section22_theChairmanBuildsTheTreeAndAQuarterIsNotUnderAQuarter`; `TreeRulesTests`, `MeterTreeTests`; e2e `meters-acceptance.spec.ts` (§2.2 #2, message text)._
    - [x] Given plot 7 moved from "Квартал А" to "Квартал Б" on 15.10.2026, when the chairman shows the range 01.10–31.10
      on the tree, then plot 7's consumption from 01.10 to 15.10 is counted under "Квартал А" and from 15.10 to 31.10
      under "Квартал Б". _Evidence: `MetersAcceptanceTests` `section22_aMovedPlotIsCountedUnderEachQuarterForItsPartOfTheRangeAndThePastKeepsTheOldTree`; `MeterTreeTests` (slices add up to the range total, incl. across a parent removal); e2e `meters-acceptance.spec.ts` (§2.2 #3)._
    - [x] When the chairman shows the tree for a past range before the move, then plot 7 is under "Квартал А". _Evidence: `MetersAcceptanceTests` `section22_aMovedPlotIsCountedUnderEachQuarterForItsPartOfTheRangeAndThePastKeepsTheOldTree`; e2e `meters-acceptance.spec.ts` (§2.2 #4); `MeterTreeTests`._

### 2.3 Replacement

- Replacing a meter is one action: the date, the final reading of the old meter, and the serial number and initial
  reading of the new one.
- A counter that wraps around (99 999 → 00 012) is entered as a replacement of the meter by itself: the same serial,
  final reading 99 999, initial reading 0.
  - **Acceptance Criteria:**
    - [x] When the chairman replaces plot 7's meter on 20.09 with final reading 8 205 and a new meter with initial
      reading 3, then the old meter is removed on 20.09, the new one is active from 20.09, and the plot card shows both
      in its history. _Evidence: `MetersAcceptanceTests` `section23_aReplacementRemovesTheOldMeterAndInstallsTheNewOneInOneAction`; `MeterReplacementTests`; e2e `meters-acceptance.spec.ts` (§2.3 #1); `docs/screenshots/010-meters-and-readings-plot-9-card.png` (ЕЛ-9 removed, ЕЛ-9/2 "Замінив попередній лічильник"), `…-meter-card-replace-dialog.png`._
    - [x] When the chairman enters a wrap-around for plot 9's meter (same serial, final 99 999, initial 0), then the
      meter keeps counting from 0 and the consumption across the wrap is calculated correctly. _Evidence: `MetersAcceptanceTests` `section23_aWrapAroundIsAReplacementOfTheMeterByItself`, `section25_wrapAroundAddsUpBothParts` (61 kWh); `MeterReplacementTests`; e2e `meters-acceptance.spec.ts` (§2.3 #2, "Заповнити як прокрутку"); `docs/screenshots/010-meters-and-readings-meter-card-replace-dialog.png`._

### 2.4 Readings

- A reading is a value on the date it was taken. The date field is filled with today and shows a quiet hint next to
  it, not a dialog: "Дата показу — сьогодні, 21.10.2026. Змініть, якщо знімали в інший день" / "Reading date is
  today, 21 Oct 2026. Change it if the meter was read on another day". On the round screen one date applies to the
  whole round and can be changed for a single row.
- The date cannot be in the future, before the meter's installation or after its removal.
- One reading per meter and date: entering a reading for a date that already has one replaces it; the old one stays in
  the history marked "замінено" / "superseded".
- Readings entered by the board, the electrician or the import count at once. A reading a member submits for their own
  meter waits for approval ("на підтвердженні" / "pending") and is not used for consumption until someone allowed to
  approve readings approves it, or rejects it with a reason the member sees.
- Ordering check against the counted readings of the same meter: a reading lower than the previous one or higher than
  the next one is refused. A reading dated between two others is fine when it fits between them.
- Unusual consumption: when the daily consumption since the previous reading is more than 3 times the meter's usual
  daily consumption over the last 180 days, the reading is saved with a warning "Перевірте показ: споживання значно
  більше звичайного" / "Check the reading: consumption is much higher than usual".
- A reading can be corrected or deleted until a charge uses it (charges arrive with feature 011; until then every
  reading can be corrected). After that a correction goes through recalculation (feature 012).
  - **Acceptance Criteria:**
    - [x] Given today is 21.10.2026, when the electrician opens the round screen, then the round date is 21.10.2026 with
      the hint "Дата показу — сьогодні, 21.10.2026. Змініть, якщо знімали в інший день", and a reading saved without
      touching the date is dated 21.10.2026. _Evidence: `MetersAcceptanceTests` `section24_aReadingWithoutADateIsDatedTodayAndOneRowMayDifferInDate`; e2e `meters-acceptance.spec.ts` (§2.4 #1, exact hint, saved as today); `ReadingsRoundPage.test.tsx`; `docs/screenshots/010-meters-and-readings-round.png` ("Дата показу — сьогодні, 22.10.2026. Змініть, якщо знімали в інший день" — the stack date on the day of verification)._
    - [x] When the electrician changes the date of one row to 18.10.2026 and saves, then only that reading is dated
      18.10.2026. _Evidence: `MetersAcceptanceTests` `section24_aReadingWithoutADateIsDatedTodayAndOneRowMayDifferInDate`; e2e `meters-acceptance.spec.ts` (§2.4 #2); `ReadingsRoundPage.test.tsx`._
    - [x] When anyone enters a reading dated in the future, then it is refused with "Дата показу не може бути в
      майбутньому" / "The reading date cannot be in the future". _Evidence: `MetersAcceptanceTests` `section24_theDateIsNotInTheFutureAndInsideTheWorkingPeriod`; `ReadingsTests`, `ReadingValidatorTests`._
    - [x] When anyone enters a reading dated before the meter's installation or after its removal, then it is refused
      with "Дата поза періодом роботи лічильника" / "The date is outside the meter's working period". _Evidence: `MetersAcceptanceTests` `section24_theDateIsNotInTheFutureAndInsideTheWorkingPeriod`; `ReadingsTests`, `ReadingValidatorTests`._
    - [x] Given 03.09: 8 140, when the electrician enters 8 100 on 05.10, then it is refused with "Показ менший за
      попередній або більший за наступний" / "The reading is lower than the previous one or higher than the next one". _Evidence: `MetersAcceptanceTests` `section24_aLowerReadingIsRefusedAndAReadingBetweenTwoOthersSplitsTheInterval`; `ReadingsTests`._
    - [x] Given 03.09: 8 140 and 05.10: 8 205, when the electrician enters 8 170 for 20.09, then it is accepted and the
      history shows intervals of 30 and 35 kWh. _Evidence: `MetersAcceptanceTests` `section24_aLowerReadingIsRefusedAndAReadingBetweenTwoOthersSplitsTheInterval` (30 and 35 kWh); `ReadingsTests`._
    - [x] Given 05.10: 8 205, when the electrician enters 8 250 for 05.10, then 8 205 is shown as "замінено" and the
      interval uses 8 250. _Evidence: `MetersAcceptanceTests` `section24_aSecondReadingOfTheSameDateSupersedesTheFirst`; `ReadingsTests`._
    - [x] Given the usual consumption is 2 kWh a day, when the electrician enters a reading 10 days later with 400 kWh
      more, then it is saved and the warning "Перевірте показ: споживання значно більше звичайного" is shown. _Evidence: `MetersAcceptanceTests` `section24_anUnusualConsumptionIsSavedWithAWarning`; `ReadingsTests`, `ConsumptionCalculatorTests`._
    - [x] When a member submits 8 210 for 28.10 for their own plot, then it shows "на підтвердженні" and the
      consumption does not change until it is approved. _Evidence: `MetersAcceptanceTests` `section24_aMemberReadingWaitsForApprovalThenCountsOrIsRejectedWithAReason`; `MemberReadingsTests`; e2e `meters-acceptance.spec.ts` (§2.4 #9); `docs/screenshots/010-meters-and-readings-member-account.png` ("на підтвердженні")._
    - [x] When the chairman approves a member's reading on "На підтвердження", then it counts and a new interval
      appears; when he rejects one with a reason, then the member sees "відхилено" with that reason. _Evidence: `MetersAcceptanceTests` `section24_aMemberReadingWaitsForApprovalThenCountsOrIsRejectedWithAReason`; `MemberReadingsTests`; e2e `meters-acceptance.spec.ts` (§2.4 #10: the member sees "відхилено" and the reason); `docs/screenshots/010-meters-and-readings-approvals.png` ("Після підтвердження: 14,5 кВт·год за 3 дн.")._
    - [x] When the chairman corrects a reading's value or date, or deletes a reading, then the intervals around it are
      recalculated. _Evidence: `MetersAcceptanceTests` `section24_aCorrectionOrADeletionRecalculatesTheIntervalsAround`; `ReadingsTests`._

### 2.5 Consumption

- **Interval consumption** of a reading = the reading minus the previous counted reading (or the meter's initial
  reading), for the days between them. A member and the electrician see it as "з 03.11 по 28.01: 412 кВт·год".
- **Range consumption** for the board over "з … по …" = the reading at the end minus the reading at the start. When
  there is no reading on a date, the value is calculated in proportion to the days between the nearest readings before
  and after it and marked "розрахунковий" / "calculated"; when there is no reading after it, the consumption is unknown
  and shown as "немає показу після 05.10" / "no reading after 5 Oct".
- An interval or range across a replacement adds up the plot's meters.
- Worked examples (each one is an automated check):

  | Case | Input | Expected |
  |---|---|---|
  | Normal interval | 03.09: 8 140, 05.10: 8 205 | 03.09–05.10: 65 kWh |
  | Long interval | 03.09: 8 140, next 28.01: 8 552 | 03.09–28.01: 412 kWh |
  | Replacement | old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 with 3, 05.10: 69 | 03.09–05.10: 65 + 66 = 131 kWh |
  | Wrap-around | 03.09: 99 950, self-replacement 15.09 final 99 999 initial 0, 05.10: 12 | 03.09–05.10: 49 + 12 = 61 kWh |
  | Lower reading | 03.09: 8 140, 05.10 entered 8 100 | refused |
  | Reading between two others | 03.09: 8 140, 05.10: 8 205, then 20.09: 8 170 | accepted; 30 and 35 kWh |
  | Installed recently | installed 10.10 with 0, 31.10: 120 | 10.10–31.10: 120 kWh |
  | Range with calculation | 01.10: 1 000, 31.10: 1 300; range 01.10–16.10 | reading at 16.10 = 1 150 (calculated); 150 kWh |
  | Range without a later reading | last reading 05.10; range to 31.10 | unknown, "немає показу після 05.10" |
  | Member reading | 28.10: 8 210 submitted | ignored until approved; then interval 05.10–28.10 |
  | Same date twice | 05.10: 8 205, then 05.10: 8 250 | 8 205 superseded, interval uses 8 250 |
  | Default date | date not touched | saved with today's date |
  | Future date | 25.10 entered on 21.10 | refused |
  | Unusual consumption | usual 2 kWh/day; 10 days with 400 kWh | saved with the warning |
  | Plot moved to another quarter | moved A → B on 15.10; range 01.10–31.10 | 01.10–15.10 under A, 15.10–31.10 under B |
  | Plot without a meter | no active meter | not on the round, no consumption, not "без показу" |
  - **Acceptance Criteria:**
    - [x] Every row of the worked examples table passes as an automated check. _Evidence: `MetersAcceptanceTests` `section25_normalAndLongIntervals`, `section25_replacementAddsUpBothMeters`, `section25_wrapAroundAddsUpBothParts`, `section25_aRecentlyInstalledMeterCountsFromItsInitialReading`, `section25_aRangeIsCalculatedBetweenReadingsAndUnknownAfterTheLastOne`, `section25_aPlotWithoutAMeterIsNotOnTheRoundAndNeverWithoutAReading`, the `section24_*` tests and `section22_aMovedPlot…`; `ConsumptionCalculatorTests` (every row)._
    - [x] When the chairman shows the range 01.10–16.10 on the tree for a meter with 01.10: 1 000 and 31.10: 1 300,
      then the end value 1 150 is marked "розрахунковий" and the consumption is 150 kWh. _Evidence: `MetersAcceptanceTests` `section25_aRangeIsCalculatedBetweenReadingsAndUnknownAfterTheLastOne` (1 150 calculated, 150 kWh); `MeterTreeTests`; e2e `meters-acceptance.spec.ts` (§2.5, "розрахунковий"); `docs/screenshots/010-meters-and-readings-meter-tree-calculated.png`._
    - [x] When the chairman shows a range ending after a meter's last reading, then that meter shows "немає показу
      після <дата>" instead of a number. _Evidence: `MetersAcceptanceTests` `section25_aRangeIsCalculatedBetweenReadingsAndUnknownAfterTheLastOne`; `MeterTreeTests`; e2e `meters-acceptance.spec.ts` (§2.5); `docs/screenshots/010-meters-and-readings-meter-tree.png` ("немає показу після 17.10.2026")._

### 2.6 Who can see and do what

- New permissions: view meters; manage meters (register, replace, remove, tree); enter readings; approve readings.
- The chairman has all four, the treasurer can view meters. In the demo cooperative the custom role "Електрик" can view
  meters and enter readings.
- A member sees the meters and readings of the plots they currently own and can submit a reading for them; another
  plot's meters are "not found" for them.
- A user whose only extra permissions are viewing meters and entering readings (the electrician) sees a menu with only
  "Покази" and "Лічильники", and lands on "Покази".
  - **Acceptance Criteria:**
    - [x] When the demo electrician signs in, then the menu shows only "Покази" and "Лічильники" and he lands on
      "Покази". _Evidence: e2e `meters-acceptance.spec.ts` (§2.6 #1); e2e `meters.spec.ts` (electrician); `Layout.test.tsx`, `HomeRedirect.test.tsx`; `docs/screenshots/010-meters-and-readings-electrician-menu.png` (only "Покази" and "Лічильники")._
    - [x] When the treasurer opens "Лічильники", then he sees the register and meter cards without register, replace,
      remove or tree actions, and changes are refused with "У вас немає дозволу на цю дію.". _Evidence: `MetersAcceptanceTests` `section26_theTreasurerOnlyViews`; e2e `meters-acceptance.spec.ts` (§2.6 #2, "У вас немає дозволу на цю дію."); `docs/screenshots/010-meters-and-readings-treasurer-register.png` (no actions)._
    - [x] When a member opens "My account", then "Мої лічильники" lists only the meters of their own plots with their
      readings and intervals, and asking for another plot's meters gives "Ділянку не знайдено" / "Plot not found". _Evidence: `MetersAcceptanceTests` `section26_aMemberSeesOnlyTheMetersOfTheirOwnPlots` (404 `PLOT_NOT_FOUND`, "Ділянку не знайдено." / "Plot not found."); `MetersTests`, `MemberReadingsTests`; e2e `meters-acceptance.spec.ts` (§2.6 #3); `docs/screenshots/010-meters-and-readings-member-account.png`._
    - [x] When someone without the approve permission tries to approve a reading, then it is refused. _Evidence: `MemberReadingsTests` (approve without READINGS_APPROVE refused); `MetersAcceptanceTests` `section26_theElectricianEntersReadingsAndManagesNothing`; e2e `meters-acceptance.spec.ts` (§2.6 #4)._
    - [x] When a user of another cooperative asks for this cooperative's meter, then they get "not found". _Evidence: `MetersAcceptanceTests` `section26_anotherCooperativeAndThePlatformOperatorSeeNothing`; `MetersTenantIsolationTests`; e2e `meters-acceptance.spec.ts` (§2.6 #5)._

### 2.7 Screens

1. **Лічильники / Meters:** the register with filters (resource, kind, street, "без показу з <дата>"); the meter card
   with its readings and intervals, "Замінити лічильник" and "Зняти".
2. **Покази / Readings:** the electrician's screen. A round date (today by default, with the hint); plots in street and
   number order, then the other meters by name; each row shows the last reading and its date and a field for the new
   one (its date can be changed for that row); rows are saved one by one; interval consumption and warnings show at
   once; filter "без показу з <дата>" (60 days by default when switched on). Works at phone width.
3. **На підтвердження / To approve:** readings submitted by members, approve or reject with a reason.
4. **Налаштування → Дерево лічильників / Settings → Meter tree:** substations, quarter meters and common consumers, the
   plots each one feeds, moving a meter to another parent with an effective date, and a "з … по …" range showing range
   consumption per node with calculated values marked.
5. **Plot card → Лічильник:** the active meter, its history and replacements.
6. **My account → Мої лічильники:** own meters, readings with intervals, "Передати показ" (date filled with today, same
   hint).
- The tariffs of 009 that are charged by meter now say, per plot, whether the plot has an active meter of that
  resource instead of the placeholder.
- All screens show one language at a time, Ukrainian by default.
  - **Acceptance Criteria:**
    - [x] When the electrician opens "Покази" on a phone-width screen, then every row can be read and filled without
      scrolling sideways. _Evidence: e2e `meters-acceptance.spec.ts` (§2.7 #1, bounding boxes at 375 px); e2e `meters.spec.ts` (no horizontal overflow); `docs/screenshots/010-meters-and-readings-round-phone.png`._
    - [x] When the electrician switches on "без показу з" with the default, then only meters whose last reading is
      older than 60 days are listed, and a plot without an active meter is never listed. _Evidence: `MetersAcceptanceTests` `section27_theStaleFilterListsMetersNotReadForSixtyDays`; `RoundTests`; e2e `meters-acceptance.spec.ts` (§2.7 #2, the checkbox and its 60-day default); `docs/screenshots/010-meters-and-readings-round-stale.png` (only plot 20, last reading 08.08.2026)._
    - [x] When the chairman opens a plot card whose plot has a per-kWh tariff and an active electricity meter, then the
      tariff shows "за показаннями"; for a plot without a meter it shows "немає лічильника" / "no meter" and is not
      counted in the tariff's plots. _Evidence: `MetersAcceptanceTests` `section27_aMeterBasedTariffSaysPerPlotWhetherThereIsAMeter`; `PlotTariffsTests`, `TariffsTests`; e2e `meters-acceptance.spec.ts` (§2.7 #3); `docs/screenshots/010-meters-and-readings-plot-card.png` ("за показаннями", plot 7), `…-tariff-meter-presence.png` ("немає лічильника", plot 25)._

### 2.8 Excel import

- The import of 008 gets an optional sheet "Лічильники" / "Meters": plot number, resource, serial number,
  installation date and initial reading. Only plot meters; the tree is set up on its screen. The same preview, problems
  and both-language rules as 008; the template and its example row include the new sheet in both languages.
  - **Acceptance Criteria:**
    - [x] When the chairman imports a file with a "Лічильники" sheet (or "Meters"), then the preview counts the new
      meters and lists their problems, and after import the meters are in the register. _Evidence: `ImportPreviewMetersTests`, `ImportCommitTests` (uk and en sheets); e2e `meters-acceptance.spec.ts` (§2.8 #1)._
    - [x] When the same file is imported again, then the meters count as unchanged. _Evidence: `ImportCommitTests` (idempotent re-import); e2e `meters-acceptance.spec.ts` (§2.8 #2)._
    - [x] When a row names a plot that already has a different active meter of that resource, then the preview shows
      the problem "На ділянці вже є інший активний лічильник — замініть його в реєстрі" / "The plot already has another
      active meter — replace it in the register". _Evidence: `ImportPreviewMetersTests` (`METER_ACTIVE_EXISTS`); e2e `meters-acceptance.spec.ts` (§2.8 #3, the problem text)._

### 2.9 Audit

- Registering, changing, removing, replacing, deleting and re-parenting a meter, and entering, replacing, correcting,
  approving and rejecting a reading are written to the audit log with old and new values.
  - **Acceptance Criteria:**
    - [x] When the chairman replaces a meter and approves a reading, then the audit log shows both entries with the
      chairman's name and the old and new values, in the selected language. _Evidence: `MetersAcceptanceTests` `section29_theAuditLogShowsTheReplacementAndTheApprovalWithTheActor`; `MeterAuditTests`, `ReadingAuditTests`; e2e `meters-acceptance.spec.ts` (§2.9 #1, Ukrainian labels, author, old and new values); `MeterTreeTests.theAuditLogShowsAMoveWithItsParentAndDate` (verify fix 997d9cb: a tree move is listed); `docs/screenshots/010-meters-and-readings-audit.png`._

### 2.10 Demo data

- The demo cooperative gets one substation, two quarter meters, a well pump and street lighting; meters on most plots,
  two plots without a meter, one replacement, readings on irregular dates over the last four months (rounds a few days
  to two months apart, one plot not read for more than 60 days) and one pending member reading. All in Ukrainian.
  - **Acceptance Criteria:**
    - [x] When the chairman opens "Лічильники" in the demo cooperative, then he sees the substation, two quarter meters,
      the well pump, street lighting and plot meters, and "На підтвердження" has one reading. _Evidence: `MetersAcceptanceTests` `section210_theDemoCooperativeHasTheNetworkAndOnePendingReading`; `DemoMeterDataTests`; e2e `meters-acceptance.spec.ts` (§2.10 #1); `docs/screenshots/010-meters-and-readings-meters-register.png`, `…-approvals.png` (one reading)._

---

## 3. Scope and Boundaries

### In-Scope

- Meter register (both resources, four kinds), registration, change, removal, deletion without readings, replacement
  incl. wrap-around; plot guards.
- Electricity meter tree with dated moves and past ranges.
- Readings on any date with the default date and hint, round screen, member submission and approval, superseding,
  ordering check, unusual-consumption warning, correction and deletion until a charge uses them.
- Interval and range consumption with calculated values marked; replacement sums.
- Permissions, member visibility, menu for the electrician.
- Excel "Лічильники" sheet.
- Per-plot meter presence for meter-based tariffs.
- Audit, demo data, one language at a time.

### Out-of-Scope

- Electricity charges, losses per node, the common consumers split and its setting (feature 011).
- Recalculation after a correction of a used reading (feature 012).
- The board's electricity report (feature 013).
- A photo of a reading and the mobile electrician app (phase 5).
- Water charges.

---

## Assumptions & Decisions

Decided by the pipeline (no blocking question; the requirement document is detailed). Each is recorded in
`pipeline-log.md`.

- The meter's initial reading is the starting point of its first interval; a meter can be deleted while nothing but
  its initial reading exists.
- The round lists plot meters in street and number order, then the other meters by name.
- Usual consumption = the daily average over the counted intervals of the last 180 days before the new reading; with no
  earlier interval there is no warning.
- A member's pending reading never replaces a counted one; once approved, it replaces a counted reading of the same date.
- A plot with a meter cannot be deleted or turned into common land; a plot with an active meter cannot be archived.
- "Ділянки" is hidden for a user who neither may view plots nor owns plots but may enter readings or view meters; such a
  user lands on "Покази" (or "Лічильники" without the enter permission).
- The tree is a section of Налаштування open to whoever may manage meters; the settings page shows the sections the
  user may use.
- The import adds only plot meters; a plot with a different active meter of that resource is a problem, not a
  replacement.
- Meter-based tariffs list a plot as "немає лічильника" when it has no active meter of that resource and do not count it.
- The demo electrician remains the same demo member; his role gets the two permissions in demo data.

---

## Change Log

- 2026-10-21 — created by `/feature-pipeline` from `docs/features/010-meters-and-readings.md`.
- 2026-10-22 — verification — acceptance tests found two mismatches, fixed in the code, not in the spec: the demo data had two pending member readings (now one, plot 7, §2.10), and another plot's meters gave the generic "Не знайдено" (now the code `PLOT_NOT_FOUND`, "Ділянку не знайдено." / "Plot not found.", §2.6 #3). A tree move was written to the audit log but broke the audit list (the action was missing from the contract); fixed (§2.9). The catalogs use the typographic apostrophe (’) in the texts quoted here with a straight one.
- 2026-10-22 — owner review of PR #35: a resource icon (lightning for electricity, drop for water, with an accessible name and a tooltip) next to every meter; the readings round shows a plot with several meters as one card with a row per meter, and remembers the resource filter per browser (falls back to all when storage fails); the meter card and the plot card show the replacement chain both ways (`Meter.replaces`, `Meter.replacedBy`); calculated consumption is displayed to whole kWh (water to 0.01 m³), real readings as entered, the API keeps full precision; demo node meters (substation, quarters) are read on the plot round dates with 5–15 % losses; `make e2e` runs on its own database `nadel_e2e` and never touches the demo database.
