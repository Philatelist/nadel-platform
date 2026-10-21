# Functional Specification: Charge types and tariffs

- **Topic:** What the cooperative charges for (charge types), how much (tariffs with dated versions, a basis, a
  frequency and a rate), which plots each tariff applies to, and what each plot would be charged in a given month,
  before any real charges exist.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/009-charge-types-and-tariffs.md` (roadmap phase 2, feature 1 of 5)

---

## 1. Overview and Rationale (The "Why")

Today the board keeps its fees in the minutes of the general meeting and in a spreadsheet: the membership fee per
sotka, the electricity price, the land tax, a one-off fee for the road repair. When a fee changes, nobody can say later
which rate applied in which month, and every member's amount is worked out by hand.

This feature lets the chairman describe, once, what the cooperative charges for and how much: the charge types, their
tariffs with versions that start on a given date, and which plots each tariff applies to. Nothing is charged yet —
charges arrive with feature 011 — but for any plot and month the app already shows what the plot *would* be charged,
calculated by the server with the cooperative's money rules. The chairman can check the tariffs before the first
charges exist, the treasurer can answer "how much will I pay?", and a member sees what they will be charged for their
own plots.

**Success looks like:** the chairman enters "Членський внесок — 50 грн за сотку на рік" in under two minutes and sees
25.00 for a 6-sotka plot on its card; a member opens "My account" and sees the expected amounts for their own plots and
nothing about anyone else's.

---

## 2. Functional Requirements (The "What")

### 2.1 Charge types

- Every cooperative has a list of charge types: what it charges for. Four are there from the start, for existing and
  new cooperatives:
  - "Членський внесок" / "Membership fee",
  - "Електроенергія" / "Electricity",
  - "Земельний податок" / "Land tax",
  - "Вода" / "Water" — inactive until the cooperative has water meters.
- A charge type has a name, an optional description, an active mark and a position in the list. The four starting
  types are shown in the user's language until the chairman renames them; a renamed type shows the chairman's name in
  both languages.
- The chairman can add custom charge types, typically special-purpose fees ("Ремонт дороги", "Новий трансформатор"),
  rename any type, change its description, mark it active or inactive, move it up or down, and archive it.
- A charge type is never deleted. An archived type disappears from the lists and can no longer get new tariffs; its
  tariffs stay in history but no longer apply to any plot. An inactive type stays in the list, but its tariffs give no
  expected amount until it is active again.
- Names are unique among the cooperative's non-archived charge types (ignoring letter case).
  - **Acceptance Criteria:**
    - [x] When the chairman opens "Види нарахувань" for the first time, then they see Членський внесок, Електроенергія,
      Земельний податок and Вода, with Вода marked inactive; with the interface in English the same types read
      Membership fee, Electricity, Land tax and Water. _Evidence: `TariffsAcceptanceTests` §2.1 (`section21_theFourStartingTypesAreThereWaterInactive`); e2e `tariffs-acceptance.spec.ts` (§2.1 #1, uk and en); `ChargeTypesTests`; `docs/screenshots/009-charge-types-and-tariffs-charge-types.png`._
    - [x] When the chairman adds a charge type "Ремонт дороги", then it appears at the end of the list as active. _Evidence: `TariffsAcceptanceTests` `section21_aCustomTypeIsAddedAtTheEndActive`; e2e `tariffs-acceptance.spec.ts` (§2.1 #2); e2e `tariffs.spec.ts`._
    - [x] When the chairman renames "Членський внесок" to "Членські внески 2026", then the new name is shown in both
      interface languages and in the tariffs list. _Evidence: `TariffsAcceptanceTests` `section21_aRenamedTypeShowsTheNewNameEverywhere`; `ChargeTypesTests`; `chargeTypeName` unit tests._
    - [x] When the chairman adds or renames a type to a name another type already has, then the save is refused with
      "Вид нарахування з такою назвою вже є" / "A charge type with this name already exists". _Evidence: `TariffsAcceptanceTests` `section21_aDuplicateNameIsRefusedOnAddAndOnRename` (incl. the Ukrainian and English defaults); e2e `tariffs-acceptance.spec.ts` (§2.1 #4); `ChargeTypesTests`._
    - [x] When the chairman moves "Земельний податок" up, then it changes places with the type above it, and the new
      order is kept after reloading the page. _Evidence: `TariffsAcceptanceTests` `section21_movingATypeUpChangesPlacesAndTheOrderIsKept`; e2e `tariffs-acceptance.spec.ts` (§2.1 #5, after reload); `ChargeTypesPage.test.tsx` (full id list)._
    - [x] When the chairman archives a charge type and confirms, then it disappears from the list and is no longer
      offered when a tariff is created, and the plots' expected amounts no longer include its tariffs. _Evidence: `TariffsAcceptanceTests` `section21_anArchivedTypeDisappearsGetsNoTariffsAndLeavesTheAmounts`; e2e `tariffs-acceptance.spec.ts` (§2.1 #6)._

### 2.2 Tariffs and versions

- A tariff belongs to one charge type and has a name, for example "Для членів кооперативу" and "Для не членів". A
  charge type may have several tariffs.
- A tariff has versions. Each version has:
  - the date it applies from ("діє з"); it applies until the day before the next version's date, or without end;
  - the basis: per plot, per sotka of the plot's area, per kWh, per m³ of water, or a fixed one-time amount;
  - the frequency: monthly, quarterly, yearly or one-off;
  - the rate, a number with up to 4 digits after the decimal point (for example 4.3200); it is not rounded when saved, and it is shown with its significant decimals but at least two (4.3200 as "4,32", 50.0000 as "50,00", 1.4532 as "1,4532");
  - an optional note, for example "рішення загальних зборів від 12.04.2026".
- A fixed one-time amount is always one-off and has a due date; "one-off" is only possible for a fixed one-time amount.
- Two versions of the same tariff cannot start on the same date.
- Versions are never edited or deleted; a change of rate is a new version.
- A version dated in the past may be saved, with a warning that charges for past periods will be recalculated (the
  recalculation itself is feature 012).
- A tariff is created together with its first version.
  - **Acceptance Criteria:**
    - [x] When the chairman creates the tariff "Для членів кооперативу" for "Членський внесок" with a first version from
      01.01.2027, per sotka, yearly, rate 50, then the tariffs list shows it with "50,00" ("50.00" in English), "за сотку", "щороку" and
      "діє з 01.01.2027". _Evidence: `TariffsPage.test.tsx` ("shows the per-sotka yearly row in Ukrainian with the start date": "50,00 ₴ · За сотку · Щороку", "діє з 01.01.2027"); `TariffsAcceptanceTests` `section22_aTariffWithTwoVersionsListsThemNewestFirstWithFourDecimals`; `docs/screenshots/009-charge-types-and-tariffs-tariffs.png` ("50,00 ₴ · За сотку · Щороку", "діє з")._
    - [x] When the chairman adds a version from 01.07.2027 with rate 60, then the tariff card lists both versions,
      newest first, and the first one is shown as valid until 30.06.2027. _Evidence: `TariffsAcceptanceTests` `section22_aTariffWithTwoVersionsListsThemNewestFirstWithFourDecimals` (valid until the day before); e2e `tariffs-acceptance.spec.ts` (§2.2 #2); `docs/screenshots/009-charge-types-and-tariffs-tariff-card.png`._
    - [x] When the chairman enters a new version dated before today, then the form shows "Нарахування за минулі
      періоди буде перераховано" / "Charges for past periods will be recalculated" before saving, and the version can
      still be saved. _Evidence: `TariffsAcceptanceTests` `section22_aPastDatedVersionIsSavedAndFlagged`; e2e `tariffs-acceptance.spec.ts` (§2.2 #3, warning then saved); `docs/screenshots/009-charge-types-and-tariffs-past-date-warning.png`._
    - [x] When the chairman adds a version with the same start date as an existing version of that tariff, then the
      save is refused with "Версія з такою датою вже є" / "A version with this date already exists". _Evidence: `TariffsAcceptanceTests` `section22_theSameStartDateTwiceIsRefused` (409 `TARIFF_VERSION_DATE_TAKEN`); e2e `tariffs-acceptance.spec.ts` (§2.2 #4 message)._
    - [x] When the chairman enters a rate with more than 4 digits after the decimal point, a negative rate or a rate
      that is not a number, then the save is refused with "Ставка — невід'ємне число з не більше ніж 4 знаками після
      коми" / "The rate must be a non-negative number with at most 4 decimal places". _Evidence: `TariffsAcceptanceTests` `section22_aBadRateIsRefused` (5 decimals, negative, text → `RATE_INVALID`); `RateTests`; e2e `tariffs-acceptance.spec.ts` (§2.2 #5)._
    - [x] When the chairman chooses a fixed one-time amount, then the frequency is set to one-off and cannot be changed,
      and saving without a due date is refused with "Вкажіть дату сплати" / "Enter the due date". _Evidence: `TariffsAcceptanceTests` `section22_aFixedOneTimeAmountIsOneOffWithADueDate`; `VersionFields.test.tsx`; e2e `tariffs-acceptance.spec.ts` (§2.2 #6: frequency disabled, "Вкажіть дату сплати")._
    - [x] When the chairman saves a rate of 4.32, then it is kept as 4.3200 and shown as 4,32 (4.32 in English) everywhere. _Evidence: `TariffsAcceptanceTests` `section22_aTariffWithTwoVersionsListsThemNewestFirstWithFourDecimals` (4.32 stored and returned as 4.3200); `formatRate` tests (`rate.test.ts`: 4.3200 → "4,32"); `TariffsPage.test.tsx`, `TariffPage.test.tsx` (rates shown with at least two decimals)._

### 2.3 Which plots a tariff applies to

- Each tariff has a scope, chosen by the chairman on the tariff card:
  - **All plots** — every active plot that is not common land; new plots join automatically;
  - **Selected plots** — only the plots the chairman picks.
- With "All plots" the chairman can exclude individual plots, each with a reason (for example "звільнено рішенням
  загальних зборів від 12.04.2026"). An exclusion is shown on that plot's card.
- Common land never has tariffs: it cannot be picked or excluded and never gets an amount. Archived plots get nothing.
- Per-kWh and per-m³ tariffs apply only to plots with a meter of that kind. Meters arrive with feature 010; until then
  such a tariff shows "застосовується до ділянок з лічильником" / "applies to plots with a meter" instead of a number
  of plots.
- A new tariff applies to all plots until the chairman changes its scope.
  - **Acceptance Criteria:**
    - [x] When the chairman excludes plot 17 from an all-plots tariff with the reason "звільнено рішенням загальних
      зборів", then the tariffs list shows one plot fewer, and plot 17's card shows the tariff as not charged with that
      reason. _Evidence: `TariffsAcceptanceTests` `section23_anExclusionNeedsAReasonAndShowsOnThePlot` (count −1, EXCLUDED with the reason); e2e `tariffs-acceptance.spec.ts` (§2.3); `docs/screenshots/009-charge-types-and-tariffs-plot-excluded.png`._
    - [x] When the chairman tries to save an exclusion without a reason, then the save is refused with "Вкажіть
      причину" / "Enter a reason". _Evidence: `TariffsAcceptanceTests` `section23_anExclusionNeedsAReasonAndShowsOnThePlot` (blank reason → `REQUIRED`); `ScopeEditor.test.tsx`; e2e `tariffs-acceptance.spec.ts` (§2.3 "Вкажіть причину")._
    - [x] When the chairman switches a tariff to "Selected plots" and picks plots 3 and 5, then the tariffs list shows
      "2 ділянки" and only plots 3 and 5 show this tariff on their cards. _Evidence: `TariffsAcceptanceTests` `section23_selectedPlotsAreCountedAndTheOnlyOnesWithTheTariff`; e2e `tariffs-acceptance.spec.ts` (§2.3 "2 ділянки")._
    - [x] When the chairman searches for a plot in the scope editor, then common land is not offered, and a request to
      add common land anyway is refused with "Спільна земля не має тарифів" / "Common land has no tariffs". _Evidence: `TariffsAcceptanceTests` `section23_commonLandIsNotOfferedAndIsRefused` (`TARIFF_COMMON_LAND`); `PlotPicker.test.tsx`; e2e `tariffs-acceptance.spec.ts` (§2.3)._
    - [x] When the chairman opens a per-kWh electricity tariff, then it shows "застосовується до ділянок з лічильником"
      instead of a number of plots. _Evidence: `TariffsAcceptanceTests` `section23_aPerKwhTariffHasNoPlotCount`; `TariffsPage.test.tsx`; `docs/screenshots/009-charge-types-and-tariffs-tariffs.png` ("застосовується до ділянок з лічильником")._

### 2.4 Expected amount for a plot and a month

- For any plot and month, the app shows each tariff that applies and the amount the plot would be charged, calculated
  by the server. The cooperative bills monthly:
  - per plot: the rate × the month's share of the frequency;
  - per sotka: the rate × the plot's area in sotkas (area in m² ÷ 100) × the month's share;
  - the month's share is 1 for monthly, 1/3 for quarterly and 1/12 for yearly tariffs;
  - per kWh and per m³: no amount here, shown as "за показаннями" / "by meter readings";
  - fixed one-time: the rate in the month of its due date, 0.00 in every other month.
- If a tariff's version changes inside the month, or the first version starts inside it, the month is split by days:
  each version counts for the days it is valid divided by the days in the month.
- The result is rounded once, at the end, to whole kopecks (half up). Rates are never rounded on their own.
- The amount is per plot; how the plot is shared between co-owners does not change it.
- An excluded plot shows the tariff with "не нараховується" / "not charged" and the reason, without an amount. A tariff
  whose first version starts after the month is not shown for that month.
- The total for the month is the sum of the amounts shown.
- Worked examples (each one is an automated check):

  | Case | Input | Expected per month |
  |---|---|---|
  | Membership per sotka, yearly rate | 50.0000 per sotka per year, plot 600 m² | 50 × 6 / 12 = 25.00 |
  | Odd area | 50.0000 per sotka per year, plot 612 m² | 50 × 6.12 / 12 = 25.50 |
  | Rounding once at the end | 33.3333 per sotka per year, plot 700 m² | 33.3333 × 7 / 12 = 19.444425 → 19.44 |
  | Per plot, quarterly | 300.0000 per plot per quarter | 100.00 |
  | Version change mid-month, November 2026 (30 days) | 50.0000 until 15.11, 60.0000 from 16.11, per sotka yearly, 600 m² | 12.50 + 15.00 = 27.50 |
  | Special-purpose fee | fixed one-time 1 200.00, due 01.05.2026 | 1 200.00 in May 2026, 0.00 in other months |
  | Excluded plot | all plots, plot 17 excluded | plot 17: no amount, reason shown |
  | Land tax | 35.0000 per sotka per year, plot 600 m² | 35 × 6 / 12 = 17.50 |
  | Common land | any tariff | never applies |
  - **Acceptance Criteria:**
    - [x] Given a membership tariff of 50 per sotka per year, when the chairman opens the card of a 600 m² plot and
      chooses a month, then the "Тарифи" section shows 25.00 for it. _Evidence: e2e `tariffs.spec.ts` (25.00 on a 600 m² plot); `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable`; e2e `tariffs-acceptance.spec.ts` (§2.4)._
    - [x] Given the version change of the table above, when anyone allowed opens the 600 m² plot for November 2026,
      then the amount shown is 27.50. _Evidence: `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable` (27.50); `ChargeCalculatorTests`; e2e `tariffs-acceptance.spec.ts` (month picker 2026-11 → 27.50)._
    - [x] Given a road-repair fee of 1 200.00 due 01.05.2026, when the chairman chooses May 2026 on a plot card, then
      the fee shows 1 200.00, and when they choose June 2026, then it shows 0.00. _Evidence: `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable` (1200.00 in May, 0.00 in June); e2e `tariffs-acceptance.spec.ts` (§2.4); `docs/screenshots/009-charge-types-and-tariffs-plot-card.png` (0,00 ₴ in October)._
    - [x] Given a per-kWh electricity tariff, when the chairman opens a plot card, then the tariff shows "за
      показаннями" without an amount. _Evidence: `TariffsAcceptanceTests` `section24_landTaxAndMeterBasedTariffs`; e2e `tariffs-acceptance.spec.ts` ("за показаннями"); `docs/screenshots/009-charge-types-and-tariffs-plot-card.png`._
    - [x] When the chairman opens the card of common land, then no tariffs and no amounts are shown. _Evidence: `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable` (common land row); `PlotTariffsTests`; e2e `tariffs-acceptance.spec.ts` (§2.4 #5); `PlotPage.test.tsx` (no panel for common land)._
    - [x] When the chairman opens a plot card for a month before any version of a tariff starts, then that tariff is
      not listed for that month. _Evidence: `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable`; `ChargeCalculatorTests` (version starting after the month → not listed); `PlotTariffsTests`._
    - [x] Given two co-owners at 1/2 each, when the chairman opens their plot card, then the amount is the whole
      plot's amount, not half. _Evidence: `TariffsAcceptanceTests` `section24_coOwnersDoNotSplitTheAmount`; `docs/screenshots/009-charge-types-and-tariffs-member-account.png` (plot 7 owned 1/2 shows the whole 33,33 ₴)._
    - [x] Every row of the worked examples table passes as an automated check. _Evidence: `ChargeCalculatorTests` (one test per row: 25.00, 25.50, 19.44, 100.00, 27.50, 1200.00/0.00, 17.50); `TariffsAcceptanceTests` `section24_everyRowOfTheWorkedExamplesTable`, `section24_landTaxAndMeterBasedTariffs`, `section23_anExclusionNeedsAReasonAndShowsOnThePlot`._

### 2.5 Who can see and change tariffs

- The chairman can change charge types, tariffs, versions and scopes. The chairman and the treasurer can see them; the
  "Тарифи" menu item is shown only to people who may see tariffs. Other roles get these permissions only if the
  chairman adds them to the role.
- A member sees the tariffs and expected amounts only for the plots they currently own: in "My account" and on their own
  plot's card. Another plot's tariffs and amounts are never shown to them, not even by typing an address.
  - **Acceptance Criteria:**
    - [x] When the treasurer opens "Тарифи", then they see the charge types and tariffs, and no add, edit, version,
      scope or archive actions. _Evidence: `TariffsAcceptanceTests` `section25_theTreasurerReadsButCannotChange`; e2e `tariffs.spec.ts`; e2e `tariffs-acceptance.spec.ts` (§2.5); `docs/screenshots/009-charge-types-and-tariffs-treasurer.png`._
    - [x] When the treasurer tries to change a tariff anyway, then the change is refused with "У вас немає дозволу на цю дію." and
      nothing changes. _Evidence: `TariffsAcceptanceTests` `section25_theTreasurerReadsButCannotChange` (403 `PERMISSION_DENIED`, nothing changed); e2e `tariffs-acceptance.spec.ts` (§2.5 #2)._
    - [x] When a member opens the app, then there is no "Тарифи" menu item, and opening the tariffs page directly shows
      "У вас немає доступу до цієї сторінки" / "You don't have access to this page". _Evidence: `TariffsAcceptanceTests` `section25_aMemberHasNoTariffsPermissionAndNoAccess`; e2e `tariffs.spec.ts`; e2e `tariffs-acceptance.spec.ts`; `docs/screenshots/009-charge-types-and-tariffs-member-no-access.png`._
    - [x] When a member opens "My account", then the section "Що мені нараховуватимуть" / "What I will be charged"
      lists, for each of their own plots, the tariffs and the expected amounts for the current month. _Evidence: `TariffsAcceptanceTests` `section25_aMemberSeesOnlyTheAmountsOfOwnPlots`; e2e `tariffs.spec.ts`; e2e `tariffs-acceptance.spec.ts`; `docs/screenshots/009-charge-types-and-tariffs-member-account.png`._
    - [x] When a member asks for the expected amounts of a plot they do not own, then they get "Ділянку не знайдено" /
      "Plot not found" and no amounts. _Evidence: `TariffsAcceptanceTests` `section25_aMemberSeesOnlyTheAmountsOfOwnPlots` (404 `NOT_FOUND`, no items); `PlotTariffsTests`; e2e `tariffs.spec.ts`._
    - [x] When a user of another cooperative opens this cooperative's tariff, then they get "not found" and see nothing
      of it. _Evidence: `TariffsAcceptanceTests` `section25_anotherCooperativeSeesNothing`; `TariffsTenantIsolationTests`; `PlotTariffsTests` (other-tenant plot 404)._

### 2.6 Screens

1. **Тарифи / Tariffs** (menu): the charge types in their order, each with its tariffs: name, current rate, basis,
   frequency, "діє з" date of the current version and the number of plots it applies to. Actions for the chairman:
   "Новий тариф", "Види нарахувань".
2. **Tariff card:** name and charge type; the version history, newest first, with date range, basis, frequency, rate,
   due date and note; the "Нова версія" form (date, basis, frequency, rate, due date for a one-time fee, note) with the
   past-date warning; the scope editor (all plots or selected plots; exclusions with reasons).
3. **Види нарахувань / Charge types:** the list in order with add, rename, description, active mark, move up/down and
   archive.
4. **Plot card → "Тарифи":** a month picker (current month by default) and, for that month, each tariff that applies
   with its amount, "за показаннями", or "не нараховується" with the reason, and the month's total.
5. **My account → "Що мені нараховуватимуть":** the same for the current month for each of the member's own plots.

- All screens are in Ukrainian by default and in English when the user switches the interface language.
  - **Acceptance Criteria:**
    - [x] When the chairman opens "Тарифи" with no tariffs yet, then each charge type is listed with "Тарифів ще немає"
      / "No tariffs yet" and the action "Новий тариф". _Evidence: e2e `tariffs-acceptance.spec.ts` (§2.6 #1 "Тарифів ще немає", "Новий тариф"); `TariffsAcceptanceTests` `section26_anEmptyListAndAMonthThatChangesTheAmounts`; `docs/screenshots/009-charge-types-and-tariffs-tariffs.png` (Вода: no tariffs yet)._
    - [x] When the chairman changes the month on a plot card's "Тарифи" section, then the amounts and the total change
      to that month's values. _Evidence: e2e `tariffs-acceptance.spec.ts` (month 2026-11 → 27.50, 2026-12 → 30.00); `TariffsAcceptanceTests` `section26_anEmptyListAndAMonthThatChangesTheAmounts`; `PlotTariffsPanel.test.tsx`._

### 2.7 Audit

- Every change is written to the audit log with the old and new values: a charge type added, renamed, described,
  activated or deactivated, reordered or archived; a tariff created; a version added; a scope changed; an exclusion
  added or removed with its reason.
  - **Acceptance Criteria:**
    - [x] When the chairman renames a charge type, adds a tariff version and excludes a plot, then the audit log shows
      three entries with the chairman's name and the old and new values, and each entry links to the charge type or
      tariff. _Evidence: `TariffsAcceptanceTests` `section27_renameVersionAndExclusionAreThreeAuditEntries`; e2e `tariffs-acceptance.spec.ts` (§2.7: author, old → new, links to `/charge-types` and `/tariffs/{id}`); `ChargeTypeAuditTests`, `TariffAuditTests`, `TariffScopeAuditTests`._

---

## 3. Scope and Boundaries

### In-Scope

- Charge types: the four starting ones for every cooperative, custom ones, rename, description, active mark, order,
  archive.
- Tariffs with versions (date, basis, frequency, rate with 4 decimals, due date, note), past-date warning.
- Scopes: all plots with exclusions and reasons, selected plots; common land never; meter-based tariffs marked.
- Expected amount per plot and month, calculated by the server, with the month split by days and one rounding; the
  rule written down in the cooperative's money rules so feature 011 uses the same one.
- Permissions to manage and to view tariffs; member visibility of their own plots only.
- Screens: Тарифи, tariff card, charge types, plot card section, "My account" section; Ukrainian and English.
- Audit of every change.

### Out-of-Scope

- Creating charges and the ledger (feature 011), period closing.
- Recalculating past charges after a past-dated version (feature 012); here only the warning.
- Meters and readings (feature 010) and any amount for per-kWh or per-m³ tariffs.
- Using the charge type order for payment allocation (phase 3).
- Reports (feature 013).
- Billing other than monthly; editing or deleting versions; restoring an archived charge type.
- The mobile app.

---

## Assumptions & Decisions

Decided by the pipeline (no blocking question; the requirement document is detailed). Each is recorded in
`pipeline-log.md`.

- The cooperative bills monthly; there is no setting for another billing frequency yet.
- The four starting charge types show their default name in the user's language until renamed (as base roles do).
- Charge types are never deleted, only archived; an archived type cannot be restored in this feature.
- Inactive charge types keep their tariffs, but those tariffs give no expected amount.
- Versions cannot be edited or deleted; a mistake is corrected by a new version with a later date.
- A tariff is created with its first version and applies to all plots until its scope is changed.
- Exclusions exist only for "All plots"; switching a tariff to "Selected plots" removes its exclusions (audited).
- Common land is never in a scope; archived plots get no amounts.
- Rates are zero or positive.
- "One-off" goes only with a fixed one-time amount; the fixed amount is due on one date and is not split by days.
- The month split by days applies both to a version change inside the month and to a first version starting inside it.
- Per-kWh and per-m³ tariffs show "за показаннями" for every plot in scope until meters arrive (feature 010).
- The plot card gets a "Тарифи" section with a month picker (the card has no tabs).
- "My account" shows the current month only; a member can pick another month on their own plot's card.
- The month total is the sum of the per-tariff amounts, each rounded once.
- Treasurers see tariffs through the view permission; any other role can be given either permission by the chairman.

---

## Change Log

- 2026-10-20 — created by `/feature-pipeline` from `docs/features/009-charge-types-and-tariffs.md`.
- 2026-10-21 — verification — wording aligned with the app: rates use the interface's decimal separator ("50,0000" in Ukrainian, "50.0000" in English), and a refused change and a closed page show the app-wide texts "У вас немає дозволу на цю дію." and "У вас немає доступу до цієї сторінки".
- 2026-10-21 — change request from the owner — a rate is shown with its significant decimals but at least two ("50,00", "4,32", "1,4532", "0,35"; a dot in English) instead of four; storage and the API keep four decimals; money formatting unchanged.
