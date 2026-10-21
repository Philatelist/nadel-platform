# Technical Specification: Charge types and tariffs

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Completed
- **Author(s):** Alex Usov (pipeline; inputs from spring-backend, postgres-database and react-frontend)

---

## 1. High-Level Technical Approach

- A new Spring Modulith module **`app.nadel.tariffs`** owns charge types, tariffs, versions and scopes, the
  endpoints of tag `tariffs` (including `GET /plots/{id}/tariffs`) and the **expected amount calculation**.
- A new small module **`app.nadel.money`** holds the `Money` value object (the one place that rounds, HALF_UP to 2
  places, as money-rules §1 already claims) and the `Rate` value object (scale ≤ 4, never rounded). Ledger (011)
  reuses both. ADR 0013 records it.
- The plots module gains a public read facade **`plots.PlotCatalog`** (area, kind, archived, number), so tariffs never
  reads the `plot` table. Member visibility uses the existing public `identity.OwnedPlotsSource`.
- `ChargeCalculator` in the tariffs module root is pure (no Spring, no DB) and public, so 011 calls the same rule.
- V10 adds four tenant tables with RLS, a `seed_charge_types(tenant)` function, two permissions (`TARIFFS_VIEW`,
  `TARIFFS_MANAGE`) and demo tariffs.
- Contract first: `api/openapi.yaml` (new tag `tariffs`, schemas `Rate`, `ChargeType`, `Tariff`, `TariffVersion`,
  `TariffScope`, `PlotTariffs` …), then `make gen`.
- Web: pages Тарифи, tariff card, new tariff, charge types; a "Тарифи" panel on the plot card; "Що мені
  нараховуватимуть" on the account page; audit log labels. Display only: the client never computes money.

Dependencies: `tariffs → plots (PlotCatalog), identity, money, problem, audit`. `plots` never depends on `tariffs`
(no cycle); the plot card loads the tariffs section with its own request.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Database (`V10__charge_types_and_tariffs.sql`)

Every table: `tenant_id uuid not null references tenant`, `id uuid` PK, `UNIQUE (tenant_id, id)` as `*_tenant_id_uq`,
composite FKs on `(tenant_id, …)`, RLS enabled with the `tenant_isolation` policy (`USING` + `WITH CHECK` on
`current_tenant_id()`), an index on `tenant_id`, text + named CHECK constraints (no enum types), `created_at`/`updated_at`
with `now()` defaults.

| Table | Key columns and rules |
|---|---|
| `charge_type` | `code` text null (MEMBERSHIP, ELECTRICITY, LAND_TAX, WATER — seeded only; unique `(tenant_id, code)` where not null); `name` text not null (seeded: English default), `name_customized` boolean (role pattern of V4); `description` text null ≤ 500; `active` boolean; `position` int (no unique, ordered by `(position, id)`); `archived_at` timestamptz null. Unique index `(tenant_id, lower(name)) WHERE archived_at IS NULL`. `REVOKE DELETE, TRUNCATE` from `nadel_app`. |
| `tariff` | `charge_type_id` → `charge_type` (RESTRICT); `name` text 1–100; `scope_kind` text `ALL_PLOTS`/`SELECTED_PLOTS` default `ALL_PLOTS`. `REVOKE DELETE, TRUNCATE`. |
| `tariff_version` | `tariff_id` → `tariff` (RESTRICT); `valid_from` date; `basis` (`PER_PLOT`, `PER_AREA_SOTKA`, `PER_KWH`, `PER_M3`, `FIXED_ONE_TIME`); `frequency` (`MONTHLY`, `QUARTERLY`, `YEARLY`, `ONE_OFF`); `rate numeric(14,4) >= 0`; `due_date` date null; `note` text null ≤ 500. `tariff_version_valid_from_uq UNIQUE (tenant_id, tariff_id, valid_from)`; `tariff_version_one_off_ck`: `(basis = FIXED_ONE_TIME) = (frequency = ONE_OFF)` and `(frequency = ONE_OFF) = (due_date IS NOT NULL)`. V10 made it append-only (`REVOKE UPDATE, DELETE, TRUNCATE` from `nadel_app`, V3 `audit_entry` pattern); **amended 2026-10-21:** `V11__tariff_version_correction.sql` grants `UPDATE, DELETE` back (a version is corrected or deleted until a charge uses it; the use check guards it) and keeps `TRUNCATE` revoked; RLS still isolates both statements per cooperative. |
| `tariff_scope_plot` | PK `(tariff_id, plot_id)`; `tariff_id` → `tariff`; `(tenant_id, plot_id)` → `plot(tenant_id, id)` **ON DELETE CASCADE** (a plot can be deleted only while it has no history — 006; a scope row must not block that); `kind` `SELECTED`/`EXCLUDED`; `reason` text: `(kind = EXCLUDED) = (reason IS NOT NULL)`, trimmed 1–500. Index `(tenant_id, plot_id)`. Rows are replaced on scope change (DELETE stays granted; the audit log keeps history). |

