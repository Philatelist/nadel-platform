# Technical Specification: Meters and readings

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Draft
- **Author(s):** Alex Usov (pipeline; inputs from spring-backend, postgres-database and react-frontend)

---

## 1. High-Level Technical Approach

- A new Spring Modulith module **`app.nadel.meters`** owns meters, the parent history (tree), readings, the round list,
  approvals, consumption and the import facade for the meters sheet.
- Dependencies: `meters → plots (PlotCatalog, PlotChangeGuard SPI), identity, audit, problem`; `tariffs → meters`
  (`MeterCatalog`); `imports → meters` (`MeterImport`). `meters` never depends on tariffs or imports; `plots` never
  depends on meters (it defines the SPI, meters implements it — ADR 0011 pattern).
- **Pure calculation** in the module root: `MeterValue` (scale ≤ 3, never rounded on parse), `Quantity` (exact
  rational), `ConsumptionCalculator` (intervals, value at a date with linear interpolation by days, range consumption
  across replacements, split by parent periods, unusual-consumption check). Unit tests for every row of functional spec
  §2.5. ADR 0014 records the rational arithmetic and the single display rounding of calculated values.
- **Ports for 011:** `ReadingUsage.isUsed(tenantId, meterId, from, to)` (range-based, default bean returns false — same
  pattern as `TariffVersionUsage`); `MeterCatalog` for meter presence.
- V12 adds three tenant tables with RLS, the four permissions (full `seed_base_roles` re-create plus targeted inserts),
  and demo meters. Contract first (`api/openapi.yaml`, tags `meters`, `readings`, `meter-tree`), then `make gen`.
- Web: Покази (phone-first round), Лічильники (register and card), На підтвердження, Налаштування → Дерево
  лічильників, plot card panel, "Мої лічильники"; menu and landing rules for the electrician; one UI language
  (catalogs only, `data-user-content`, ESLint rule, language-purity spec).

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database (`V12__meters_and_readings.sql`)

Style as V8–V11: text + named CHECKs, `*_tenant_id_uq`, composite FKs on `(tenant_id, …)`, RLS `tenant_isolation`
(`USING` + `WITH CHECK`), `tenant_id` index, `REVOKE TRUNCATE` from `nadel_app`.

| Table | Key columns and rules |
|---|---|
| `meter` | `kind` (`PLOT`, `SUBSTATION`, `QUARTER`, `COMMON_CONSUMER`); `resource` (`ELECTRICITY`, `WATER`; `kind = PLOT OR resource = ELECTRICITY`); `serial` 1–50; `name` (required unless `PLOT`, ≤ 100); `plot_id` → `plot` RESTRICT, `(kind = PLOT) = (plot_id IS NOT NULL)`; `installed_on`, `initial_value numeric(14,3) ≥ 0`; `removed_on`/`final_value` together, `removed_on ≥ installed_on`; `note` ≤ 500; `replaces_meter_id` → `meter`. Partial unique `(tenant_id, plot_id, resource) WHERE kind = 'PLOT' AND removed_on IS NULL` (→ `METER_ACTIVE_EXISTS`). |
| `meter_parent` | `meter_id`, `parent_meter_id` → `meter`, `meter_id <> parent_meter_id`; `valid_from`, `valid_to` (inclusive, null = open); `EXCLUDE USING gist (tenant_id WITH =, meter_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)` (btree_gist from V8). Indexes `(tenant_id, parent_meter_id, valid_from)`, partial open rows. |
| `meter_reading` | `meter_id` → `meter` RESTRICT; `taken_on` date; `value numeric(14,3) ≥ 0`; `source` (`ENTERED`, `IMPORTED`, `MEMBER`); `status` (`APPROVED`, `PENDING`, `REJECTED`, `SUPERSEDED`); `entered_by_member_id` → `member`; `reject_reason` iff `REJECTED` (1–500); `superseded_by_id` → `meter_reading` (ON DELETE SET NULL) iff `SUPERSEDED`; `decided_by_member_id`, `decided_at`. Partial unique `(tenant_id, meter_id, taken_on) WHERE status = 'APPROVED'`; index `(tenant_id, meter_id, taken_on)`; partial `(tenant_id) WHERE status = 'PENDING'`. |

