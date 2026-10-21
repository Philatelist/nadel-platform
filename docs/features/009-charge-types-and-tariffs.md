# 009. Charge types and tariffs

Roadmap phase 2, feature 1 of 5. Input for `/feature-pipeline`. Requires 006 (plots), 007 (ownership) and the
cooperative settings page (007, `SETTINGS_MANAGE`).

## 1. Goal

The chairman describes what the cooperative charges for and how much: charge types (membership fee, electricity,
special-purpose fee, land tax, water later), their tariffs with versions that start on a given date, the calculation
basis and frequency, and which plots each tariff applies to. Nothing is charged yet: charges are created by feature 011.
This feature already shows, for any plot and month, what the plot *would* be charged, calculated by the server, so the
chairman can check the tariffs before the first charges exist.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/money-rules.md` (sections 1, 4, 5, 6.1, 10), `docs/domain/glossary.md`, ADRs 0001, 0002, 0006, 0007, 0009,
0011 and the design reference `docs/design/screens/` (board screens).

Phase 2 sequence: `009 Charge types and tariffs` → `010 Meters and readings` → `011 Ledger, charges and period
closing` → `012 Retroactive recalculation` → `013 Reports`.

General rules as in phase 1: contract-first, Flyway only, tenant-scoped tables with RLS and an isolation test,
permissions not role names, Problem Details with a `code`, Ukrainian by default with English available, admin changes
audited through the outbox, unit tests for every money rule, coverage floor raised to the new measured level.

## 2. Domain

### 2.1 Charge types

A cooperative's list of what it charges for. Seeded for every cooperative (editable names, like base roles in 004–005):

| Code | Default name (uk / en) | Typical basis |
|---|---|---|
| `MEMBERSHIP` | Членський внесок / Membership fee | per sotka or per plot |
| `ELECTRICITY` | Електроенергія / Electricity | per kWh |
| `LAND_TAX` | Земельний податок / Land tax | per sotka, yearly |
| `WATER` | Вода / Water | per m³ (inactive until the cooperative has water meters) |

The chairman can add custom charge types, typically special-purpose fees ("Ремонт дороги", "Новий трансформатор").
A charge type has: name, optional description, `active` flag, display order (used later for allocation priority,
money-rules section 7). A charge type that has tariffs is archived, never deleted.

### 2.2 Tariffs and versions

- A tariff belongs to one charge type; a charge type may have several tariffs, for example separate rates for members
  and for non-members who use the infrastructure. (Buildings are reference only and never select a tariff.)
- A tariff has versions. A version has `valid_from` (a date), an optional `valid_to` (derived: the day before the next
  version), the basis, the frequency, the rate and an optional note ("рішення загальних зборів від 12.04.2026").
- Basis: `PER_PLOT`, `PER_AREA_SOTKA`, `PER_KWH`, `PER_M3`, `FIXED_ONE_TIME`.
- Frequency: `MONTHLY`, `QUARTERLY`, `YEARLY`, `ONE_OFF`. `FIXED_ONE_TIME` is always `ONE_OFF` and has a due date.
- Rate: a decimal with up to 4 places (`numeric(14,4)`, JSON string such as `"4.3200"`); it is *not* money and is
  never rounded on its own. Rounding to kopecks happens only when a charge amount is produced (money-rules section 1).
- A version whose `valid_from` is in the past is allowed (retroactive). In this feature it only shows a warning: "Нарахування
  за минулі періоди буде перераховано" — the actual recalculation is feature 012.
- A version can be **corrected or deleted until a charge uses it**; after that a correction goes through recalculation
  (feature 012). A correction takes the same fields and rules as a new version (unique `valid_from` per tariff, rate,
  one-off rule, past-date warning) and the end dates of the neighbours follow, because they are derived. The only
  version of a tariff cannot be deleted (archive the charge type instead). Whether a charge uses a version is asked
  through a port of the tariffs module (`TariffVersionUsage`); no charge exists in this feature, so every version is
  free here, and feature 011 plugs in the real answer. A change is audited with the old and new values.

### 2.3 Which plots a tariff applies to

Each tariff has a scope, chosen by the chairman:

- `ALL_PLOTS`: every plot of kind `PLOT` (common land never).
- `SELECTED_PLOTS`: an explicit list of plots.
- Exclusions: plots removed from `ALL_PLOTS` (for example a plot the general meeting freed from a fee), each with a
  reason. Shown on the plot card.
- `PER_KWH` and `PER_M3` tariffs apply only to plots that have an active meter of that resource (money-rules 6.1; the
  meter registry arrives with 010 — until then such a tariff shows "застосовується до ділянок з лічильником").

### 2.4 Calculation preview (server only)

`expected amount` for a plot, a tariff and a billing month:

```
PER_PLOT        rate × periods share
PER_AREA_SOTKA  rate × (area_m2 / 100) × periods share
PER_KWH/PER_M3  not previewed here (needs readings, 010–011) — shown as "за показаннями"
FIXED_ONE_TIME  rate, in the month of its due date only