Rules that span tables live in the service: entry kind matches `tariff.scope_kind`; no common land in a scope.

**Seeding.**
- `seed_charge_types(p_tenant uuid)`: four rows with deterministic ids `md5(tenant || ':CHARGE_TYPE:' || code)::uuid`,
  positions 1–4, WATER inactive, English names, `ON CONFLICT DO NOTHING`; `GRANT EXECUTE` to `nadel_app`.
- V10 runs `SELECT seed_charge_types(id) FROM tenant`.
- `seed_base_roles` is re-created (`CREATE OR REPLACE`, same signature, Treasurer description unchanged) with the full
  matrix plus `TARIFFS_VIEW`/`TARIFFS_MANAGE` for Chairman and `TARIFFS_VIEW` for Treasurer, and it calls
  `seed_charge_types(p_tenant)` at the end, so a new cooperative gets both from one call (`R__demo_data.sql:88` already
  calls it for every tenant).
- Existing cooperatives get the new permissions by a **targeted insert** (Chairman: 2 rows, Treasurer: `TARIFFS_VIEW`),
  never by re-running `seed_base_roles`, which would restore permissions a chairman removed from a Treasurer role.

**Demo data** (`R__demo_data.sql`, demo tenant only, fixed ids, English text): membership 50.0000 per sotka yearly from
2026-01-01 (all plots, one exclusion of an unowned plot, reason "Exempted by the general meeting"); land tax 35.0000 per
sotka yearly from 2026-01-01; electricity 4.3200 per kWh monthly from 2026-01-01; a custom "Road repair" type with a
fixed one-time 1200.0000 due 2026-12-01. The other tenant gets only the seeded types.

### 2.2 Backend

**`app.nadel.money`** (public root): `Money` (scale 2; `of(BigDecimal)` rounds HALF_UP; `ofQuotient(numerator,
denominator)` divides exactly once with HALF_UP to 2; `ZERO`, `plus`, `parse`, `toString()` plain) and `Rate` (non-negative,
≤ 10 integer digits, ≤ 4 decimals, normalised to scale 4 without rounding; `parse` rejects anything else). Unit tested.

**`app.nadel.plots.PlotCatalog`** (public interface; `plots/internal/JpaPlotCatalog`): `record PlotFacts(UUID id,
String number, boolean commonLand, boolean archived, BigDecimal areaM2)`; `Optional<PlotFacts> find(UUID)`,
`Map<UUID, PlotFacts> findAll(Collection<UUID>)`, `long countChargeable()` (active, not common land). Runs in the
caller's transaction under RLS.

**`app.nadel.tariffs`** (public root): `Basis`, `Frequency`, `VersionTerms(validFrom, basis, frequency, Rate rate,
dueDate)` (constructor enforces the ONE_OFF/FIXED/due-date rule), `ExpectedCharge` (sealed: `Amount(Money)`,
`ByReadings`), `ChargeCalculator`. `internal`: entities and repositories, `ChargeTypeCommands`/`Queries`,
`TariffCommands`/`Queries`, `ExpectedAmountQueries`, `TariffAuditPublisher`, `ChargeTypesController`,
`TariffsController`, `TariffProblems`.

**Authorization.** Reads `hasAuthority('PERM_TARIFFS_VIEW')`; writes `PERM_TARIFFS_VIEW and PERM_TARIFFS_MANAGE`
(plots pattern). `GET /plots/{id}/tariffs` has no `@PreAuthorize`: `ExpectedAmountQueries` allows a caller with
`TARIFFS_VIEW`, or a member whose `OwnedPlotsSource.currentPlotsOf(memberId)` contains the plot; anyone else (and an
unknown or other-tenant plot) gets 404 `NOT_FOUND`; the platform operator 403 (`CooperativeCallers` pattern). Common land
and archived plots return 200 with no items and total `0.00`.

