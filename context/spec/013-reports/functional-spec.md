# Functional Specification: Reports and report templates

- **Topic:** The chairman and the treasurer get ready-made reports built from what the app already knows: charges by
  month, plot balances, debtors, electricity by the meter tree, water, missing readings, land tax and corrections.
  Each report is shown on screen and downloaded as an Excel file. The chairman can also upload a report form (for
  example a tax office form) as an Excel file with placeholders, and the app fills it in. The tax report itself is a
  placeholder slot until its official form is uploaded.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/013-reports.md` (roadmap phase 2, feature 5 of 5)

---

## 1. Overview and Rationale (The "Why")

Since features 010 to 012 the app holds meters, readings, charges, losses, common consumers, water connections and
corrections. The board still cannot see any of it as a report: the chairman who asked how much electricity the
cooperative bought, how much the plots used and how much was lost has to read the meter tree and add up the charges
by hand. The treasurer rebuilds the same tables in spreadsheets for every general meeting and for the tax office.

This feature adds the reports and a template mechanism. Reports introduce no new calculation rules: they only show
what is already booked, and every total can be reconciled with the plot accounts and the billing months screen. The
template mechanism lets the cooperative use its own forms without waiting for a new app version.

**Success looks like:** the chairman opens "Electricity by meter tree" for last month, sees each substation, quarter
meter and common consumer with start and end readings, consumption, price, amount, plots fed and losses in kWh and
percent, and downloads it as an Excel file. The chairman uploads the "Land tax statement" form, presses
"Generate" for 2026 and receives the form filled in with every plot, its area, rate and yearly amount. The treasurer
sees the debtors and downloads them with phone numbers for calls. A member and the electrician see no reports.

---

## 2. Functional Requirements (The "What")

All texts are shown in the user's language only (Ukrainian by default, English when chosen), including column headers
and the header block of downloaded files. Plot numbers, names and anything typed by users are shown as entered.
Amounts are shown in hryvnias with kopecks, electricity in kWh and water in m³ with three decimals. The app
calculates every number; the screens only show them.

### 2.1 The Reports section

- **As a** chairman or treasurer, **I want to** open a "Reports" section, **so that** I can find every report I am
  allowed to run.
  - **Acceptance Criteria:**
    - [ ] When a chairman or treasurer signs in, then the menu contains "Reports", and when they open it, they see a
          card for each built-in report they may run and, below them, the report templates.
    - [ ] When a member or a user with the "Electrician" role signs in, then the menu has no "Reports" item, and when
          they open the address of a report directly, then they see a message that they have no access.
    - [ ] When the platform operator signs in, then they see no reports and no cooperative data.

### 2.2 Rules common to every report

- Each report has its own filters and always a period. A period given in months means billing months. A period given
  as dates means the days a service was provided, or the dates of readings. Every report says which of the two it
  uses, and the filter label says so too.
- "Charged" always means the effective amount: charges plus their adjustments minus reversals. Columns that split the
  amount show regular charges, reversals and adjustments separately, so the treasurer can reconcile with plot
  accounts.
- Plots are listed in the natural order of their numbers, meters in the order of the meter tree, charge types in their
  configured order.
- The screen shows the first 200 rows and the totals, and says how many rows the report has in all. The downloaded
  file holds all rows and the same totals. The numbers on screen and in the file always agree.
- A downloaded file has one sheet per report (some reports have two), a header block (cooperative name, report name,
  filters, the date and time it was made in Kyiv time, and who made it), a frozen header row, numbers stored as
  numbers and dates as dates. The file name is made of the report code and the period in Latin letters, for example
  `land-tax_2026-01-01_2026-12-31.xlsx`.
- A value that is not known (for example a meter value at the start of the period) is shown empty with the reason,
  never as zero.
- Until payments are added in a later phase, no report shows a "paid" column, not even an empty one.
- A report with more than 20 000 rows is not produced. The user sees "The report is too large. Narrow the filters."
- Making any report is recorded in the audit log: who, which report, which filters and how many rows, but never the
  contents.
  - **Acceptance Criteria:**
    - [ ] When the user asks for a report with filters that give more than 20 000 rows, then they see "The report is
          too large. Narrow the filters." and no file is offered.
    - [ ] When the user changes the language to English and downloads a report, then the column headers and the header
          block of the file are in English, and plot numbers and names are unchanged.
    - [ ] When the user downloads a report that shows more than 200 rows on screen, then the file contains all rows and
          the same totals as the screen.
    - [ ] When a chairman has run a report and then opens the audit log, then there is an entry with their name, the
          report, the filters and the row count, and the entry shows no report content.
    - [ ] When a fixed set of 300 plots with 12 months of charges is reported, then the report appears within 5
          seconds.

### 2.3 Charges by month

- **As a** treasurer, **I want to** see what was charged per month and charge type, **so that** I can reconcile the
  month with the billing months screen.
  - Filters: billing months from–to, charge types (all by default), street (optional).
  - One row per month and charge type: the month and whether it is open or closed, the charge type, the number of plots
    charged, regular charges (opening balances in their own column), reversals, adjustments and the effective total.
    Totals are shown per month and for the whole range.
  - A second sheet, "By service period", groups the same charges by the month of the service they were for. An
    adjustment booked in March for January shows under January there. A sentence in the header explains the
    difference between the two sheets.
  - **Acceptance Criteria:**
    - [ ] When the treasurer opens the report for one month, then the effective total over all charge types equals the
          total of that month on the billing months screen, to the kopeck.
    - [ ] When a reversal has been booked for a charge, then the report shows it in the reversals column and the
          effective total is smaller by that amount.
    - [ ] When an adjustment booked in March belongs to a January charge, then the "By service period" sheet shows it
          under January and the main sheet shows it under March.
    - [ ] When the treasurer chooses a street, then only plots on that street are counted and summed.

### 2.4 Plot balances

- **As a** treasurer, **I want to** see the debt of every plot per charge type, **so that** I can see who owes what on
  a given day.
  - Filters: "as of" date (today by default), charge types, street, status (red, yellow, green, grey).
  - One row per active plot, common land excluded: number and street, the number of current owners (no names), debt per
    charge type, total debt, the date of the oldest unpaid charge, days overdue and the status colour.
  - For today the status is the one shown on the map. For a past date the status is worked out as of that date with
    today's thresholds, and the header says so.
  - **Acceptance Criteria:**
    - [ ] When the treasurer opens the report for today, then each plot's total debt and status equal those on its
          account and on the plot map.
    - [ ] When the treasurer chooses a past date, then charges booked for later dates are not counted and the header
          says that today's thresholds were used.
    - [ ] When the treasurer filters by status "red", then only red plots are listed.
    - [ ] When the report is opened, then no owner names appear in it.

### 2.5 Debtors

- **As a** treasurer, **I want to** get the list of debtors with their contacts, **so that** I can call or write to
  them.
  - Filters: debt above an amount (by default the cooperative's red amount) or older than a number of days (by default
    the cooperative's red days), charge types, street. The two thresholds work as "or", like the red status.
  - One row per plot that matches: number and street, owners' full names with phone and e-mail, debt per charge type,
    total debt, date of the oldest unpaid charge and days overdue.
  - The report needs the permission to view members in addition to the permission to view reports. Without it the
    report is not listed and its address shows no access.
  - **Acceptance Criteria:**
    - [ ] When the treasurer opens Debtors with the default filters, then only plots over the red amount or older than
          the red days are listed, with owners' names and contacts.
    - [ ] When a user whose role has "view reports" but not "view members" opens the Reports section, then the
          Debtors card is not shown, and when they open its address directly, then they see no access.
    - [ ] When a plot has two owners, then both names and contacts appear in its row.
    - [ ] When the user lowers the amount threshold, then more plots are listed.

### 2.6 Electricity by meter tree

- **As a** chairman, **I want to** see electricity from the substation down to the plots, **so that** I know how much
  was bought, used, lost and charged.
  - Filter: a date range (service days), by default the previous calendar month.
  - Sheet "Consumption", one row per meter-tree node, indented by level: kind, name or plot number, meter serial,
    value at the start and at the end of the range with its type (real, calculated, unknown, and the date of the last
    reading when unknown), consumption in kWh, the sum of the children's consumption, losses in kWh and in percent of
    the node's consumption (for substations and quarter meters whose values are known at both ends), and the number of
    plots fed.
  - When a value is unknown, every figure derived from it on that row is empty with the reason.
  - Sheet "Charged", from the charges booked in the range: per plot the kWh, price per kWh and amount of consumption;
    the losses (as a percentage and amount per plot when losses are a fixed percent, or each booked losses run per node
    when losses are proportional); each booked common-consumer run with its split, consumption, amount, plots and
    plot-days; adjustments and reversals in their own rows with the original service period.
  - Totals: kWh used by plots, kWh of losses charged, kWh of common consumers charged, amount per source and the total.
    The header shows the cooperative's current losses mode and percent; a run booked under another mode still shows
    what it booked.
  - **Acceptance Criteria:**
    - [ ] When the chairman opens the report for a month with a fully known tree, then each node shows start, end,
          consumption and the sum of its children, and losses equal consumption minus the children's sum.
    - [ ] When a meter value is unknown, then the cells derived from it are empty with the reason and nothing shows 0.
    - [ ] When the chairman downloads the report, then the amounts on the "Charged" sheet equal the electricity
          charges for that range shown on the plot accounts.
    - [ ] When a common-consumer run is booked in the range, then it appears with its split, consumption, amount, plots
          and plot-days.
    - [ ] When a booked charge has been adjusted, then the adjustment is a separate row showing the original service
          period.

### 2.7 Water

- **As a** chairman, **I want to** see water use and water charges per plot, **so that** I can explain them to the
  members.
  - Filters: a date range and the water source (a well, the cooperative's water supply, or all).
  - One row per plot that had a water connection in the range: number and street, source, connected days in the range,
    water meter serial if any, cubic metres in the range (connected days only), and water charges by kind (by meter,
    fixed fee, share of a well pump) with adjustments. A summary per source gives plots, connected plot-days, cubic
    metres and amount.
  - Plots that were not connected are not listed.
  - **Acceptance Criteria:**
    - [ ] When a plot was connected for only part of the range, then its row shows only the connected days and the cubic
          metres of those days.
    - [ ] When a plot was not connected on any day of the range, then it is not in the report.
    - [ ] When the chairman picks the source "well", then only plots connected to a well are listed.

### 2.8 Missing readings

- **As a** chairman, **I want to** see which meters have no reading in a period, **so that** I can ask the owners for
  them.
  - Filters: a date range, resource (electricity, water or both), meter kind (plot meters by default; quarter meters,
    substations and common consumers optional), street.
  - One row per active meter with no approved reading in the range: resource and kind, plot number or meter name,
    serial, the last approved reading (date and value), days since it, and whether a submitted reading is waiting for
    approval. The report also shows how many open "not charged" items exist for those meters, with a link to that
    list.
  - A water meter of a plot not connected on any day of the range is not listed. A meter installed after the range or
    removed before it is not listed.
  - **Acceptance Criteria:**
    - [ ] When a plot meter has an approved reading in the range, then it is not listed.
    - [ ] When a plot meter has only a reading waiting for approval, then it is listed and marked as waiting.
    - [ ] When a water meter belongs to a plot not connected during the range, then it is not listed.
    - [ ] When the user follows the link to the "not charged" list, then the list opens.

### 2.9 Land tax

- **As a** treasurer, **I want to** see the land tax per plot for a year, **so that** I can prepare the tax report.
  - Filter: a year.
  - One row per active plot the land tax applies to: number and street, area in m² and in sotkas, the rate per sotka
    (when several rates applied in the year, one row per rate with its days), the yearly amount the app expects, and
    the amount actually charged for that year. A total for the cooperative is shown.
  - A plot whose expected and charged amounts differ is marked, and the report suggests a recalculation without
    booking one.
  - **Acceptance Criteria:**
    - [ ] When the rate is 35,00 per sotka per year and a plot is 600 m², then its row shows 6 sotkas and a yearly
          amount of 210,00.
    - [ ] When the amount charged differs from the expected amount, then the row is marked and the suggestion to
          recalculate is shown.
    - [ ] When two rates applied in the year, then the plot has one row per rate with its days.
    - [ ] When the user reads the total, then it equals the sum of the rows as listed.

### 2.10 Corrections

- **As a** chairman, **I want to** list every reversal and adjustment of a period, **so that** I can show the general
  meeting every change of the past.
  - Filters: billing months from–to, charge types, kind (reversal, adjustment or both).
  - One row per correction: booked on, billing month, plot, charge type, the original charge (its service period and
    amount), the amount, the source (manual reversal, recalculation or run reversal), the cause and reason of the
    recalculation if there was one, and who booked it.
  - **Acceptance Criteria:**
    - [ ] When a recalculation booked an adjustment, then the report lists it with the original charge, the cause and
          the reason, and the name of the person who booked it.
    - [ ] When the user chooses only "reversal", then adjustments are not listed.
    - [ ] When a month has no corrections, then the report says there is nothing to show instead of an empty table.

### 2.11 Report templates

- **As a** chairman, **I want to** upload my own report form with placeholders, **so that** the app fills in the
  official or customary form for me.
  - A template is an Excel file with placeholders written like `${cooperative.name}`. A placeholder alone in a cell is
    filled with a number or a date, so formulas in the template keep working. A placeholder inside text is filled as
    text in Ukrainian number and date format.
  - Single values include the cooperative's name, full official name, ЄДРПОУ code, address, bank, IBAN, the period
    (from, to, year), when and by whom the file is made, and totals such as the land tax total, the charged total per
    charge type and the total debt.
  - A row containing placeholders of one row set is repeated once per row of that set. Row sets are plots, months,
    meters and water. A sheet may have one repeating row.
  - The templates page shows a catalog of every placeholder: its type, a description in both languages, an example and
    the permission it needs, with a search box and a copy button for each. Names and contacts of people are not
    available in templates.
  - Press "Generate", choose the template and a period, and receive the filled file. A placeholder the app does not
    know stays visible in the cell and is listed as a warning; a placeholder whose value is unknown is left empty and
    listed. The warnings are shown on screen and in a "Warnings" sheet added to the file.
  - Uploading a new file for a template makes a new version; generating always uses the latest version; older versions
    can be downloaded. The generated file names the template and its version in its properties and in the audit log.
    Generated files are not kept. Archiving a template hides it from generation and keeps its versions.
  - A file is refused if it is not an Excel workbook, contains macros, is larger than 2 MB, is damaged or is
    unreasonably large when unpacked. Links to other files are removed and listed as a warning. The app never runs
    formulas or code from a template.
  - **Acceptance Criteria:**
    - [ ] When the chairman uploads a valid template, then it appears in the list as version 1, and when they upload a
          new file to it, then it becomes version 2 and version 1 can still be downloaded.
    - [ ] When the chairman generates from a template with a repeating row for plots, then the file has one row per
          plot with the plot's values.
    - [ ] When a cell contains only `${totals.landTax.expected}`, then the generated cell holds a number and a formula
          in the template that refers to it still works.
    - [ ] When a template contains `${nonsense.field}`, then the generated file still shows `${nonsense.field}` in the
          cell and the warning list names it.
    - [ ] When the chairman uploads a file with macros, a file over 2 MB, a file that is not an Excel workbook or a
          compressed bomb, then they see "The template file is not valid" (or "too large" for size) and nothing is
          stored.
    - [ ] When the chairman archives a template, then it can no longer be generated and shows as archived, and its
          versions can still be downloaded.
    - [ ] When a chairman generates from a template, then the audit log has an entry with the template name, its
          version and the period.
    - [ ] When a user with the permission to view reports but not to manage templates opens the Reports section, then
          they can generate from active templates but cannot upload, archive or download template versions.
    - [ ] When a user has neither permission, then the templates page and its addresses show no access.

### 2.12 The tax report slot

- The templates page always shows a fixed slot "Tax report" for every cooperative. Until the chairman uploads a
  template into it, it shows the status "template not added yet". When the official form is available, it is uploaded
  into the slot like any other template. Fields missing from the catalog for the official form are added in a later
  feature.
  - **Acceptance Criteria:**
    - [ ] When the chairman opens the templates page of any cooperative that has uploaded nothing, then the "Tax report"
          slot shows "template not added yet".
    - [ ] When the chairman uploads a template into the slot, then its status changes to the template's version and the
          slot can be used to generate.

### 2.13 Cooperative requisites

- **As a** chairman, **I want to** enter the cooperative's legal details, **so that** templates (and later invoices)
  can use them.
  - The settings page gets a block "Requisites" with the full official name, legal address, bank name and IBAN.
    The IBAN must be a valid Ukrainian IBAN. The ЄДРПОУ code is shown read-only with the hint "changed by the
    platform operator". These are public data of the legal entity, not personal data. Changing them needs the
    permission to manage settings and is recorded in the audit log with the changed fields.
  - **Acceptance Criteria:**
    - [ ] When the chairman enters a valid IBAN and saves, then the requisites are saved and the audit log shows the
          changed fields.
    - [ ] When the chairman enters an IBAN that is not a valid Ukrainian IBAN, then they see "The IBAN is not valid" and
          nothing is saved.
    - [ ] When the chairman opens the block, then the ЄДРПОУ code is shown but cannot be edited.
    - [ ] When a user without the permission to manage settings opens settings, then the requisites cannot be changed.
    - [ ] When a template uses the IBAN placeholder, then the generated file contains the saved IBAN.

### 2.14 Permissions

- The chairman and the treasurer can view and download the built-in reports (without personal data) and generate from
  templates. The chairman alone manages templates, including the tax report slot. Debtors also needs the permission
  to view members. Custom roles can be given these permissions in the roles screen. Members and the electrician get no
  reports by default.
  - **Acceptance Criteria:**
    - [ ] When the treasurer signs in, then they see all reports including Debtors, and no template management.
    - [ ] When the chairman opens the roles screen, then "View reports" and "Manage report templates" appear with
          descriptions, and the chairman role has both.
    - [ ] When a custom role is given "View reports" without "View members", then its holders see every report except
          Debtors.

### 2.15 Links from other screens

- The plot account and the billing months screen link to the matching report with filters already set.
  - **Acceptance Criteria:**
    - [ ] When the treasurer clicks the report link on a billing month, then Charges by month opens for that month.
    - [ ] When the treasurer clicks the report link on a plot's account, then Plot balances opens filtered to that
          plot's street.

### 2.16 Sample template and demo data

- The demo cooperative includes a sample template "Land tax statement", so "Generate" works in the demo.
  - **Acceptance Criteria:**
    - [ ] When the chairman of the demo cooperative generates the "Land tax statement" for the current year, then they
          receive a filled file with every plot, its area, rate and yearly amount.

---

## 3. Scope and Boundaries

### In-Scope

- The eight built-in reports of section 2 with on-screen preview and Excel download.
- Report templates: upload, versions, archive, the placeholder catalog, generation with warnings.
- The fixed tax report slot with its status.
- The requisites block on the settings page.
- The two new permissions, audit entries for generating, uploading and archiving.
- The Reports section and the templates page, links from plot accounts and billing months.
- Guides in both languages, the demo sample template.

### Out-of-Scope

- The official tax form itself.
- PDF output (later phase with documents).
- Payment, allocation and cash reports (added when payments arrive).
- Scheduled or e-mailed reports.
- Templates shared by the platform operator for all cooperatives.
- Names and contacts in templates.
- Charts.

---

## Assumptions & Decisions

- Spec source: the user chose the reconciled requirement from branch `docs/feature-013-reports` (Phase 1 answer).
- The "as of" date, filters and defaults are as in the requirement; the 20 000-row cap and the 200-row preview are as
  in the requirement.
- Generation runs while the user waits; no background queue.
- The Treasurer base role receives both Reports permission and keeps its existing view-members permission, so it sees
  Debtors.
- The template catalog is built into the app (a fixed list), not stored per cooperative.
- The tax report slot is a fixed entry; the first upload into it creates the template record.
- The exact wording of messages is final in the i18n catalogs; those in this spec are the intended meaning.
- "Download template version" requires the manage-templates permission.

---

## Change Log

- (none)