Cross-table rules in the service: a `PLOT` meter only on a plot of kind `PLOT`; tree rules; ordering. Grants: UPDATE and
DELETE kept for all three (meter delete without readings, parent correction, reading correction/deletion; the use
check is the port).

**Permissions.** `METERS_VIEW`, `METERS_MANAGE`, `READINGS_ENTER`, `READINGS_APPROVE`: re-create `seed_base_roles`
in full (Chairman 15, Treasurer + `METERS_VIEW`; descriptions unchanged; keep `SELECT seed_charge_types(p_tenant)`),
then targeted inserts for existing tenants (Chairman 4, Treasurer 1). Never re-run the seed.

**Demo data** (`R__demo_data.sql`, block `-- BEGIN demo meters` / `-- END demo meters`, Ukrainian, fixed ids, ON
CONFLICT DO NOTHING, dates relative to `CURRENT_DATE` with one anchor per statement): "Підстанція №1"; "Квартал А",
"Квартал Б" under it; "Свердловина №1" under the substation, "Освітлення вул. Вишнева" under "Квартал А"; plot meters on
most plots split across the quarters; plots 25 and 28 without a meter; one replacement (plot 9, 30 days ago); 3–5
irregular approved readings per meter (gaps 6–55 days) over ~120 days; one plot last read ~75 days ago; one pending
member reading for plot 7 by member@demo.local; a meter and reading in the other tenant. Електрик gets `METERS_VIEW`,
`READINGS_ENTER` and the description "Вносить покази лічильників на обході" (legacy UPDATE of the old text). Counts in
a comment at the top of the block. Test helper `DemoMeterData` (clear in FK order / restore the block), like
`DemoTariffData`.

### 2.2 Backend

**Module root (public):** `MeterResource`, `MeterKind`, `MeterValue`, `Quantity`, `ConsumptionCalculator` with records
`MeterHistory`, `Reading`, `Interval`, sealed `ValueAt` (`Real`, `Calculated`, `Unknown`), `RangeConsumption`
(`Known`, `Unknown`), `ParentPeriod`, `Slice`; `MeterCatalog`; `ReadingUsage`; `MeterImport` + records.

**Calculation rules.**
- The initial reading (on `installed_on`) and the final reading (on `removed_on`) are points of the meter's history.
- Interval = value − previous point; range = `valueAt(to) − valueAt(from)` clipped to the meter's working period, summed
  over the plot's chain of meters (replacement, wrap-around).
- `valueAt(d)` = the approved reading on `d`, else exact linear interpolation `(v0·N + (v1 − v0)·k) / N` between the
  nearest points (`Calculated`), else `Unknown(lastReadingOn)`.
- `Quantity` keeps exact numerator/denominator; `toMeterValue()` rounds HALF_UP to 3 decimals for display only
  (ADR 0014; money-rules §6 note). Consumption is computed from exact values; 011 feeds `Quantity` to money.
- Unusual: baseline = earlier approved intervals whose end lies in `(d − 180, previous date]`; warn when
  `c · Σdays > 3 · Σc · n`; no warning without a baseline or with `Σc = 0`.
- Tree range: per parent period overlapping `[from, to]`, the slice `[max(from, pFrom), min(to, pTo)]`; adjacent slices
  share the boundary value, so they sum to the total.