**Version correction (amendment 2026-10-21).** `TariffCommands.updateVersion` / `deleteVersion` lock the tariff row `FOR
UPDATE` and its charge type `FOR SHARE`, load the version (it must be of that tariff), ask the use check, then write.
The use check is the public port `app.nadel.tariffs.TariffVersionUsage.isUsed(tenantId, versionId)`; the tariffs module
injects `List<TariffVersionUsage>` and the version is used when any bean says so. `internal/NoChargesYetUsage` is the
default and answers `false`. Feature 011's ledger implements the port in its own module (ledger depends on tariffs, never
the reverse) and locks the tariff row `FOR SHARE` when it writes a charge. `TariffVersionRecord` is no longer
`@Immutable` (`correct(terms, note)`). An unchanged `PUT` writes nothing and no audit entry. `validTo` stays derived, so
neighbours need no write.

**Locks.** Creating a tariff or version locks its charge type `FOR SHARE`; archiving `FOR UPDATE`; reorder locks the
tenant's non-archived charge types `FOR UPDATE` and requires the full id set (else 409 `CHARGE_TYPE_ORDER_STALE`).

**Name uniqueness.** Unique index for stored names; the service also rejects a name equal (case-insensitive) to the
Ukrainian or English default of a non-archived, non-customised seeded type → 409 `CHARGE_TYPE_NAME_TAKEN`.

**Audit** (`AuditEvent` in the transaction, outbox as ADR 0007): entity types `CHARGE_TYPE`, `TARIFF`; actions
`CHARGE_TYPE_CREATED`, `CHARGE_TYPE_UPDATED` (changed fields among `name`, `description`, `active`),
`CHARGE_TYPE_REORDERED` (one per type whose `position` changed), `CHARGE_TYPE_ARCHIVED`, `TARIFF_CREATED` (name, charge
type, first version fields), `TARIFF_VERSION_ADDED` (`validFrom`, `basis`, `frequency`, `rate`, `dueDate`, `note`),
`TARIFF_VERSION_UPDATED` (changed fields only, old to new, among `validFrom`, `basis`, `frequency`, `rate`, `dueDate`, `note`), `TARIFF_VERSION_DELETED` (old values), `TARIFF_SCOPE_CHANGED` (`scope`, `selectedPlots` as sorted plot numbers), `TARIFF_EXCLUSION_ADDED`/`REMOVED` (`plot`,
`reason`). Reasons and notes are never logged.

### 2.3 Calculation (`ChargeCalculator.calculate(List<VersionTerms>, BigDecimal areaM2, YearMonth month)`)

1. Sort versions by `validFrom`; `validTo` = next `validFrom` − 1 day, or open.
2. For each version, `days` = overlap with the month (≥ 0); `D` = days in month.
3. No version with `days > 0` → empty (tariff not listed). Any overlapping version with `PER_KWH`/`PER_M3` →
   `ByReadings`.
4. Otherwise one exact numerator over the common denominator `D × 12` (rates are never divided):
   - recurring: `rate × units × days × f`, `units` = 1 (`PER_PLOT`) or `areaM2 / 100` exact (`PER_AREA_SOTKA`), `f` =
     12 monthly, 4 quarterly, 1 yearly;
   - `FIXED_ONE_TIME`: `rate × D × 12` when its due date is in the month and that version is valid on the due date; never
     split by days.
5. `Money.ofQuotient(numerator, D × 12)` — the single HALF_UP rounding.

Checks: 33.3333 × 7 × 30 / 360 = 19.444425 → 19.44; (50·6·15 + 60·6·15)/360 = 27.50; 300 × 30 × 4 / 360 = 100.00.

`ExpectedAmountQueries` filters archived and inactive charge types, applies the scope (all plots minus exclusions →
`EXCLUDED` item with the reason; selected plots), and sums the amounts for the total. The month defaults to the current
Kyiv month (`Clock`).

### 2.4 API contract (tag `tariffs`)

- `Rate`: string, pattern `^\d{1,10}(\.\d{1,4})?$` (a violation is field code `RATE_INVALID`). `Basis`, `Frequency`,
  `TariffScopeKind` enums. `Permission` gains `TARIFFS_VIEW`, `TARIFFS_MANAGE`; `AuditEntityType`/`AuditAction` gain the
  values of 2.2.
