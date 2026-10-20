# 013. Reports for the board and report templates (tax report placeholder)

Roadmap phase 2, feature 5 of 5. Input for `/feature-pipeline`. Requires 009 (tariffs), 010 (meters and the meter
tree), 011 (ledger, charges, period closing) and 012 (recalculation).

## 1. Goal

The chairman and the treasurer get ready-made reports from the data the system already holds, can download them, and
the platform gets a template mechanism: a report form (for example for the tax office) is uploaded as a file with
placeholders, and the system fills it in automatically. The tax report itself is a placeholder in this feature: its
official template is added later, without code changes.

## Context for the pipeline

Read together with `CLAUDE.md`, `docs/domain/money-rules.md` (sections 2, 3, 6, 6.3, 9, 10), `docs/domain/glossary.md`,
ADRs 0006, 0007, 0009, 0011, 0012 (the Excel import module already reads and writes `.xlsx` with fastexcel; reuse it).

Phase 2 sequence: `009 Charge types and tariffs` → `010 Meters and readings` → `011 Ledger, charges and period
closing` → `012 Retroactive recalculation` → `013 Reports`.

## 2. Built-in reports

Each report has filters (period from–to, charge type, street) and is shown on screen and downloadable as `.xlsx` (and
`.pdf` later with phase 4 documents). Amounts come from the server (money-rules section 1); the client only displays.

| Report | Content |
|---|---|
| Charges by period | per charge type and month: number of plots, total charged, reversals and adjustments |
| Plot balances | per plot: charged, allocated, debt per charge type, prepayment, status (money-rules 2, 9) |
| Debtors | plots with debt above a chosen amount or age, owners and contacts (personal data: permission below) |
| Electricity by meter tree | per substation, quarter meter and common consumer: readings (start, end), consumption, price per kWh, amount; plots and members fed; sum of plot readings; losses in kWh and % (money-rules 6.3 — the columns the chairman asked for) |
| Missing readings | plots with an active meter and no reading for the period |
| Land tax | per plot: area, rate, yearly amount; total for the cooperative |

Payments, allocations and cash reports join these when phase 3 adds payments: the reports are built so a new column
set or report can be added without changing the others.

## 3. Report templates (the tax report placeholder)

- A **report template** is an `.xlsx` file uploaded by the chairman (later also by the platform operator for all
  cooperatives) with placeholders:
  - single values: `${cooperative.name}`, `${cooperative.edrpou}`, `${period.from}`, `${period.to}`,
    `${totals.landTax}`, `${totals.charged.MEMBERSHIP}`, `${generatedAt}`, …
  - repeating rows: a row containing `${rows.plots.number}`, `${rows.plots.area}`, … is copied once per plot.
- The cooperative's requisites that templates (and later invoices) need are added to the settings page of 007 in this
  feature: full official name, ЄДРПОУ code, legal address, bank name and IBAN. They are public data of the legal
  entity, not personal data.
- The system publishes the **field catalog**: every placeholder it can fill, with a description and an example, shown
  on the templates page and returned by the API, so the person preparing a template knows what to write.
- Generating a report from a template: choose the template and the period → the system fills it and offers the file.
  Unknown placeholders are left visible and listed as warnings, never silently dropped.
- Templates are versioned (a new upload is a new version; generated files record which version was used). They are
  tenant-scoped; files are stored in the database or object storage as the architecture allows (no new infrastructure
  only for this).
- **Tax report placeholder:** a template entry "Податкова звітність" exists for every cooperative with status "шаблон
  ще не додано". When the official form is available, the owner uploads it with placeholders and adds any missing
  catalog fields in a follow-up feature.
- Security: templates are data, never code. Only `${...}` substitution and row repetition; no formulas are evaluated by
  the server; uploads go through the same size and zip-bomb guards as the Excel import (ADR 0012).

## 4. Permissions

- New `REPORTS_VIEW` (Chairman and Treasurer base roles): built-in reports without personal data.
- Reports with names and contacts (Debtors) also require `MEMBERS_VIEW`.
- New `REPORT_TEMPLATES_MANAGE` (Chairman): upload, version and archive templates.
- Generating a report is audited (who, which report or template, period), because reports can contain personal data.

## 5. Web UI

1. **Звіти** (menu, with `REPORTS_VIEW`): list of built-in reports and templates; filters; preview table; "Завантажити
   Excel".
2. **Шаблони звітів** (with `REPORT_TEMPLATES_MANAGE`): upload, versions, field catalog with copy buttons, the tax
   report placeholder with its status.

## 6. Out of scope

The official tax form itself; PDF output (phase 4 documents); payments reports (phase 3, added to this mechanism);
scheduled or e-mailed reports (phase 8).

## 7. Acceptance criteria

- [ ] Every built-in report of section 2 shows on screen and downloads as `.xlsx`, with totals matching the ledger
      (tested on fixed ledger fixtures with worked numbers).
- [ ] Template upload, field catalog, placeholder filling, repeating rows and unknown-placeholder warnings work;
      a sample template ships with the tests.
- [ ] The tax report placeholder exists for every cooperative with status "template not added".
- [ ] Permissions and audit as in section 4; isolation tests for new tables.
- [ ] `make check` green; e2e: the chairman downloads the electricity report and generates a report from a sample
      template.