**Internal:** entities `Meter`, `MeterParent`, `MeterReading` + repositories; `MeterCommands` (register, update serial/
name/note, remove, replace, delete, parent), `ReadingCommands` (enter one/batch, member submit, correct, delete, approve,
reject), `ReadingValidator`, `MeterQueries`, `RoundQueries`, `TreeQueries`, `MeterAuditPublisher`, `MeterPlotGuard`
(implements `plots.PlotChangeGuard`), `NoChargesYetUsage`, controllers `MetersController`, `ReadingsController`,
`MeterTreeController`.

**Rules.**
- Ordering per meter (approved readings + initial/final points, excluding the same date): `prev ≤ v ≤ next`;
  otherwise 409 `READING_OUT_OF_ORDER`. Date: not in the future (Kyiv `Clock`), within `[installed_on, removed_on]`.
- Same date: the existing approved reading becomes `SUPERSEDED` (update + flush before inserting the new approved row,
  then link). Correction (`PUT`) = a new row superseding the old (history kept); delete = hard delete, audited.
- Member submission: own current plot (`OwnedPlotsSource`), active meter, always `PENDING`; a second pending for the
  same meter and date replaces the first; approval re-validates ordering and supersedes an approved reading of that date.
- Use check: `ReadingUsage` over the span between the neighbours; 409 `READING_IN_USE`.
- Locks: plot `FOR SHARE` (meter create/replace), meter `FOR UPDATE` for every reading write, remove and replace; parent
  moves lock new parent `SHARE`, child `UPDATE`.
- Tree: SUBSTATION has no parent; QUARTER under SUBSTATION; PLOT and COMMON_CONSUMER under SUBSTATION or QUARTER;
  electricity only; parent active on the effective date; a replacement takes over its predecessor's parent and, for a
  non-plot meter, its children. Moves append a parent row (an equal date corrects the latest). 409 `METER_TREE_INVALID`.
- Plot guard: delete and PLOT→COMMON refused when any meter exists, archive when an active meter exists (409
  `PLOT_HAS_METER`); wired into `PlotCommands.delete/archive/update` and the import's plot path.
- `PlotCatalog` gains `street` in `PlotFacts`, `inStreetOrder(ids)` (Ukrainian collation, nulls last, then number sort
  key), `findAndLockShared(id)`, `findActiveByNumbers(numbers)`.

**Authorization.** Reads `METERS_VIEW`; meter writes `METERS_VIEW and METERS_MANAGE`; round, entry, correction and
deletion `READINGS_ENTER`; approvals list, approve, reject `READINGS_APPROVE`; `GET /plots/{id}/meters` and `POST
/plots/{id}/readings` without `@PreAuthorize`: `METERS_VIEW` for any plot (GET only) or the plot's current owner,
otherwise 404, operator 403 (`ExpectedAmountQueries.mayAsk` pattern).

**Audit** (entity types `METER`, `METER_READING`; codes and parameters per audit README): `METER_REGISTERED`,
`METER_UPDATED`, `METER_REMOVED`, `METER_REPLACED` (old meter; `removedOn`, `finalReading`, `replacementMeter`),
`METER_DELETED`, `METER_PARENT_CHANGED` (`parent`, `effectiveFrom`), `READING_ENTERED`, `READING_SUBMITTED`,
`READING_REPLACED`, `READING_CORRECTED`, `READING_DELETED`, `READING_APPROVED`, `READING_REJECTED` (`reason`). Labels
are user content (serial or name); values plain decimals and ISO dates.

### 2.3 API contract (tags `meters`, `readings`, `meter-tree`)

- `MeterValue` string `^\d{1,11}(\.\d{1,3})?$` (field code `METER_VALUE_INVALID`); enums `MeterKind`, `MeterResource`,
  `ReadingStatus`, `ReadingSource`, `ReadingWarning` (`UNUSUAL_CONSUMPTION`), `ExpectedChargeStatus` + `NO_METER`.