- `GET /charge-types` → `ChargeType[]` (`id`, `code?`, `name`, `nameCustomized`, `description?`, `active`, `position`).
- `POST /charge-types` (`name`, `description?`) → 201; `PUT /charge-types/{id}` (`name`, `description?`, `active`);
  `POST /charge-types/{id}/archive` → 204; `PUT /charge-types/order` (`ids[]`) → 204.
- `GET /tariffs` → `TariffSummary[]` (`id`, `name`, `chargeTypeId`, current version or the first future one, `plotCount`
  — null for meter-based tariffs, `scopeKind`).
- `POST /tariffs` (`chargeTypeId`, `name`, `firstVersion`) → 201 `Tariff`; `GET /tariffs/{id}` → `Tariff` (versions newest
  first with derived `validTo` and `pastDated`, scope with selected plots and exclusions with plot number and reason).
- `POST /tariffs/{id}/versions` (`validFrom`, `basis`, `frequency`, `rate`, `dueDate?`, `note?`) → 201.
- `PUT /tariffs/{id}/versions/{versionId}` (body `TariffVersionCreate`, the same fields and rules) → 200 `TariffVersion`
  (with `validTo`, `pastDated`); `DELETE /tariffs/{id}/versions/{versionId}` → 204. Both `TARIFFS_VIEW` and `TARIFFS_MANAGE`.
  409 `TARIFF_VERSION_IN_USE`, `TARIFF_LAST_VERSION` (delete of the only version), `TARIFF_VERSION_DATE_TAKEN`,
  `CHARGE_TYPE_ARCHIVED`; 404 for an unknown or foreign tariff and a version of another tariff. Added 2026-10-21.
- `PUT /tariffs/{id}/scope` (`kind`, `plotIds[]`, `exclusions[{plotId, reason}]`) → 200 `Tariff` (full replace).
- `GET /plots/{id}/tariffs?month=YYYY-MM` → `PlotTariffs` (`month`, `items[]`: `tariffId`, `tariffName`, `chargeTypeId`,
  `chargeTypeCode?`, `chargeTypeName`, `chargeTypeNameCustomized`, `basis`, `frequency`, `rate`, `status`
  `AMOUNT|BY_READINGS|EXCLUDED`, `amount?` Money, `reason?`; `total` Money). Bad month → 400.
- Problems: 409 `CHARGE_TYPE_NAME_TAKEN`, `TARIFF_VERSION_DATE_TAKEN`, `CHARGE_TYPE_ARCHIVED`,
  `CHARGE_TYPE_ORDER_STALE`; 400 `VALIDATION_FAILED` with field codes `RATE_INVALID`, `FREQUENCY_BASIS_MISMATCH`,
  `REQUIRED` (`dueDate`, `exclusions[i].reason`), `TARIFF_COMMON_LAND`, `PLOT_ARCHIVED`, `UNKNOWN_PLOT`.

### 2.5 Web

- Routes (lazy, `RequirePermission TARIFFS_VIEW`): `/tariffs` TariffsPage, `/tariffs/new` TariffFormPage (also
  `TARIFFS_MANAGE`), `/tariffs/:id` TariffPage, `/charge-types` ChargeTypesPage. Menu item "Тарифи" before "Імпорт".
- `components/tariffs/`: `VersionFields` (shared by new tariff and new version; FIXED forces ONE_OFF and shows the due
  date), `ScopeEditor` + `PlotPicker` (MemberPicker pattern over `GET /plots`, common land filtered), `ExclusionList`,
  `VersionHistory`, `ChargeTypeDialog`, `PlotTariffsPanel` (month picker, rows, total), `MyChargesPanel`
  (account page, one block per owned plot, current month), shared `TariffAmountRows`.
- `components/ui/MonthField` (`<input type="month">`, value `YYYY-MM`).
- `packages/shared`: `formatRate` (string only, significant decimals but at least 2 — "4.3200" → "4,32" — locale separator; storage and API keep 4) and a rate input check; no
  `Number()` anywhere for rates or amounts. Past-date warning: `validFrom < kyivToday()` (string comparison), plus the
  server's `pastDated` on saved versions.
- i18n (uk + en): `nav.tariffs`, `tariffs.*`, `tariffs.basis.*`, `tariffs.frequency.*`, `chargeTypes.*`,
  `chargeTypes.defaultNames.{MEMBERSHIP,ELECTRICITY,LAND_TAX,WATER}` (shown while `nameCustomized` is false),
  `account.myCharges`, the new `problem.*` and field codes, `permission.TARIFFS_*`, `audit.entityType.*`,
  `audit.action.*`, `audit.fields.*`.
