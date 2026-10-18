# Technical Specification: Plots Registry

- **Functional Specification:** [functional-spec.md](functional-spec.md) (source: `docs/features/006-plots-registry.md`)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

Extends the phase-0 `app.nadel.plots` module (read-only map stub) into a registry: one Flyway migration on the existing
`plot` table, contract-first CRUD + archive/restore/delete endpoints, the map GeoJSON moved to `GET /plots/map`,
visibility decided in one mapper, audit through the 002 outbox, and web list/card/form screens built from the 003 kit.

---

## 2. Proposed Solution & Implementation Plan

### 2.1 Data — `V6__plots_registry.sql`

| Item | Change |
|---|---|
| `natural_sort_key(text)` | immutable SQL function: digit runs left-padded to 20, other runs lower-cased |
| `plot` new columns | `has_building boolean not null default false`, `notes text`, `archived_at timestamptz`, `updated_at timestamptz not null default now()`, `number_sort_key text collate "C"` generated from `natural_sort_key(number)` (not mapped in JPA) |
| uniqueness | drop `UNIQUE (tenant_id, number)` (`plot_tenant_id_number_key`); `plot_tenant_number_uq` on `(tenant_id, lower(number)) where archived_at is null`; `plot_tenant_cadastral_uq` on `(tenant_id, cadastral_number) where cadastral_number is not null` |
| checks | `plot_area_by_kind_chk` (PLOT > 0, COMMON ≥ 0); `plot_number_chk` (1–20 chars, trimmed); `plot_cadastral_chk` (`^\d{10}:\d{2}:\d{3}:\d{4}$`) |
| `plot_status` | all rows reset to GREY with debt 0; missing rows inserted as GREY |

RLS, policy, `tenant_id` index and grants unchanged (existing table).

**Demo data (`R__demo_data.sql`, plots part):** 30 plots on "Garden St" (1–14 + "12а") and "Orchard St" (15–29), road
`R-1` as common land; varied areas, ~10 cadastral numbers, buildings on about every third plot, notes on two; polygons
kept for the hidden map; statuses GREY; conflict target `(tenant_id, lower(number)) where archived_at is null`. Other
tenant keeps 2 plots.

### 2.2 API (`api/openapi.yaml`, then `make gen`)

| Method & path | operationId | Permission | Notes |
|---|---|---|---|
| `GET /plots` | `listPlots` | signed in | `q` (≤100), `kind`, `status`, `includeArchived`, `page`, `size` (default 20, max 100) → `PlotPage` |
| `GET /plots/map` | `getPlotMap` | signed in | was `getPlots`; `PlotFeatureCollection`; `areaM2`, `debt` optional |
| `GET /plots/{id}` | `getPlot` | signed in | `PlotDetails` |
| `POST /plots` | `createPlot` | `PLOTS_EDIT` | 201 + `Location` |
| `PUT /plots/{id}` | `updatePlot` | `PLOTS_EDIT` | |
| `POST /plots/{id}/archive` / `restore` | `archivePlot` / `restorePlot` | `PLOTS_EDIT` | idempotent; restore re-checks the number |
| `DELETE /plots/{id}` | `deletePlot` | `PLOTS_EDIT` | 204; archived → 409 `PLOT_ARCHIVED` |

Schemas: `PlotSummary` (required `id, number, kind, status, archived`; optional `street, areaM2, cadastralNumber,
hasBuilding` — absent for restricted viewers), `PlotDetails` (+ `notes, archivedAt, createdAt, updatedAt`), `PlotPage`
(as `MemberPage`), `PlotInput` (`number` 1–20, `kind`, `areaM2` decimal → `BigDecimal`, optional `street` ≤100,
`cadastralNumber` ≤22 without a pattern, `hasBuilding`, `notes` ≤2000). Codes: 409 `PLOT_NUMBER_TAKEN` (field
`number`), `CADASTRAL_NUMBER_TAKEN` (field `cadastralNumber`), `PLOT_ARCHIVED`; field codes `CADASTRAL_INVALID`,
`AREA_INVALID`. `AuditAction` + `PLOT_CREATED/UPDATED/ARCHIVED/RESTORED/DELETED`; `AuditEntityType` + `PLOT`.

### 2.3 Backend (`app.nadel.plots.internal`, package-private)

- `PlotsController` (replaces `PlotController`): write endpoints `@PreAuthorize("hasAuthority('PERM_PLOTS_EDIT')")`;
  reads require a cooperative member (operator → 403).
