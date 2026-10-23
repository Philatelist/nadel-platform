# tariffs

What the cooperative charges for and how much (feature 009): **charge types**, **tariffs with versions**, **scopes** and
the **expected amount** of a plot for a month. Nothing is charged here; the ledger (feature 011) charges with the same
`ChargeCalculator` (`breakdown`), reads the tariffs through `TariffBook` and picks the tariff with `TariffResolver`.

Tables (all under RLS `tenant_isolation`, an index on `tenant_id`, composite foreign keys on `(tenant_id, id)`; V10):

| Table | What it holds |
|---|---|
| `charge_type` | What is charged for: the five seeded types (`code` MEMBERSHIP, ELECTRICITY, LAND_TAX, WATER, COMMON_CONSUMERS since V13) and custom ones |
| `tariff` | A tariff of one charge type, with its `scope_kind` (`ALL_PLOTS` or `SELECTED_PLOTS`; new tariffs are `ALL_PLOTS`) |
| `tariff_version` | Rates by date (`valid_from`, basis, frequency, `rate numeric(14,4)`, due date, note); a new rate is a new version, a mistake is corrected or deleted until a charge uses the version |
| `tariff_scope_plot` | Selected plots (`SELECTED`), or exclusions with a reason (`EXCLUDED`); written by `PUT /tariffs/{id}/scope`; cascades when a plot is deleted |

All four tables exist in V10 (one migration, one isolation test). `nadel_app` has no `DELETE`/`TRUNCATE` on `charge_type` and `tariff`.
`tariff_version` had no `UPDATE`/`DELETE` in V10; V11 grants both so a version can be corrected or deleted until a charge
uses it (see Correcting a version). `TRUNCATE` stays revoked on all four, and RLS limits both statements to the caller's
cooperative.

## Public API and permissions

