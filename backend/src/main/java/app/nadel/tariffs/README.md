# tariffs

What the cooperative charges for and how much (feature 009). Delivered so far: **charge types** (slice 1) and
**tariffs with versions** (slice 2) and **scopes** (slice 3); the expected amount of a plot follows in a later slice.

Tables (all under RLS `tenant_isolation`, an index on `tenant_id`, composite foreign keys on `(tenant_id, id)`; V10):

| Table | What it holds |
|---|---|
| `charge_type` | What is charged for: the four seeded types (`code` MEMBERSHIP, ELECTRICITY, LAND_TAX, WATER) and custom ones |
| `tariff` | A tariff of one charge type, with its `scope_kind` (`ALL_PLOTS` or `SELECTED_PLOTS`; new tariffs are `ALL_PLOTS`) |
| `tariff_version` | Append-only rates by date (`valid_from`, basis, frequency, `rate numeric(14,4)`, due date, note) |
| `tariff_scope_plot` | Selected plots (`SELECTED`), or exclusions with a reason (`EXCLUDED`); written by `PUT /tariffs/{id}/scope`; cascades when a plot is deleted |

All four tables exist in V10 (one migration, one isolation test). `nadel_app` has no `DELETE`/`TRUNCATE` on `charge_type` and `tariff`, and
no `UPDATE`/`DELETE`/`TRUNCATE` on `tariff_version`.

## Charge types

- `seed_charge_types(tenant)` writes the four types with deterministic ids `md5(tenant || ':CHARGE_TYPE:' || code)::uuid`,
  positions 1 to 4, English names, Water inactive, `name_customized = false`, `ON CONFLICT DO NOTHING` (never
  overwrites what a chairman changed). `seed_base_roles(tenant)` calls it, so a new cooperative gets the types and the
  roles from one call; V10 seeds every existing cooperative.
- The stored name of a seeded type is the English default. While `nameCustomized` is false clients show their own
  localised default name (the pattern of the base role names); renaming sets the flag for good. A custom type is always
  customised.
- A type is archived, never deleted: `archived_at` hides it from the list, frees its name and blocks changes
  (409 `CHARGE_TYPE_ARCHIVED`, also for archiving it again). An inactive type stays in the list.
- The list is ordered by `(position, id)`; `position` has no unique constraint. A new type goes to `max(position) + 1`
  over all types, archived ones included.

## Endpoints

Tag `charge-types`:

| Endpoint | Operation | Who |
|---|---|---|
| `GET /charge-types` | `listChargeTypes` | `TARIFFS_VIEW` |
| `POST /charge-types` | `createChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /charge-types/{id}` | `updateChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `POST /charge-types/{id}/archive` | `archiveChargeType` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /charge-types/order` | `reorderChargeTypes` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |

Tag `tariffs` (`TariffsController`):

