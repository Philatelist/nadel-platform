# Functional Specification: Plots Registry

- **Topic:** The cooperative's list of plots and common land as records — number, street, kind, area, cadastral number, building — with a list, a plot card and a create/edit form.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/006-plots-registry.md` (roadmap phase 1, feature 2 of 4)

---

## 1. Overview and Rationale (The "Why")

Every money feature that follows — tariffs per sotka, charges, debt colours — needs a reliable list of the
cooperative's plots. Today the app knows only a handful of demo plots drawn on a hidden map, and nobody can add, fix or
retire a plot. This feature makes plots real records the board maintains: each plot has a number, a street, its area,
an optional cadastral number and a note on whether a building stands on it. Roads and shared areas are recorded as
common land. There is no map and no owners yet; plots work fully without drawings.

**Success looks like:** the treasurer opens the plots list after signing in, finds plot "12а" by typing "12", and sees
its area in sotkas; the chairman adds, corrects, archives and restores plots without help; an ordinary member sees
only the plot numbers, streets and status colours.

---

## 2. Functional Requirements (The "What")

### 2.1 What a plot record holds

- **Number** — required, free text ("12", "12а", "R-1"); no two active plots of the cooperative may share a number
  (ignoring letter case and surrounding spaces). Another cooperative may use the same number.
- **Street** — optional.
- **Kind** — "Plot" or "Common land" (roads, shared areas). Common land never has owners.
- **Area** — required; shown in sotkas (1 sotka = 100 m²). A plot's area must be greater than zero; common land may be
  zero. On entry the person can type the area in sotkas or in square metres.
- **Cadastral number** — optional; when given it must look like `1234567890:12:345:6789` (10 digits, 2, 3, 4) and be
  unique in the cooperative, archived plots included.
- **Building** — yes/no, default no: whether a building stands on the plot. Reference information only; it does not
  affect any charge.
- **Notes** — optional free text, visible only to people allowed to view plots.
- **Status colour** — until owners are recorded (next feature), every plot shows grey labelled "Без власника" / "No
  owner"; common land shows grey labelled "Спільні землі" / "Common land". The colour is never the only signal; the
  label is always shown.
  - **Acceptance Criteria:**
    - [x] When the chairman saves a new plot "31" on "Вишнева" with area 6 sotkas, then the plot appears in the list with 6 in the "Площа, соток" (area, sotkas) column and a grey "Без власника" mark, and its card shows "6 соток". _Evidence: e2e `plots-acceptance.spec.ts` Ukrainian create (list row "Без власника", card "6 соток (600 м²)"); `PlotsRegistryAcceptanceTests` journey; see docs/screenshots/006-plots-registry-card-treasurer.png._
    - [x] When the chairman saves a plot with the number of an existing active plot (for example "12" or " 12 "), then the save is refused and the number field shows "a plot with this number already exists". _Evidence: `PlotsRegistryAcceptanceTests` refused saves (409 PLOT_NUMBER_TAKEN, field number); e2e `plots.spec.ts` " 12 " inline message; `PlotsWriteTests`._
    - [x] Given plot "12" is archived, when the chairman creates a new plot "12", then it is saved. _Evidence: `PlotsRegistryAcceptanceTests` refused saves (archived 12 frees the number); e2e `plots-acceptance.spec.ts` restore conflict._
    - [x] When the chairman enters a cadastral number in a wrong format (for example "123:45"), then the save is refused and the field shows the expected format; when the number is already used by another plot of the cooperative, then the field shows that it is already taken. _Evidence: `PlotsRegistryAcceptanceTests` refused saves (CADASTRAL_INVALID, CADASTRAL_NUMBER_TAKEN); `CadastralNumberTests`; `PlotForm.test.tsx`._
    - [x] When the chairman enters area 0 for a plot, then the save is refused with a message under the area field; when the kind is common land, then area 0 is accepted. _Evidence: `PlotsRegistryAcceptanceTests` refused saves; `PlotsWriteTests`; `PlotForm.test.tsx`._
    - [x] When the chairman types the area as 600 m², then the plot shows "6 соток"; when they type 6.5 sotkas, then the plot stores and shows "6,5 сотки". _Evidence: e2e `plots-acceptance.spec.ts` Ukrainian (600 м² → "6 соток", "6,5" → "6,5 сотки"); `area.test.ts`._

