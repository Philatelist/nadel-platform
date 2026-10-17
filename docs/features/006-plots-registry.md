# 006. Plots registry

Roadmap phase 1, feature 2 of 4. Input for `/feature-pipeline`. Requires 002 (permissions, audit) and 003 (UI kit: build screens from `web/src/components/ui`).

## 1. Goal

The cooperative's list of plots and common land as records: number, street, area, cadastral number. Every later money
feature (tariffs per sotka, charges, debt status) attaches to these records. No polygons and no map in this feature.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/glossary.md`, `docs/domain/money-rules.md` and ADRs 0002, 0003, 0004, 0007, 0008, 0009.

Feature sequence in roadmap phase 1 (each one builds on the previous and leaves `main` working):
`002 Members and access control` → `003 UI design system` → `004 Role names` → `005 UI preferences` → `006 Plots registry` → `007 Ownership` → `008 Excel import`.
The map is not part of phase 1 (roadmap phase 6): plots are records without polygons.

General rules for every feature in this sequence:
- All new tables are tenant-scoped: `tenant_id uuid not null`, RLS with a `tenant_isolation` policy, index on
  `tenant_id`, and an isolation test. Schema changes only through a new Flyway migration (never edit existing ones).
- Contract-first: change `api/openapi.yaml`, run `make gen`, implement the generated interfaces.
- Authorization through permissions (`PERM_*`), never role names (ADR 0009). The server is the authority; the UI only
  hides actions.
- Errors use Problem Details (RFC 9457) with a machine-readable `code` and field errors for validation.
- Web UI: Ukrainian by default, English available; all text through `@nadel/shared` i18n; empty, loading and error
  states on every screen.
- Admin changes are audited through domain events and the Modulith outbox (ADR 0007).
- Tests: unit tests for domain rules, Testcontainers integration tests, isolation test per new table, allowed and denied
  permission tests per endpoint, `ModularityTests` green, raise `coverage.minimum` to the new measured level.
- No mobile changes in phase 1.
- Do not store tax numbers (РНОКПП), passport data or dates of birth.

## 2. Data: extend the existing `plot` table

| Field | Rules |
|---|---|
| `number` | required, unique per cooperative among non-archived plots; free text (`12`, `12а`, `R-1`) |
| `street` | optional |
| `kind` | `PLOT` or `COMMON` (roads, shared areas; never has owners) |
| `area_m2` | required; > 0 for `PLOT`; numeric(10,2); UI shows sotkas (1 sotka = 100 m²) and accepts either unit on input |
| `cadastral_number` | optional; format `NNNNNNNNNN:NN:NNN:NNNN`; unique per cooperative |
| `has_building` | required boolean, default `false`: whether a building stands on the plot (requested by the chairman; reference information only, it does not affect charges) |
| `notes` | optional, visible only with `PLOTS_VIEW` |
| `archived_at` | soft archive; archived plots hidden by default |
| `boundary` | stays nullable and unused |

Hard delete only for a plot created by mistake that has never had an owner (feature 007 adds the ownership check; until
then any non-archived plot without references can be deleted).

Status: a plot without an owner shows `GREY` (money-rules section 9). Until feature 007, every `PLOT` is `GREY`;
`COMMON` is always `GREY`. Keep `plot_status` consistent.

Audit every create, update, archive, restore and delete through the 002 audit mechanism.

## 3. Visibility

- With `PLOTS_VIEW`: all fields.
- A member without `PLOTS_VIEW`: list of plots with **number, street, kind and status only**. Full details only for plots
  they own (that part becomes active with 007; design the response for it now). Enforced in API responses.

## 4. API

- `GET /plots` (search by number, street, cadastral number; filters: kind, status, archived; natural sort by number so
  `2` comes before `10` and `12а` after `12`; paging), `GET /plots/{id}`, `POST /plots`, `PUT /plots/{id}`,
  `POST /plots/{id}/archive`, `POST /plots/{id}/restore`, `DELETE /plots/{id}`.
- The existing GeoJSON response used by the demo map moves to `GET /plots/map` and follows the same visibility rules.

## 5. Web UI

1. **Plots list** (default screen for users with `PLOTS_VIEW`): number, street, area in sotkas, kind, status color chip;
   search, filters, natural sorting, paging. A member sees number, street and status only.
2. **Plot card**: all fields, edit, archive/restore, delete (each by permission). An "Owners" section placeholder that
   feature 007 fills.
3. **Create / edit plot form** with validation messages per field.

## 6. Tests (in addition to the general rules)

- Unit: cadastral number validation, natural sort comparator, sotka/m² conversion.
- Integration: uniqueness of number among non-archived plots per cooperative (same number allowed in another
  cooperative), member visibility on `GET /plots` and `GET /plots/map`, audit entries.
- Playwright: treasurer opens plots list, searches, opens a plot card; member sees no area or notes.
- Demo data: about 30 plots on two streets plus a road as common land.

## 7. Out of scope

Owners and ownership (007), import (008), polygons and map (phase 6), merging and splitting plots.

## 8. Decisions already made (do not ask)

- Plots without polygons are fully functional.
- Soft archive; hard delete only for mistaken plots without history.
- Natural sorting of plot numbers.