| Endpoint | Operation | Who |
|---|---|---|
| `GET /tariffs` | `listTariffs` | `TARIFFS_VIEW` |
| `POST /tariffs` | `createTariff` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `GET /tariffs/{id}` | `getTariff` | `TARIFFS_VIEW` |
| `POST /tariffs/{id}/versions` | `addTariffVersion` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /tariffs/{id}/scope` | `updateTariffScope` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |

The platform operator has no permissions and gets 403 `PERMISSION_DENIED`; a member without the permissions gets 403.
The cooperative always comes from the token. A type of another cooperative or an unknown id is 404 (RLS).

Writes (`ChargeTypeCommands`, one transaction each, the audit event is published in the same transaction):

- Names are trimmed (1 to 100 characters, the contract's `maxLength` applies to the raw text) and unique among
  non-archived types ignoring letter case: the unique index `charge_type_tenant_name_uq` is the guarantee, and a
  pre-check gives the friendly 409 `CHARGE_TYPE_NAME_TAKEN` with `errors[{field: name}]`. The pre-check also refuses a
  name equal (ignoring case) to the English or Ukrainian default of a non-archived seeded type that was never renamed,
  because clients show that default to the user. Once a seeded type is renamed its defaults are free again. The Ukrainian
  defaults can race with a concurrent request (only the index is race-free); that is accepted.
- `PUT` replaces name, description and `active`. A blank description is cleared. Sending back the default name a client
  displays for a never-renamed seeded type (either language) is not a rename: the stored name and `nameCustomized` stay.
  A `PUT` that changes nothing is accepted, writes no audit entry and keeps `updated_at`.
- `PUT /charge-types/order` takes `{ids}`: exactly the non-archived types, each once. The rows are locked
  `FOR UPDATE` in list order first, so the check cannot race with an archive or another reorder; any other set is 409
  `CHARGE_TYPE_ORDER_STALE` and nothing changes. Positions are renumbered from 1; the current order again changes
  nothing. Changing or archiving one type locks its row `FOR UPDATE`.

## Tariffs and versions

- A tariff is created together with its first version (`TariffCommands.create`, one transaction) and applies to all
  plots until its scope is changed. Tariffs are never renamed or deleted; versions are never edited or deleted (the
  entity is `@Immutable`, the application role has no `UPDATE` on `tariff_version`). A change of rate is a new version.
- `VersionTerms` (module root) holds the terms of a version and enforces `FIXED_ONE_TIME` iff `ONE_OFF` iff a due date.
  The controller maps its violations to field codes of `400 VALIDATION_FAILED`: `frequency` `FREQUENCY_BASIS_MISMATCH`,
  `dueDate` `REQUIRED` (a one-time amount without a due date) and `dueDate` `INVALID` (a due date with any other
  basis). A rate that is not `^\d{1,10}(\.\d{1,4})?$` is `rate` `RATE_INVALID`: on the API by the contract's pattern
  (`ValidationProblems` maps a pattern on a field called `rate` or `firstVersion.rate`), in the service by `Rate.parse`.
  The rate is never rounded (ADR 0013), stored as `numeric(14,4)` and returned with four decimals (`"4.3200"`). Fields
  of a new tariff's first version are prefixed `firstVersion.`. Bean validation runs first, so a bad rate is reported alone;
  the service then reports the frequency and due date problems together.
- The charge type is locked `FOR SHARE` when a tariff or a version is added; an archived one is 409
  `CHARGE_TYPE_ARCHIVED`, an unknown or foreign one is 404. A second version on the same date violates
  `tariff_version_valid_from_uq`, which `problem.ConstraintViolations` maps to 409 `TARIFF_VERSION_DATE_TAKEN`
  (`errors[{field: validFrom}]`).
- Reading: `validTo` of a version is the day before the next version starts (derived on every read, never stored);
  `pastDated` is `validFrom` before today on the Kyiv `Clock`. A version dated before an existing later one is fine:
  the answer to `POST .../versions` carries the `validTo` derived from the versions after it.
- The list (`GET /tariffs`) holds the tariffs of non-archived charge types ordered by charge type position, then name
  (ignoring case), each with the version valid today, else the earliest future one. `plotCount` for `ALL_PLOTS` is
  `plots.PlotCatalog.countChargeable()`; for `SELECTED_PLOTS` the selected rows whose plots are active and not common
  land (plots are read through `PlotCatalog.findAll`, never from the plot table). It is absent when the shown version is
  `PER_KWH` or `PER_M3` (such a tariff applies to the plots with a meter). For `ALL_PLOTS` the active, non-common
  plots among the exclusions are subtracted.
- `GET /tariffs/{id}` returns the tariff with its charge type (an archived one too, so the history stays readable),
  its scope (`selectedPlots`, `exclusions` with plot number, `archived` and reason, in natural order of the number) and
  the same `plotCount` as the list. A tariff of another cooperative or an unknown id is 404 (RLS). `TariffAssembler`
  builds the card (also the answer of `POST /tariffs` and `PUT .../scope`) and the counts; plots are read only through
  `PlotCatalog`.

## Scopes

- A new tariff is `ALL_PLOTS` without exclusions. `PUT /tariffs/{id}/scope` replaces the whole scope in one
  transaction: `{kind, plotIds[], exclusions[{plotId, reason}]}`. `plotIds` goes only with `SELECTED_PLOTS` and
  `exclusions` only with `ALL_PLOTS` (the other list present and not empty is field code `INVALID` on `plotIds` or
  `exclusions`); each list holds at most 5000 entries (`TOO_LONG`). Switching the kind removes the rows of the other
  kind.
- Order of checks: the tariff row is locked `FOR UPDATE` (404 when missing or foreign), then its charge type
  `FOR SHARE` (409 `CHARGE_TYPE_ARCHIVED`), then all field problems are collected into one 400 `VALIDATION_FAILED`:
  `plotIds[i]` or `exclusions[i].plotId` is `UNKNOWN_PLOT` (no such plot in the cooperative, which includes other
  cooperatives' plots), `TARIFF_COMMON_LAND`, `PLOT_ARCHIVED` or `DUPLICATE` (the later of two equal ids);
  `exclusions[i].reason` is `REQUIRED` when blank after trimming and `TOO_LONG` over 500 characters. A plot that is
  already in the stored scope with the same kind may stay although it was archived since, so the card can be saved
  again unchanged; a newly added archived plot is refused.
- Stored rows are never edited. `TariffScopeChange` diffs the stored rows against the request: rows that no longer hold
  are deleted, new ones inserted (an exclusion with a new reason is deleted and inserted), the tariff's `scope_kind`
  and `updated_at` change. A save that changes nothing touches nothing and writes no audit entry.

## Audit

`TARIFF_CREATED` and `TARIFF_VERSION_ADDED` (entity type `TARIFF`, label "charge type name / tariff name"). The first
lists `name`, `chargeType` and the version fields; the second `validFrom`, `basis`, `frequency`, `rate` (four decimals),
`dueDate` and `note` (the last two only when set). The note text is part of the audit entry (like plot notes) but is never
written to the application log.

A change of scope writes (entity type `TARIFF`, same label), in this order:
`TARIFF_SCOPE_CHANGED` when the kind or the selected plots changed, with `scope` (old to new kind, only when it
changed) and `selectedPlots` (the plot numbers in natural order joined by ", ", empty is none; only when the set
changed); then one `TARIFF_EXCLUSION_REMOVED` per exclusion that is gone (`plot` = the number, `reason` = the old one)
and one `TARIFF_EXCLUSION_ADDED` per new exclusion (`plot`, `reason`), each in the order of the plot numbers. A new
reason for an excluded plot is a removal plus an addition. Switching to selected plots therefore audits every
removed exclusion. The reasons are in the audit entries only, never in the application log.

Entity type `CHARGE_TYPE` label = the stored name:
`CHARGE_TYPE_CREATED` (`name`, `description`, `active`), `CHARGE_TYPE_UPDATED` (changed fields among `name`,
`description`, `active`), `CHARGE_TYPE_REORDERED` (one entry per type whose position changed, field `position` with the
old and new number), `CHARGE_TYPE_ARCHIVED` (`archived` false to true). Published through `TariffAuditPublisher`.

## Default names: an exception to the English-only rule

The Ukrainian default names of the four seeded types ("Членський внесок", "Електроенергія", "Земельний податок",
"Вода") are needed on the server for the name check above. They live in one UTF-8 resource file,
`src/main/resources/tariffs/charge-type-default-names.properties` (next to the English ones), read by
`DefaultChargeTypeNames`. The file mirrors `chargeTypes.defaultNames` in the web catalog
(`packages/shared/src/i18n/uk.ts`), which is the source of the texts users see; keep the two in step. This is a
narrow exception to "everything in the repository is English", of the same kind as the import vocabulary (ADR 0012).

## Dependencies

`identity` (`CurrentUser`, `AccessProfile`), `audit` (events), `problem`, `money` (`Rate`) and `plots` (the public
`PlotCatalog` only: `find`, `findAll`, `inNaturalOrder`, `countChargeable`). The module root exports `Basis`, `Frequency` and `VersionTerms`. Nothing depends on this module yet.