- `GET /meters` (filters resource, kind, street, noReadingSince), `POST /meters`, `GET/PUT/DELETE /meters/{id}`,
  `POST /meters/{id}/remove` (`removedOn`, `finalReading`), `POST /meters/{id}/replace` (date, final reading, new serial,
  initial reading), `PUT /meters/{id}/parent` (`parentMeterId`, `effectiveFrom`), `GET /meters/{id}/readings` (readings
  with intervals and status).
- `GET /meter-tree?from&to` (recursive `MeterTreeNode`: meter, slices with `from`, `to`, start/end `ValueAt`,
  consumption or unknown with `lastReadingOn`, children).
- `GET /readings/round?street&noReadingSince&resource`; `POST /readings` batch (≤ 100 rows; 200 with per-row
  `SAVED|REJECTED`, reading, `supersededReadingId`, warnings, error `{code, field, params}`); `PUT /readings/{id}` and
  `DELETE /readings/{id}`; `GET /readings?status=PENDING`; `POST /readings/{id}/approve`, `POST /readings/{id}/reject`
  (`reason`).
- `GET /plots/{id}/meters`; `POST /plots/{id}/readings` (`meterId`, `takenOn`, `value`).
- Problems: 409 `METER_ACTIVE_EXISTS`, `METER_COMMON_LAND`, `METER_HAS_READINGS`, `METER_TREE_INVALID`,
  `METER_HAS_CHILDREN`, `METER_REMOVED`, `METER_HAS_PENDING_READINGS`, `PLOT_HAS_METER`, `READING_OUT_OF_ORDER`,
  `READING_DATE_IN_FUTURE`, `READING_DATE_OUTSIDE_METER`, `READING_IN_USE`, `READING_STATUS_INVALID`; 400
  `VALIDATION_FAILED`.
- `ImportCounts.meters`, `ImportSheet.METERS`; import problem `METER_ACTIVE_EXISTS` (with plot).
- Tariffs: `plotCount` for meter-based tariffs = plots in scope with an active meter of the resource; plot items
  `NO_METER` (no amount, not in the total) or `BY_READINGS`.

### 2.4 Import (extends 008)

Sheet `Лічильники` / `Meters`, optional; columns `plot_number`, `resource`, `serial`, `installed_on`, `initial_reading`
(all required); vocabulary in `header-aliases.properties` (sheet names, headers, resource words електроенергія/
електрика/вода, electricity/water); template bundles and the example row in both languages. `MetersSheet`,
`MeterColumn`, `ImportSheet.METERS`, `PlanCounts.meters`, `PlanDigest` lines. Matching: plot from the file's plots plan
or `PlotCatalog.findActiveByNumbers`; `MeterImport.plan` → NEW / UNCHANGED (same plot, resource, serial) / problem
`METER_ACTIVE_EXISTS`; common land `PLOT_COMMON_LAND`; duplicates `DUPLICATE_IN_FILE`; ≤ 2 000 rows. Commit order:
lock plots, lock meters, members; apply members, plots, meters; one `AuditBatch`.

### 2.5 Web

- Routes: `/readings` (READINGS_ENTER) ReadingsRoundPage; `/meters`, `/meters/:id` (METERS_VIEW); `/readings/approvals`
  (READINGS_APPROVE); `/settings/meter-tree` (METERS_MANAGE); `/settings` allowed with SETTINGS_MANAGE or
  METERS_MANAGE, the page showing the sections the user may use.
- Menu: "Покази", "Лічильники", "На підтвердження"; "Ділянки" shown with PLOTS_VIEW or when the user has no meter
  permission; "Налаштування" with SETTINGS_MANAGE or METERS_MANAGE. HomeRedirect: PLOTS_VIEW → /plots, READINGS_ENTER →
  /readings, METERS_VIEW → /meters, else /account.
- `packages/shared`: `parseMeterValue` / `formatMeterValue` (string-only, ≤ 3 decimals, locale separators), units from
  the catalogs ("кВт·год", "м³" / "kWh", "m³").