### 2.2 Plots list

- The plots list shows number, street, area in sotkas, kind and status colour with its label, ordered naturally by
  number ("2" before "10", "12" before "12а"). It has a search box (number, street or cadastral number), filters by
  kind and status, a "show archived" option, and pages of 20.
- People allowed to view plots land on the plots list after signing in; everyone else keeps landing on their account.
- Every signed-in person of the cooperative can open the plots list. People who may not view plots see only number,
  street, kind and status — never area, cadastral number, building or notes.
  - **Acceptance Criteria:**
    - [x] When the treasurer signs in, then the plots list opens first. _Evidence: e2e `plots.spec.ts` treasurer; `acceptance.spec.ts`; see docs/screenshots/006-plots-registry-list-treasurer.png._
    - [x] When the treasurer types "12" in the search box, then the list shows plots whose number, street or cadastral number contains "12", with "12" listed before "12а". _Evidence: e2e `plots.spec.ts` treasurer ("12" before "12а"); `PlotsReadTests`; `NaturalSortKeyTests`._
    - [x] When the treasurer filters by kind "Common land", then only the road and other common land are listed. _Evidence: e2e `plots-acceptance.spec.ts` filter spec; `PlotsReadTests`; see docs/screenshots/006-plots-registry-list-common-land.png._
    - [x] When an ordinary member opens the plots list, then they see numbers, streets and status labels but no area column, and opening a plot shows no area, cadastral number, building or notes. _Evidence: e2e `plots.spec.ts` member; e2e `plots-acceptance.spec.ts` member; `PlotsRegistryAcceptanceTests` member tests; `PlotsReadTests`; see docs/screenshots/006-plots-registry-list-member.png and docs/screenshots/006-plots-registry-card-member.png._
    - [x] When the list has more than 20 plots, then it shows pages and moving to the next page shows the next plots in the same order. _Evidence: e2e `plots-acceptance.spec.ts` paging (page 1 ends "19", page 2 starts "20"); `PlotsReadTests`._
    - [x] When nothing matches the search, then the list shows the shared "nothing found" state; when loading fails, then it shows the shared error state with "Try again". _Evidence: e2e `plots-acceptance.spec.ts` nothing found + error with "Try again" (route mock) and retry; `PlotsPage.test.tsx`._

### 2.3 Plot card

- The plot card shows every field the person may see, the status mark, and an "Owners" section that says owners will
  be shown here later. People allowed to edit plots see "Edit", "Archive" (or "Restore" for an archived plot) and
  "Delete".
- Archiving hides the plot from the default list (it stays visible with "show archived"); restoring brings it back and
  is refused if another active plot took its number meanwhile.
- Deleting is for plots created by mistake: it asks for confirmation and removes the plot for good. Only an active
  (not archived) plot can be deleted; an archived plot must be restored first. A plot that has history (owners from the
  next feature on) cannot be deleted; until then any active plot can be deleted.
  - **Acceptance Criteria:**
    - [x] When the treasurer opens plot "12а", then they see number, street, area in sotkas, kind, cadastral number, building and notes, the grey status mark and the "Owners" section placeholder, and no Edit, Archive or Delete buttons. _Evidence: e2e `plots.spec.ts` treasurer (plots 21 and 9 incl. notes, no edit actions); `PlotsRegistryAcceptanceTests` journey; see docs/screenshots/006-plots-registry-card-treasurer.png._
    - [x] When the chairman archives plot "31", then it disappears from the default list and appears with "show archived" marked as archived; when they restore it, then it is back in the default list. _Evidence: e2e `plots.spec.ts` chairman archive/show archived/restore; `PlotsRegistryAcceptanceTests` journey; `PlotsWriteTests`._
    - [x] Given another active plot now has number "31", when the chairman restores the archived "31", then the restore is refused with "a plot with this number already exists". _Evidence: e2e `plots-acceptance.spec.ts` restore conflict message in the UI; `PlotsRegistryAcceptanceTests`; `PlotsWriteTests`._
    - [x] When the chairman deletes the active plot "31" and confirms, then it is gone from the list, including the archived view; when they cancel the confirmation, then nothing changes. _Evidence: e2e `plots.spec.ts` chairman cancel then confirm delete, gone incl. archived view; `PlotsRegistryAcceptanceTests` journey._
    - [x] Given plot "31" is archived, when the chairman opens its card, then no Delete button is offered (only Restore), and a deletion attempt sent anyway is refused with the message to restore it first. _Evidence: e2e `plots.spec.ts` (no Delete on an archived card, API 409 PLOT_ARCHIVED); `PlotsRegistryAcceptanceTests` journey; `PlotPage.test.tsx`._
    - [x] When a person without permission to edit plots tries to change, archive, restore or delete a plot, then the action is refused with the "You don't have access" message. _Evidence: e2e `plots-acceptance.spec.ts` treasurer/member (no actions, edit page refused, API 403); `PlotsRegistryAcceptanceTests`; `PlotsWriteTests` (incl. PLOTS_EDIT without PLOTS_VIEW → 403)._