- AuditPage: CHARGE_TYPE → `/charge-types`, TARIFF → `/tariffs/{id}` through an `entityHref` map; value formatters for
  rate, basis, frequency, dates and active.

---

## 3. Impact and Risk Analysis

- **Permissions ripple.** Two new permissions change counts and matrices in `RolesTests`, `SeedBaseRolesTests`,
  `BaseRolesMatrixTests`, `DemoAccessChecks`, `PermissionCatalog` (exhaustive switch), `BaseRoles`, the contract enum,
  i18n, `web/e2e/role-names.spec.ts` (count 9 → 11), ADR 0009 and `architecture.md`. Done in one slice.
- **Re-seeding existing roles.** Targeted inserts only (see 2.1), so a chairman's removal of a Treasurer permission is
  not undone.
- **Rounding.** All arithmetic is exact `BigDecimal` with one `Money.ofQuotient`; worked examples are unit tests;
  `numeric(14,4)` would silently round a fifth decimal, so the boundary rejects it first (pattern + `Rate.parse`).
- **One rule for 011.** `ChargeCalculator` is public and documented in money-rules §4.
- **Seeded-name collisions.** Service check against both default names.
- **Plot deletion.** Scope rows cascade on plot delete (006 allows deleting a plot only without history).
- **e2e on the shared dev DB.** Tariffs cannot be deleted (a version only until a charge uses it): specs use a unique custom charge type per run
  and archive it in `finally`, which removes its tariffs from every plot; assertions filter by their own names. Demo
  tariffs change no counts or statuses (nothing is charged).
- **Coverage.** Floor 0.97; raised to the measured level at the end.

---

## 4. Testing Strategy

- **Unit:** `MoneyTests`, `RateTests`; `ChargeCalculatorTests` with every row of the functional spec §2.4 table plus
  edge cases (first version mid-month, version starting after the month, quarterly with a split, FIXED due in another
  month, meter-based mixed month, leap February); `VersionTermsTests`.
- **Integration (Testcontainers):** charge types (seed for existing and new tenants, add, rename incl. default-name
  collision, reorder, archive, active), tariffs and versions (duplicate date, ONE_OFF rules, past date, archived type),
  scopes (exclusions, selected, common land, switch clears rows), `GET /plots/{id}/tariffs` (amounts, excluded,
  by-readings, common land, archived plot, member own vs other plot, treasurer, operator 403), allowed and denied
  tests for every endpoint, audit entries, isolation per table (RLS, cross-tenant FKs), grants (V11: versions may be updated and deleted, never truncated; RLS isolates both), version correction (`TariffVersionCorrectionTests`: edit, delete, use check through a test bean, last version, audit),
  `ModularityTests`, seed matrix tests.
- **Web (vitest):** formatRate, rate check, past-date check, VersionFields, ScopeEditor, PlotTariffsPanel,
  MyChargesPanel, pages, Layout menu gating, AuditPage links, catalog parity.
- **e2e (Playwright):** `tariffs.spec.ts` — the chairman creates a membership tariff and sees the expected amount on a
  plot card; a member sees only their own plot's amounts; the treasurer sees no actions. Acceptance specs in the final
  slice. Screenshots into `docs/screenshots/009-charge-types-and-tariffs-*.png`.
- **Commands:** `./mvnw -q -Dtest=<Class> test` (single class), `make check` (full), `make e2e`.

---

## Assumptions (pipeline)

- `app.nadel.money` is a separate module (ADR 0013), not part of tariffs, because ledger (011) and payments reuse it.
- Seeded charge type names follow the role pattern: stored English default + `name_customized`; the client shows the
  catalog name while not customised.
- New cooperatives get charge types through `seed_base_roles` (the one seed entry point); no trigger on `tenant`.
- Inactive or archived charge types' tariffs are omitted from expected amounts (not shown as "not charged").
- A month where an overlapping version is meter-based shows "by readings" for the whole tariff.
- Common land and archived plots: 200 with no items.
- Tariff rename is not in the requirement's API and is not built. Version correction and deletion were added by the owner on 2026-10-21.
- `GET /plots/{id}/tariffs` lives in the tariffs module (tag `tariffs`) to keep plots free of a tariffs dependency.
- Scope rows cascade on plot delete.