- `PlotQueries` (`list`, `card`, `map`; absorbs `PlotMapQuery`), `PlotRepository.search` (native, escaped `like`, filters,
  `order by number_sort_key, number, id`, count query), `PlotDtoMapper` with the single visibility seam
  `canSeeDetails(viewer, plot)` = `PLOTS_VIEW` now (ownership in 007). Restricted viewers: no optional fields,
  `includeArchived` ignored, archived card → 404, `q` matches number and street only, map without area/debt.
- `PlotStatusPolicy.statusOf(kind, cached)` (COMMON or no row → GREY).
- `Plot` entity: new fields and `create/apply/archive/restore`; `PlotStatusEntry.initial` (GREY) inserted on create.
- `PlotData.from(PlotInput)`: trims, REQUIRED/TOO_LONG, `AREA_INVALID` (scale > 2, too large, PLOT ≤ 0), `CadastralNumber`
  value object (`CADASTRAL_INVALID`), all field errors in one 400. Area is never rounded.
- `PlotCommands` (`create/update/archive/restore/delete`, `@Transactional`): friendly pre-checks + index-name mapping
  to 409; no-op updates write no audit; `PlotSnapshot` (`of`, `diff`, `NONE`) → `AuditEvent` `PLOT_*`, entity `PLOT`,
  label = number; delete only when not archived (seam for 007 references).
- `plots/README.md`, `package-info` text.

### 2.4 Web

| Area | Change |
|---|---|
| `packages/shared/src/area.ts` | `toSquareMetres(value, unit)`, `m2ToSotky(m2)`; string decimal arithmetic, no floats in payloads; tests |
| status label | `plotStatusLabel(t, plot)` — GREY → `status.GREY_PLOT` / `status.GREY_COMMON` by kind; used by list, card, map |
| `Layout` | nav item "Plots" for every signed-in member (no permission), first in the menu |
| `App.tsx` | `HomeRedirect`: `PLOTS_VIEW` → `/plots`, else `/account`; routes `/plots`, `/plots/:id` (signed in), `/plots/new`, `/plots/:id/edit` (`PLOTS_EDIT`) |
| `PlotsPage` | URL params `q, kind, status, archived, page`; columns by viewer; "Add plot" with `PLOTS_EDIT`; shared states |
| `PlotPage` | details `<dl>` by viewer, status mark, "Owners" placeholder, Edit/Archive/Restore/Delete (`PLOTS_EDIT`), `ConfirmDialog` for delete |
| `PlotForm` / `PlotFormPage` | fields incl. area + unit select; field-error mapping incl. `PLOT_NUMBER_TAKEN`, `CADASTRAL_NUMBER_TAKEN`, `PLOT_ARCHIVED` notice |
| `AuditPage` | entity type `PLOT`, link to the card, field labels, action labels |
| `MapPage` | `useGetPlotMap`, optional `areaM2`/`debt`, kind-aware label |
| i18n | `nav.plots`, `status.GREY_PLOT/GREY_COMMON`, `plots.*`, `plot.*`, `problem.*`, `validation.*`, `audit.*` (uk + en) |

---

## 3. Impact and Risk Analysis

- `/plots` changes meaning; `MeEndpointTests`, `plots/internal/TenantIsolationTests` and `MapPage` updated.
- Coverage floor 0.94 is flaky on main (baseline 0.9393); new code must be well tested so the branch ends above it.
- Members now get a side menu (Plots item); e2e that assert "no menu for a member" are updated.
- Landing route changes for people with `PLOTS_VIEW`; e2e landing assertions updated.
- `lower()` under the DB locale for Cyrillic: a test that "12а" and "12А" collide.

## 4. Testing Strategy

- Unit: `CadastralNumber`, `PlotData`, `PlotSnapshot`, `PlotStatusPolicy`; web: area helpers, status label, PlotForm,
  PlotsPage restricted columns, nav, HomeRedirect.
- Database: `natural_sort_key` ordering and case-insensitive collision (replaces the comparator unit test of the feature
  doc, since the sort lives in SQL).
- Integration: CRUD, uniqueness (other tenant, archived frees number, restore conflict, cadastral including archived,
  concurrency), permissions allowed/denied per endpoint (chairman, treasurer, member, operator), visibility on list,
  card and map, filters, paging, natural order, audit entries, delete rules; isolation for new columns.
- E2E: treasurer lands on plots, searches, opens a card; member sees no area/notes; chairman create → edit → archive →
  restore → delete; audit row.
- `make check`; coverage floor raised to the measured value rounded down.

## Assumptions (recorded, not confirmed)

- Natural sort computed in the database (generated column).
- Deleting an archived plot is refused (`PLOT_ARCHIVED`).
- One `PlotSummary` schema with optional fields for restricted viewers.
- Restricted viewers cannot search by cadastral number and never see archived plots.
- Plot number limited to 20 characters.