### 2.4 Create and edit form

- One form for creating and editing: number, street, kind, area with a unit choice (sotkas or m²), cadastral number,
  building (yes/no) and notes. Each problem is shown under its field; nothing is saved until every field is valid.
  - **Acceptance Criteria:**
    - [x] When the chairman submits the form with an empty number or an empty area, then each empty field shows "required" and nothing is saved. _Evidence: e2e `plots.spec.ts` chairman empty submit; `PlotsRegistryAcceptanceTests` refused saves; `PlotForm.test.tsx`._
    - [x] When the chairman edits plot "12а" and changes the street, then the card shows the new street. _Evidence: e2e `plots.spec.ts` chairman edit street; `PlotsRegistryAcceptanceTests` journey._

### 2.5 Audit

- Every create, change, archive, restore and delete of a plot is recorded in the audit log with who, when and what
  changed; the audit log can be filtered to plots.
  - **Acceptance Criteria:**
    - [x] When the chairman changes plot "12а"'s street and then opens the audit log filtered to plots, then they see an entry for plot "12а" with the old and new street. _Evidence: e2e `plots.spec.ts` audit row "E2E Lane → E2E Avenue"; `PlotsRegistryAcceptanceTests` journey (audit API filtered to PLOT)._
    - [x] When the chairman creates, archives, restores and deletes a plot, then each action appears as its own entry. _Evidence: e2e `plots.spec.ts` five audit rows; `PlotsRegistryAcceptanceTests` journey (actions in order); `PlotsWriteTests`._

### 2.6 Demo data

- The demo cooperative has about 30 plots on two streets and a road as common land; some plots have cadastral
  numbers and buildings.
  - **Acceptance Criteria:**
    - [x] When the treasurer opens the plots list in the demo cooperative, then about 30 plots on two streets and one common-land road are listed, all with grey status marks. _Evidence: e2e `plots-acceptance.spec.ts` (30 plots on Garden St / Orchard St, all "No owner", road "Common land"); `PlotsRegistryAcceptanceTests` demo test; `TenantIsolationTests`; see docs/screenshots/006-plots-registry-list-treasurer.png._

---

## 3. Scope and Boundaries

### In-Scope

- Plot and common-land records, list, card, form, archive/restore/delete, audit, demo data.
- Visibility by permission; the member's restricted view.

### Out-of-Scope

- Owners and ownership (next feature), Excel import, polygons and the map (phase 6), merging and splitting plots.
- Mobile screens.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking).
- Until owners exist every plot is grey ("Без власника"); common land is grey ("Спільні землі").
- Plot numbers are compared ignoring letter case and surrounding spaces; cadastral numbers stay unique including
  archived plots.
- Every signed-in person of the cooperative can open the plots list (restricted view without permission), so ordinary
  members now get a menu with a "Ділянки" / "Plots" item.
- People allowed to view plots land on the plots list; others land on their account.
- The hidden map uses the same visibility: debts and areas are no longer sent to people who may not view plots.

---

## Change Log

- (none yet)