The module root exports `ChargeCalculator` (pure, no Spring, no database), `VersionTerms` (with `InvalidVersionTermsException`),
`Basis`, `Frequency`, `ExpectedCharge` (`Amount` or `ByReadings` with the meter basis), `DaySet` (pure: sorted disjoint
closed date ranges, `ALL`, `NONE`, `of`, `count`, `intersect`, `contains`, `first`, `last`), `TariffResolver` (pure),
the port `TariffVersionUsage`, the read API `ChargeTypeCatalog` (`find`, `findAndLockShared`, `all`: `ChargeTypeFacts` with code, stored name, `nameCustomized`, active, archived, position and the audit token `auditName()`; `findByNames`: the types a typed name stands for, by the stored name and, while a seeded type is not renamed, its default names of `DefaultChargeTypeNames` in both languages, ignoring case and extra spaces, for the ledger's opening balances import; implemented by `JpaChargeTypeCatalog`) and the read API `TariffBook`
(implemented by `JpaTariffBook`); everything else is in `internal`. Feature 011 (the ledger) calls the same
`ChargeCalculator`, so the preview and the real charge never diverge.

Permissions (ADR 0009, defined in `identity.Permission`, seeded for the Chairman by `seed_base_roles`; the Treasurer gets
only `TARIFFS_VIEW`): `TARIFFS_VIEW` reads charge types, tariffs and the expected amount of any plot. `TARIFFS_MANAGE`
changes them, and every write checks `TARIFFS_VIEW` and `TARIFFS_MANAGE` together. A member without these permissions may read only the expected amount of a plot they
currently own. Every change is written to the audit log (see Audit).

## Charge types

- `seed_charge_types(tenant)` writes the five types with deterministic ids `md5(tenant || ':CHARGE_TYPE:' || code)::uuid`,
  positions 1 to 5, English names, Water inactive (`COMMON_CONSUMERS`, "Common consumers", added by V13 for new and
  existing cooperatives), `name_customized = false`, `ON CONFLICT DO NOTHING` (never
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
| `PUT /tariffs/{id}/versions/{versionId}` | `updateTariffVersion` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `DELETE /tariffs/{id}/versions/{versionId}` | `deleteTariffVersion` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `PUT /tariffs/{id}/scope` | `updateTariffScope` | `TARIFFS_VIEW` and `TARIFFS_MANAGE` |
| `GET /plots/{id}/tariffs?month=YYYY-MM` | `getPlotTariffs` | `TARIFFS_VIEW` for any plot; otherwise a member for a plot they own now (no `@PreAuthorize`) |

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
  plots until its scope is changed. Tariffs are never renamed or deleted. A change of rate is a new version; a mistake in a
  version is corrected or the version deleted until a charge uses it (below).
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
  land (plots are read through `PlotCatalog.findAll`, never from the plot table). When the shown version is `PER_KWH`
  or `PER_M3` (such a tariff applies to the plots with a meter, spec 010 section 2.7), a plot also needs an active plot
  meter of that resource (electricity, water) to count: `ALL_PLOTS` counts the active, non-common plots in
  `meters.MeterCatalog.plotIdsWithActiveMeter(resource)` and `SELECTED_PLOTS` the selected ones among them. For
  `ALL_PLOTS` the active, non-common plots among the exclusions (with a meter, for a meter-based version) are
  subtracted. `TariffAssembler` loads the chargeable count and each resource's metered plots once per request.
- `GET /tariffs/{id}` returns the tariff with its charge type (an archived one too, so the history stays readable),
  its scope (`selectedPlots`, `exclusions` with plot number, `archived` and reason, in natural order of the number) and
  the same `plotCount` as the list. A tariff of another cooperative or an unknown id is 404 (RLS). `TariffAssembler`
  builds the card (also the answer of `POST /tariffs` and `PUT .../scope`) and the counts; plots are read only through
  `PlotCatalog`.

## Correcting a version

`PUT /tariffs/{id}/versions/{versionId}` replaces the terms of a version; `DELETE` removes it
(`TariffCommands.updateVersion` and `deleteVersion`, one transaction each). The rule (money rules section 4): a version can
be corrected or deleted until a charge uses it; after that a correction goes through recalculation (feature 012).

- **Request.** The body is `TariffVersionCreate`, the same form as for a new version, with the same field codes (`rate`
  `RATE_INVALID`, `frequency` `FREQUENCY_BASIS_MISMATCH`, `dueDate` `REQUIRED` or `INVALID`) and the same rule for the
  past date (`pastDated` in the answer, no refusal). The answer is `200` with the version and its derived `validTo`.
- **Order of checks.** Field problems (400); the tariff row `FOR UPDATE` (404 when unknown or foreign) and its charge type
  `FOR SHARE` (409 `CHARGE_TYPE_ARCHIVED`); the version, which must belong to that tariff (404 otherwise); the use check
  (409 `TARIFF_VERSION_IN_USE`); for `DELETE` the last-version check (409 `TARIFF_LAST_VERSION`: a tariff always has a
  version, archive the charge type instead); then the write. A `validFrom` that another version of the tariff has is 409
  `TARIFF_VERSION_DATE_TAKEN` (`errors[{field: validFrom}]`, from the unique index as on create).
- **A request that changes nothing** (`rate` `50` against a stored `50.0000` is no change) writes nothing and no audit entry.
  It still goes through the use check, so a version in use answers 409 for it too.
- **End dates.** `validTo` is derived from the ordering of the versions on every read and never stored, so correcting a
  date or deleting a version re-derives the neighbours' `validTo` with no further write; `GET /tariffs/{id}` and
  `GET /plots/{id}/tariffs` show the result at once.
- **Entity.** `TariffVersionRecord` is no longer `@Immutable`; `correct(terms, note)` replaces the terms and keeps the
  owner, the tariff and the creation time. V11 grants `UPDATE` and `DELETE` to `nadel_app`; the use check, not the grant,
  protects a version a charge was produced from.

### The port `TariffVersionUsage`

`TariffVersionUsage.isUsed(UUID tenantId, UUID versionId)` is a public interface in the module root. The tariffs module
asks **every** bean of that type (`List<TariffVersionUsage>`) and counts a version as used when any answers `true`.

The ledger (feature 011) implements it without a module cycle: it depends on `tariffs`, and its
`ledger.internal.LedgerTariffVersionUsage` answers from its own tables (a charge that is not reversed has a
`charge_part` of the version). The 009 stub `NoChargesYetUsage` is gone. The method runs inside the caller's
tenant-aware transaction with the tariff row locked `FOR UPDATE`; the ledger writes charges with the tariffs locked
`FOR SHARE` (`TariffBook.lockShared`), so a correction waits for a run and then sees its charges. The
`TARIFF_VERSION_IN_USE` tests against the real implementation are `ledger.TariffVersionUsageTests`; here a test bean
stands in (`TariffVersionCorrectionTests`).

## Charging (feature 011): `TariffBook` and `TariffResolver`

- **`TariffBook`** (`JpaTariffBook`): `snapshot()` reads every charge type (`ChargeTypeEntry`: code, active, archived)
  and every tariff (`TariffEntry`: charge type, name, `Scope` `ALL_PLOTS`/`SELECTED_PLOTS`, selected plots, excluded
  plots, `VersionRef(id, VersionTerms)` oldest first) in four queries; `tariffIds()`; `lockShared(ids)` locks the
  tariffs `FOR SHARE` in id order until the end of the caller's transaction.
- **`TariffResolver.resolve(snapshot, chargeTypeId, PlotFacts(plotId, commonLand, archived, areaM2), from, to)`**
  (pure): `None(TYPE_INACTIVE)` for an inactive, archived or unknown type; `None(NO_TARIFF)` when no tariff of the type
  has a version valid in the range (versions never end, so: its first version starts on or before `to`);
  `None(OUTSIDE_SCOPE)` for an archived plot or common land; then the tariffs whose scope covers the plot (selected,
  or all plots minus exclusions): exactly one is `Single`, two or more are `Conflict` (ordered by name, then id), none
  is `None(EXCLUDED)` when the plot is excluded from one, else `None(OUTSIDE_SCOPE)`. Tests: `TariffResolverTests`.
- **The conflict warning** (`TariffConflicts`): `GET /tariffs` and `GET /tariffs/{id}` (and the answers of create and
  scope changes) carry `conflicts[{otherTariffId, otherTariffName, plotCount, plots[{plotId, number}] (the first 10
  in natural order)}]`, worked out with the same resolver for every active plot that is not common land from today
  on (`PlotCatalog.findChargeable`), ordered by the other tariff's name; empty when there is none or the type is
  inactive. A warning only: both tariffs stay valid; the ledger charges neither on those plots and queues the case.
  The warning shows every overlap of scopes, whatever the amounts; the ledger's month run counts a tariff in a conflict
  only when it would charge the plot more than `0.00` in the month (money rules §4.1, `ledger` README), so two
  one-time fees of one type due in different months are warned about here but never queued.
  Tests: `ledger.TariffConflictWarningTests`.

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

## Expected amount of a plot

`GET /plots/{id}/tariffs?month=YYYY-MM` (`ExpectedAmountQueries`, `TariffsController.getPlotTariffs`) answers what the plot
*would* be charged in the month. The arithmetic lives once in the public, pure `ChargeCalculator` (rule in
`docs/domain/money-rules.md` section 4.1); the query only picks the tariffs and the scope.

- **Breakdown (feature 011).** `ChargeCalculator.breakdown(versions, areaM2, YearMonth, DaySet applicable)` returns
  `Optional<Breakdown(Money amount, List<Part(terms, days, units, from, to)>)>`: each recurring version counts for
  the days of the month on which it is valid **and** that are applicable (the water connection days; `DaySet.ALL`
  otherwise), over the days in the month; a one-time fee counts only when its version is valid on the due date and the
  due date is applicable (its part: 1 day, the due date, 1 unit). One exact sum, rounded once. Empty when no version is
  valid on an applicable day of the month, or when a per-kWh or per-m3 version is (charged by readings). A one-time fee
  due in another month breaks down to `0.00` without parts. `calculate` is `breakdown(..., DaySet.ALL)` mapped to
  `ExpectedCharge.Amount`, so the 009 results are unchanged. Tests: `ChargeCalculatorTests` (also 25.00 and 12.90 for
  water 300 per plot per year with the plot connected all month or from the 16th), `DaySetTests`.
- **Calculator.** `ChargeCalculator.calculate(List<VersionTerms>, areaM2, YearMonth)` returns an
  `Optional<ExpectedCharge>`: empty when no version overlaps the month, `ExpectedCharge.ByReadings(basis)` when a
  `PER_KWH` or `PER_M3` version overlaps it (the basis of the latest such version names the meter's resource), else
  `ExpectedCharge.Amount(Money)`. Versions are sorted by `validFrom`, each counts
  for its days in the month over `D`; everything is summed as one exact fraction over `D x 12` and rounded once with
  `Money.ofQuotient`. A one-time fee counts in its due month only (never split). `ChargeCalculator.shownVersion` says
  which version's basis, frequency and rate to display.
- **Who may ask.** No `@PreAuthorize`: a caller with `TARIFFS_VIEW` may ask for any plot of the cooperative; anyone else
  only for a plot in `OwnedPlotsSource.currentPlotsOf(memberId)`. Every other case (another member's plot, an unknown id,
  a plot of another cooperative under RLS) is the same 404 `NOT_FOUND`; the platform operator is 403. Common land and an
  archived plot answer 200 with no items and a total of `0.00`.
- **Month.** Optional, `YYYY-MM` (contract pattern; anything else is 400 `VALIDATION_FAILED` with field `month`);
  default is the current month on the Kyiv `Clock`.
- **Which tariffs.** Tariffs of non-archived and active charge types only. `ALL_PLOTS` applies unless the plot is
  excluded (item status `EXCLUDED` with the reason, no amount, only when the tariff is listed for the month at all);
  `SELECTED_PLOTS` applies only to selected plots. A tariff that no version of covers the month is not listed. Items
  are ordered by charge type position, then tariff name (ignoring case); `total` is the sum of the `AMOUNT` items.
- **Meter-based tariffs** (feature 010). A `ByReadings` tariff is `BY_READINGS` when the plot had a plot meter of the
  resource (`PER_KWH` electricity, `PER_M3` water) working on at least one day of the month
  (`meters.MeterCatalog.hasMeterBetween(plot, resource, first day, last day)`: installed on or before the last day and
  not removed before the first), otherwise `NO_METER`: the tariff does not reach the plot. Neither has an amount or adds
  to the total; the amount by readings comes with feature 011. The question is asked once per resource.
- **Queries.** The active charge types, their tariffs, all their versions and the scope rows that name this plot are
  loaded in four queries, whatever the number of tariffs. Plots are read through `PlotCatalog`, ownership through
  `OwnedPlotsSource`, meter presence through `MeterCatalog`.
- **Demo data.** `R__demo_data.sql` adds membership (50.0000 per sotka yearly, plot 28 excluded), land tax (35.0000
  per sotka yearly), electricity (4.3200 per kWh monthly) and a custom "Road repair" type with a fixed one-time fee of
  1200.0000 due 2026-12-01, all from 2026-01-01 for the demo cooperative (block `demo tariffs`). The block `demo ledger`
  (feature 011, dev profile only) adds the "Спільні споживачі за лічильником" tariff of the common consumers type (4.3200
  per kWh from 2026-01-01) and books the month runs, so in the dev demo the membership, land tax and electricity
  versions are in use (`TARIFF_VERSION_IN_USE` on a correction).

## Audit

`TARIFF_CREATED`, `TARIFF_VERSION_ADDED`, `TARIFF_VERSION_UPDATED` and `TARIFF_VERSION_DELETED` (entity type `TARIFF`,
label = the tariff name, user content). The first lists `name`, `chargeType` (a charge type name token, see below) and the
version fields; the second `validFrom`, `basis`, `frequency`, `rate` (four decimals), `dueDate` and `note` (the last two only
when set). `TARIFF_VERSION_UPDATED` lists the changed fields only, each as old to new, in the order `validFrom`, `basis`,
`frequency`, `rate`, `dueDate`, `note` (a field that was or becomes unset has no old or no new value; a request that
changes nothing writes no entry). `TARIFF_VERSION_DELETED` lists the old values (new is empty) of `validFrom`, `basis`,
`frequency`, `rate` and, when set, `dueDate` and `note`. The version is identified by the values (the entry is about the
tariff). The note text is part of the audit entry (like plot notes) but is never written to the application log.

A change of scope writes (entity type `TARIFF`, label = the tariff name), in this order:
`TARIFF_SCOPE_CHANGED` when the kind or the selected plots changed, with `scope` (old to new kind, only when it
changed) and `selectedPlots` (the plot numbers in natural order joined by ", ", empty is none; only when the set
changed); then one `TARIFF_EXCLUSION_REMOVED` per exclusion that is gone (`plot` = the number, `reason` = the old one)
and one `TARIFF_EXCLUSION_ADDED` per new exclusion (`plot`, `reason`), each in the order of the plot numbers. A new
reason for an excluded plot is a removal plus an addition. Switching to selected plots therefore audits every
removed exclusion. The reasons are in the audit entries only, never in the application log.

Charge type names in the audit log are tokens, never English display text (the pattern of the role names): a seeded type
that was never renamed (`nameCustomized` false) is written as `base:<CODE>`, with `<CODE>` one of `MEMBERSHIP`,
`ELECTRICITY`, `LAND_TAX`, `WATER`, `COMMON_CONSUMERS`; a renamed seeded type and every custom type is written under its stored name
(`ChargeTypeSnapshot.auditName`). This applies to the `CHARGE_TYPE` label and `name` old/new values and to the
`chargeType` value of `TARIFF_CREATED`. The client shows the catalog name for `base:<CODE>` and the value as is otherwise.

Entity type `CHARGE_TYPE` label = the name token above (after the change):
`CHARGE_TYPE_CREATED` (`name`, `description`, `active`), `CHARGE_TYPE_UPDATED` (changed fields among `name`,
`description`, `active`), `CHARGE_TYPE_REORDERED` (one entry per type whose position changed, field `position` with the
old and new number), `CHARGE_TYPE_ARCHIVED` (`archived` false to true). Published through `TariffAuditPublisher`.

## Default names: an exception to the English-only rule

The Ukrainian default names of the five seeded types ("Членський внесок", "Електроенергія", "Земельний податок",
"Вода", "Спільні споживачі") are needed on the server for the name check above. They live in one UTF-8 resource file,
`src/main/resources/tariffs/charge-type-default-names.properties` (the English and Ukrainian default names side by
side, keys like `MEMBERSHIP.en` and `MEMBERSHIP.uk`), read by `DefaultChargeTypeNames`. The file mirrors `chargeTypes.defaultNames` in the web catalog
(`packages/shared/src/i18n/uk.ts`), which is the source of the texts users see; keep the two in step. This is a
narrow exception to "everything in the repository is English", of the same kind as the import vocabulary (ADR 0012).

## Dependencies

`identity` (`CurrentUser`, `AccessProfile`, `OwnedPlotsSource`), `audit` (events), `problem`, `money` (`Rate`, `Money`) and
`plots` (the public `PlotCatalog` only: `find`, `findAll`, `inNaturalOrder`, `countChargeable`, `findChargeable`) and `meters` (the public
`MeterCatalog` only: `plotIdsWithActiveMeter`, `hasMeterBetween`; `meters` never depends on `tariffs`). The module root exports
`Basis`, `Frequency`, `VersionTerms`, `ExpectedCharge`, `ChargeCalculator`, `DaySet`, `TariffBook`, `TariffResolver`,
`TariffVersionUsage` and `ChargeTypeCatalog`. The ledger (feature 011) depends on this module and implements
`TariffVersionUsage`.