- Components: `ReadingDateField` (date input + the quiet hint from one catalog template with the locale date; today from
  `kyivToday`), round rows as a list (cards at phone width, `inputMode="decimal"`, per-row save, Enter moves on, the
  saved row shows its interval and warnings; no client arithmetic), MetersPage (filters in the URL), MeterPage (history,
  intervals, status badges, replace/remove/correct/delete dialogs), ApprovalsPage (reason required), MeterTreePage
  (date range, nested tree, "розрахунковий" badge, "немає показу після <дата>", move dialog with effective date),
  PlotMeterPanel, MyMetersPanel with SubmitReadingDialog.
- AuditPage: METER and METER_READING labels and values (meter values via `formatMeterValue`, dates, status enums).
  Import panels: `meters` counts. Tariffs: `NO_METER` text ("немає лічильника" / "no meter").
- All texts in uk + en catalogs; user content marked; language-purity spec extended to the new screens.

---

## 3. Impact and Risks

- **Plot guard.** New veto in three plot paths and the import's plot update; tests that delete demo plots must not use
  plots with demo meters.
- **Permission ripple** 11 → 15 (tests, e2e role counts, `DemoAccessChecks`, the Електрик role now has 2 permissions).
- **`PlotFacts.street`** ripples into tariffs tests.
- **Flush order** against the partial unique index on approved readings; tested explicitly.
- **Demo dates relative to today**: tests assert relative facts only; a long-lived dev DB ages (reset with `down -v`).
- **Round size**: no paging; rows memoised; phone-width cards; `responsive.spec.ts` covers `/readings`.
- **Rounding**: interpolated display values are rounded once to 3 decimals (ADR 0014); consumption uses exact values.
- **011 contract**: `ReadingUsage` is range-based; 011 must lock the meter row `FOR SHARE` when it charges.
- **Coverage**: floor raised to the measured level at the end.

---

## 4. Testing Strategy

- **Unit:** `MeterValueTests`, `QuantityTests`, `ConsumptionCalculatorTests` (every row of the functional spec §2.5
  table, plus clipping, wrap-around, interpolation at both ends, unusual-check edges), `ReadingValidatorTests`.
- **Integration (Testcontainers):** meters CRUD/remove/delete/replace incl. wrap-around, plot guards, tree moves and past
  ranges, readings entry/batch/supersede/correct/delete/approve/reject, member submission and visibility, round list
  and filters, ordering and date errors, unusual warning, tariffs `NO_METER` and plot counts, import sheet uk/en,
  allowed and denied per endpoint, audit, isolation per table, constraints, seed and targeted permission inserts,
  `DemoMeterData`, `ModularityTests`.
- **Web (vitest):** meter value parse/format, `ReadingDateField` hint, round page, menu and landing, approvals, tree,
  my meters, audit labels, import counts, catalog parity.
- **e2e:** electrician round (default date, changed date, interval), chairman approves a member reading, member sees only
  own meter, treasurer read-only, phone width, language purity on the new screens; screenshots
  `docs/screenshots/010-meters-and-readings-*.png`.
- **Commands:** `./mvnw -B test -Dtest=<Class>`, `make check`, `make e2e`.

---

## Assumptions (pipeline)

- `MeterValue` and the calculation live in the meters module root, not in `money`; ADR 0014 records exact rational
  arithmetic and the single display rounding.
- `ReadingUsage` is range-based (`meterId, from, to`) because 011 charges by date range; inserting a reading between two
  charged readings is refused once 011 says they are used.
- A reading correction creates a new row superseding the old one (history kept); deletion is a hard delete, audited.
- The batch entry is per row (each row its own transaction); the UI saves row by row.
- The round has no paging; plot meters in street and number order (Ukrainian collation), then other meters by name.
- A non-plot meter's replacement takes over its children.
- Demo dates are relative to the current date.
- `GET /readings?status=PENDING` is added for the approvals screen (missing from the feature doc's API list).
