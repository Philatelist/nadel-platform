# 008. Excel import of plots, members and ownership

Roadmap phase 1, feature 4 of 4. Input for `/feature-pipeline`. Requires 002, 006 and 007.

## 1. Goal

Load a whole cooperative in one go: hundreds of plots and members with ownership, from one Excel file, safely and
repeatably. Reuses the validation of features 002–007; adds no new domain rules.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/glossary.md`, `docs/domain/money-rules.md` and ADRs 0002, 0003, 0004, 0007, 0008, 0009.

Feature sequence in roadmap phase 1 (each one builds on the previous and leaves `main` working):
`002 Members and access control` → `003 UI design system` → `004 Role names` → `005 UI preferences` → `006 Plots registry` → `007 Ownership` → `008 Excel import` → `009 Deployment`.
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

## 2. File format

One `.xlsx` file with three sheets. Provide a downloadable template with headers, a short instruction row and one
example row per sheet.

| Sheet | Columns |
|---|---|
| `Plots` | number, street, kind (`PLOT`/`COMMON`), area_m2 or area_sotky (exactly one), cadastral_number, has_building (yes/no, optional, default no), notes |
| `Members` | external_id (any key unique within the file), last_name, first_name, middle_name, phones (comma-separated), email, postal_address, delivery_channel |
| `Ownership` | plot_number, member_external_id, share (`1/2`), valid_from |

Header names are matched case-insensitively; Ukrainian header aliases are accepted (for example `Номер`, `Вулиця`,
`Площа, сот.`). Dates in Excel date format or `DD.MM.YYYY`.

## 3. Flow

1. **Upload** (with `IMPORT_RUN`) → the server parses and validates everything **without saving** and returns an import
   id and a preview: counts of new, updated and unchanged records per sheet, and row-level errors (`sheet`, `row`,
   `column`, message in the UI language).
2. If there are errors, commit is not possible; the user fixes the file and uploads again.
3. **Commit** → everything is saved in **one transaction**; any failure rolls everything back. A preview expires after
   30 minutes and cannot be committed after the data it was based on changed.
4. **Idempotent re-import**: plots match by `number`; members by `email` if present, otherwise by last name + first name
   + first phone; existing records are updated, not duplicated; ownership rows that already exist are skipped. Ownership
   in the file that conflicts with existing ownership is an error (use the change-of-owner flow instead).
5. The import is audited as one action with summary counts and the file name; the individual changes are audited too.

Limits: up to 2,000 plots and 3,000 members per file, maximum 10 MB; larger files are rejected with a clear message.
Only `.xlsx`; macros and external links are ignored. Parse with a streaming reader to keep memory bounded.

## 4. API

- `GET /imports/template` (the `.xlsx` template).
- `POST /imports` (multipart upload) → preview with import id.
- `POST /imports/{id}/commit`.
- `GET /imports` (history: who, when, file name, counts, status).

## 5. Web UI

1. **Import screen**: download template, upload, preview with counts and an error table (sheet, row, column, message),
   commit button enabled only without errors, result summary.
2. **Import history** list.

## 6. Tests (in addition to the general rules)

- Unit: header matching with aliases, area unit handling, share parsing, member matching keys.
- Integration: preview saves nothing; commit saves everything; a failure in the middle rolls back everything; second
  import of the same file changes nothing; conflicting ownership is reported as an error; tenant isolation (import into
  one cooperative never touches another); limits.
- Test fixtures: a valid file (30 plots, 35 members, co-owners), a file with typical errors, an oversized file.
- Playwright: chairman downloads the template, uploads the valid fixture, sees the preview, commits, sees plots in the
  list.

## 7. Out of scope

Import of polygons or cadastral files (phase 6), CSV or Google Sheets import, scheduled or automatic imports.

## 8. Decisions already made (do not ask)

- All-or-nothing import with a mandatory preview step.
- Matching keys as in section 3, step 4.
- One file with three sheets.