periods share   MONTHLY 1 · QUARTERLY 1/3 · YEARLY 1/12 when the cooperative bills monthly (default)
```

- If a version changes inside the month, the month is split by days: each version contributes its rate × days it is
  valid / days in month (decision recorded here so 011 uses the same rule).
- The result is rounded once, HALF_UP to kopecks, at the end.
- Ownership share does not change the plot's amount (debt belongs to the plot, money-rules 8); the preview shows the
  amount per plot.

## 3. Worked examples (each becomes a unit test)

| Case | Input | Expected |
|---|---|---|
| Membership per sotka, yearly rate, monthly billing | 50.0000 UAH/sotka/year, plot 600 m² | 50 × 6 / 12 = 25.00 |
| Odd area | 50.0000 UAH/sotka/year, plot 612 m² | 50 × 6.12 / 12 = 25.50 |
| Rounding once at the end | 33.3333 UAH/sotka/year, plot 700 m² | 33.3333 × 7 / 12 = 19.444425 → 19.44 |
| Per plot, quarterly | 300.0000 UAH/plot/quarter | 100.00 per month |
| Version change mid-month (30-day month) | 50.0000 until the 15th, 60.0000 from the 16th, yearly per sotka, 600 m² | (50 × 6 / 12 × 15/30) + (60 × 6 / 12 × 15/30) = 12.50 + 15.00 = 27.50 |
| Special-purpose fee | FIXED_ONE_TIME 1 200.00, due 01.05.2026 | 1 200.00 in May 2026, 0.00 in other months |
| Excluded plot | ALL_PLOTS with plot 17 excluded | plot 17: no amount, reason shown |
| Land tax | 0.3500 UAH/m² per year expressed as 35.0000 UAH/sotka/year, plot 600 m² | 35 × 6 / 12 = 17.50 per month |
| Common land | any tariff | never applies |

## 4. API (`api/openapi.yaml`)

- `GET/POST /charge-types`, `PUT /charge-types/{id}`, `POST /charge-types/{id}/archive`.
- `GET/POST /tariffs`, `GET /tariffs/{id}`, `POST /tariffs/{id}/versions`, `PUT /tariffs/{id}/scope` (scope,
  selected plots, exclusions with reasons).
- `PUT /tariffs/{id}/versions/{versionId}` (correct a version: `validFrom`, `basis`, `frequency`, `rate`, `dueDate`,
  `note`; 200 with the version) and `DELETE /tariffs/{id}/versions/{versionId}` (204), both `TARIFFS_MANAGE`. 409
  `TARIFF_VERSION_IN_USE` when a charge uses the version, 409 `TARIFF_LAST_VERSION` when it is the only one of its
  tariff, 409 `TARIFF_VERSION_DATE_TAKEN` and `CHARGE_TYPE_ARCHIVED` as for a new version; 404 for an unknown or
  foreign tariff and for a version that is not of that tariff.
- `GET /plots/{id}/tariffs?month=2026-11` — the tariffs that apply to the plot in that month and the server-calculated
  expected amount for each (`Money` strings).
- Rates in JSON are strings with up to 4 decimals (new schema `Rate`, pattern `^\d+(\.\d{1,4})?$`); amounts stay `Money`.

## 5. Data

New migration: `charge_type`, `tariff`, `tariff_version`, `tariff_scope_plot` (selected plots and exclusions with a
reason). All tenant-scoped with RLS, indexes on `tenant_id`, isolation tests. Seed the four charge types for every
existing and new cooperative (like base roles). Versions of the same tariff cannot share a `valid_from`.

## 6. Permissions

- New `TARIFFS_MANAGE` (Chairman base role) and `TARIFFS_VIEW` (Chairman and Treasurer base roles).
- A member sees the tariffs that apply to their own plots and the expected amounts for them (account page and
  `GET /plots/{id}/tariffs` for own plots); other plots' tariffs — no.

## 7. Web UI (style of 003; Ukrainian first)

1. **Тарифи** (menu, with `TARIFFS_VIEW`): list of charge types with their current tariffs, rate, basis and
   frequency, "діє з" date, number of plots it applies to.
2. **Tariff card**: version history (newest first), "Нова версія" form (date, basis, frequency, rate, note) with a
   warning when the date is in the past; scope editor (all plots / selected plots, exclusions with reasons).
3. **Charge types**: add, rename, reorder, archive.
4. **Plot card → Тарифи** tab: tariffs that apply to the plot and the expected amount for the chosen month.
5. **My account**: "Що мені нараховуватимуть" for the member's own plots.

## 8. Audit

Every create, rename, archive, new version, correction or deletion of a version, scope change and exclusion is audited with old and new values (`TARIFF_VERSION_UPDATED` lists the changed fields only; `TARIFF_VERSION_DELETED` the old values).

## 9. Out of scope

Creating charges and the ledger (011), recalculation (012), meters and readings (010), allocation priority use (phase
3), reports (013).

## 10. Acceptance criteria

- [ ] The four charge types are seeded for existing and new cooperatives; custom ones can be added, renamed, archived.
- [ ] Tariffs with versions, bases, frequencies, rates with 4 decimals and scopes work as in section 2.
- [ ] Every row of section 3 is a passing unit test of the calculation; the month split rule is implemented once and
      documented in `money-rules.md` section 4.
- [ ] Permissions and member visibility as in section 6, with allowed and denied tests per endpoint.
- [ ] All changes audited; isolation tests for every new table.
- [ ] `money-rules.md` section 4 updated: `PER_M3`, the `Rate` type with 4 decimals, the month split rule, scopes and
      exclusions.
- [ ] `make check` green; e2e: the chairman creates a membership tariff, sees the expected amount on a plot card; a
      member sees only their own plot's amounts.
