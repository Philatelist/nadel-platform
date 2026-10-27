# meters

The cooperative's meters, the electricity meter tree, the readings taken on any date and the consumption calculated from
them (feature 010, ADR 0014). Nothing is charged here; electricity charges, losses and the split of common consumers arrive
with feature 011, which reuses `ConsumptionCalculator` and feeds the exact `Quantity` to money.

Status: slices 1 to 7 of spec 010 are in code (slice 7 adds the demo data): tables, permissions, the pure calculation, the `ReadingUsage` port, the
meter register (entity, commands, queries, endpoints, the plot guard, audit), readings with the round (entry, batch,
superseding, correction, deletion, the unusual-consumption warning, the history with intervals), replacement, range
consumption over a chain of meters, the member flow (submission, approval, rejection), the electricity tree (moves
with an effective date, the tree over a date range with the consumption per node), meter presence for the tariffs
(`MeterCatalog`) and the Meters sheet of the Excel import (`MeterImport`). Feature 011 (slice 5) adds the water
connections of plots (see Water connections).

Depends on `plots` (`PlotCatalog`, the import records `PlotImportRow` and `PlotImportPlan`, and it implements
`plots.PlotChangeGuard`), `identity` (`CurrentUser`, `AccessProfile`, `OwnedPlotsSource`, `Permission`), `audit` (events)
and `problem`. `plots` never depends on `meters`; `tariffs` (`MeterCatalog`), `imports` (`MeterImport`) and `ledger`
(`MeterNetwork`) depend on it, never the other way round.

## Tables (V12)

All three are under RLS `tenant_isolation` (`USING` and `WITH CHECK`), lead their indexes with `tenant_id`, link to each other
with composite foreign keys on `(tenant_id, id)` and have `TRUNCATE` revoked from `nadel_app`. `UPDATE` and `DELETE` stay
granted. Isolation, constraints and grants are tested by `MetersTenantIsolationTests`.

| Table | What it holds |
|---|---|
| `meter` | One physical meter: `kind` (`PLOT`, `SUBSTATION`, `QUARTER`, `COMMON_CONSUMER`), `resource` (`ELECTRICITY`, `WATER`; water for plot meters only), serial, name (required unless `PLOT`), `plot_id` (exactly for `PLOT`), `installed_on` and `initial_value`, `removed_on` and `final_value` together, note, `replaces_meter_id`. At most one active plot meter per plot and resource (partial unique index `meter_active_plot_uq`, SQLSTATE 23505 for `METER_ACTIVE_EXISTS`) |
| `meter_parent` | The parent of a meter in the electricity tree per period: `valid_from`, `valid_to` (both days of the period, null while it lasts). `EXCLUDE USING gist` keeps one parent at a time per meter (23P01). A move on day D closes the old row on D - 1 and appends a new one |
| `meter_reading` | A value on a date: `source` (`ENTERED`, `IMPORTED`, `MEMBER`), `status` (`APPROVED`, `PENDING`, `REJECTED`, `SUPERSEDED`), the reject reason iff `REJECTED`, `superseded_by_id` (only on a superseded row), who decided when. At most one `APPROVED` row per meter and date (partial unique index `meter_reading_approved_uq`); supersede the old row (update, flush) before inserting the new approved one |

Foreign keys: a plot with a meter, a meter that has readings, a meter that is somebody's parent and a meter that was replaced
cannot be deleted (`RESTRICT`); the parent rows of a deleted meter go with it (`CASCADE`); deleting a reading clears the
`superseded_by_id` of the row it superseded (`SET NULL` on that column only), which stays `SUPERSEDED`.

What the database does not check and the service must: a `PLOT` meter only on a plot of kind `PLOT`; tree rules (kinds,
depth, electricity only, the parent active on the date); the order of readings (`previous <= value <= next`) and their dates
within the working period; that a `REJECTED` or `APPROVED` row carries `decided_*` when it was decided.

## Public API (module root)

| Type | What it is |
|---|---|
| `MeterResource`, `MeterKind` | The two enums |
| `MeterValue` | A reading or a quantity of a resource: not negative, at most 11 integer digits and 3 decimals, scale 3, never rounded. `parse` is strict (`^\d{1,11}(\.\d{1,3})?$`); a fourth decimal is an `InvalidMeterValueException`. `toString()` is the wire format with exactly three decimals (`"8205.000"`); clients trim the zeros for display |
| `Quantity` | An exact reduced fraction (`BigInteger` over `BigInteger`): `plus`, `minus`, `times(long)`, `dividedBy(long)`, `compareTo`, `signum`, `numerator()`, `denominator()`. `toDecimal()` rounds HALF_UP to three decimals with the sign kept (a report's losses or reversal kWh), `toMeterValue()` is that value refused below zero: the one display rounding of a quantity (ADR 0014) |
| `ConsumptionCalculator` | Pure and static (no Spring, no database, no clock); see below |
| `ReadingUsage` | Port: `isUsed(tenantId, meterId, from, to)`; the ledger implements it (`LedgerReadingUsage`, feature 011: a charge that is not reversed read the meter on a day of the span). Every bean is asked; the readings are used when any says so |
| `PlotMeterPointsChanged` | Event (feature 011): the points of a plot meter changed on a date (`cause` `READING_ENTERED`, `READING_APPROVED`, `READING_CORRECTED`, `READING_DELETED`, `METER_REMOVED`, `METER_REPLACED`), published by `internal.MeterPointEvents` in the transaction of the change, for `PLOT` meters only; a correction that moves a reading publishes one per date. Ids, codes and a date only. The ledger charges the intervals around the date |
| `ReadingChanges` | Facade for the ledger's recalculation (feature 012 §2.5, `internal.ReadingChangeService` over `ReadingCommands`): `enter(meterId, takenOn, value, actor)`, `correct(readingId, takenOn, value, actor)`, `delete(readingId, actor)` in the caller's transaction (`Propagation.MANDATORY`; a preview rolls the change back). The same locks, rules and supersede writes as the reading endpoints, but no use check and no `PlotMeterPointsChanged` (the recalculation charges the intervals itself, so the listener cannot book them twice). Returns a `Change`: the meter (kind, resource, plot), the reading after the change, the readings before and after (date and value), the days the change reaches (`from`, `to`: the span the use check asks about) and the last point whose interval it alters (`through`), and the audit entry (`READING_ENTERED`/`REPLACED`/`CORRECTED` with the changed fields only/`DELETED`), **not published**: the ledger publishes it with the recalculation's entry |
| `MeterCatalog` | Read API for other modules (`internal.JpaMeterCatalog`): `plotIdsWithActiveMeter(resource)` (plots with an active plot meter of the resource now) and `hasMeterBetween(plot, resource, from, to)` (a plot meter of the resource worked on a day of the range, both ends included). The tariffs module uses it for `NO_METER` and the plot counts of meter-based tariffs. `find(meterId)` (kind, resource, name, active; removed meters too) and `chainOf(meterId)` (the ids of its chain of replacements, oldest first): the ledger's settings of a common consumer, which belong to the consumer's whole chain (011 slice 7). `plotMeterIds(plots, resources)` (the plot meters of the plots, active and removed, in id order): the intervals a recalculation charges (012) |
| `MeterNetwork` | Read API for the ledger (`internal.JpaMeterNetwork`, feature 011): `lockSharedPoints(meterId)` locks the meter `FOR SHARE` (a reading write holds it `FOR UPDATE`) and returns its points with stable keys (`INSTALL:{meter}`, the reading id, `REMOVAL:{meter}`; a reading on a boundary date takes its place), in the caller's transaction; `staleMeters(asOf, days)` lists the plot meters that worked on `asOf` (installed on or before it, not removed on or before it) whose last point on or before `asOf` (the latest approved reading, else the installation) is dated before `asOf - days`, as `StaleMeter(meterId, plotId, resource, lastReadingOn)`; the rule of the round's `noReadingSince` filter, as of a date. The closing check list of a billing month asks it with 60 days; `network(from, to)` (and `lockSharedNetwork`, every electricity meter `FOR SHARE` in id order first) gives the electricity tree for the losses runs: every chain that worked in the range with its meters' histories (all approved readings) and its places in the tree by days (`Placement(parentKey, from, to)`, through the day of the final removal, `null` parent for the days without one), built by `TreeQueries.network` like the tree screen. Tests: `MeterNetworkTests`, the ledger's `LossesRunsTests` |
| `WaterConnections` | Read API for the ledger (`internal.JpaWaterConnections`, feature 011): `connectedDays(plotIds, from, to)` gives, per plot, the days it was connected to any water source in the range (clipped, oldest first; a plot without such a day is absent), `connectedToSource(sourceMeterId, from, to)` the same for one source (null = the cooperative's water supply), for the common consumer split of a well pump. In the caller's transaction |
| `MeterReports` (with `MeterTreeReport`, `MeterTreeReportRow`, `MeterTreeReportSlice`, `MeterTreeValue`, `MeterValueKind`, `MeterTreeQuantity`, `MeterTreeLosses`, `MeterTreeUnknownReason`, `WaterUseRow`, `WaterSourceKind`, `MissingReadingRow`) | Read facade of the reports (feature 013, `internal.JpaMeterReports`): `tree(from, to)`, the tree of `GET /meter-tree` with the children's sum, the losses and the plots fed; `waterUse(from, to, source)`, the connected days and m³ per plot and water source; `missingReadings(from, to, resources, kinds, plotIds, limit)`, the meters without a reading in a range. See The tree for the reports and Water use and missing readings for the reports |
| `MeterImport` | Facade of the Excel import's Meters sheet (`internal.MeterImportService`): `plan`, `lock`, `apply`, with `MeterImportRow`, `MeterImportPlan`, `MeterImportItem`, `MeterImportAction` (`NEW`, `UNCHANGED`) and `MeterImportProblem`. See The Excel sheet |
| `WaterConnectionImport` | Facade of the water column of the Excel import's Plots sheet (`internal.WaterConnectionImportService`, feature 011 slice 8): `plan`, `lock`, `apply`, with the nested `Row`, `Item`, `Plan` and `Action` (`NEW`, `UNCHANGED`). See Water connections |

### The calculation

Points of a meter: its initial value on `installedOn`, every approved reading, its final value on `removedOn`; a reading on the
installation or removal date takes the place of that boundary point. `MeterHistory` is built from the approved readings only
(pending, rejected and superseded rows never reach the calculator) and refuses unordered or duplicate dates.

| Function | Result |
|---|---|
| `intervals(history)` | Consumption between neighbouring points, oldest first, including installation to the first reading and last reading to removal |
| `valueAt(history, date)` | `Real` (a point), `Calculated(Quantity)` (linear interpolation by days between the nearest points, `(v0 N + (v1 - v0) k) / N`), or `Unknown(lastReadingOn)` (no point after the date, or none before it) |
| `rangeConsumption(history, from, to)` | `valueAt(to) - valueAt(from)` clipped to the days the meter worked; a range that does not overlap them in more than a day is `Known(0)`; `Known(consumption, calculated)` or `Unknown(lastReadingOn)` |
| `rangeConsumption(chain, from, to)` | The sum over the meters of a plot (replacement, a counter that wrapped around: the same serial, final 99 999, initial 0); unknown when any part is |
| `splitByParent(chain, periods, from, to)` | One `Slice` per parent period that overlaps the range, from `max(from, validFrom)` to `min(to, validTo + 1 day)`; neighbouring slices share their boundary day and add up to the whole |
| `isUnusual(earlier, candidate)` | True when `c * sum(days) > 3 * sum(c) * n` over the earlier intervals that ended in the 180 days before the new reading and not after the new interval starts; no warning without such an interval, with a zero usual consumption or for an interval of no days |

Spec rows that are decisions about input and not calculations (a lower reading is refused, a member's pending reading is
ignored, the same date twice supersedes, the default date, a future date) belong to the reading commands of the later
slices; the calculator receives only the readings that count.

## The register (slice 2)

Internal: `MeterRecord` (entity of `meter`) and `MeterRepository`, `MeterCommands`, `MeterQueries`, `MeterDtoMapper` (the
one place that decides what a viewer sees), `MeterAccess` (the member rule shared with the readings), `ReadingFacts`
(latest approved reading and "has readings" in bulk, through `MeterReadingRepository`), `MeterSnapshot` +
`MeterAuditPublisher`, `MeterPlotGuard`, `MetersController`.

| Endpoint | Operation | Who |
|---|---|---|
| `GET /meters` | `listMeters` | `METERS_VIEW` |
| `POST /meters` | `registerMeter` | `METERS_VIEW` and `METERS_MANAGE` |
| `GET /meters/{id}` | `getMeter` | `METERS_VIEW` |
| `PUT /meters/{id}` | `updateMeter` | `METERS_VIEW` and `METERS_MANAGE` |
| `DELETE /meters/{id}` | `deleteMeter` | `METERS_VIEW` and `METERS_MANAGE` |
| `POST /meters/{id}/remove` | `removeMeter` | `METERS_VIEW` and `METERS_MANAGE` |
| `GET /plots/{id}/meters` | `listPlotMeters` | `METERS_VIEW` for any plot, otherwise the plot's current owner (without `note`); anyone else, an unknown plot and one of another cooperative the same 404 `PLOT_NOT_FOUND`, the platform operator 403 |

The platform operator has no permissions: 403 everywhere. A meter or plot of another cooperative is 404 (RLS).

- **Register.** A plot meter needs `plotId` and has no name; every other kind needs a name, has no plot and measures
  electricity. Field codes (400 `VALIDATION_FAILED`): `plotId` `REQUIRED`/`INVALID`/`UNKNOWN_PLOT`, `name`
  `REQUIRED`/`INVALID`, `resource` `INVALID`, `serial` `REQUIRED`/`TOO_LONG`, `initialReading` `METER_VALUE_INVALID`
  (the contract pattern; `ValidationProblems` maps a `Pattern` on `*Reading` and `value` to it). The plot is locked
  `FOR SHARE` (`PlotCatalog.findAndLockShared`) before it is checked: common land is 409 `METER_COMMON_LAND`, an archived
  plot 409 `PLOT_ARCHIVED` (both with `errors[{field: plotId}]`). An installation date after today (Europe/Kyiv `Clock`)
  is 409 `READING_DATE_IN_FUTURE` on `installedOn`. A second active meter of the plot and resource is 409
  `METER_ACTIVE_EXISTS` (pre-check, and the index `meter_active_plot_uq` mapped at the flush for two registrations at
  once).
- **Update** replaces serial, name and note only (same name rules); also for a removed meter. No change, no audit entry.
- **Remove** (`removedOn`, `finalReading`) locks the meter `FOR UPDATE`: an already removed meter is 409 `METER_REMOVED`;
  the final reading is the last point of the meter, so `removedOn` after today is `READING_DATE_IN_FUTURE`, before the
  installation `READING_DATE_OUTSIDE_METER`, before the latest approved reading `READING_OUT_OF_ORDER` (all on
  `removedOn`), and a value below the latest approved reading (or the initial one) `READING_OUT_OF_ORDER` on
  `finalReading`. A meter with readings waiting for approval is 409 `METER_HAS_PENDING_READINGS` (checked right after
  `METER_REMOVED`): the board approves or rejects them first, so a pending reading never ends up outside the working
  period of its meter. A meter with a working meter under it after `removedOn` (a period that lasts or ends after that
  day, of a child not removed by then) is 409 `METER_HAS_CHILDREN`: move the children first, or replace the meter,
  which takes them over.
- **Delete** locks the meter: a removed meter is 409 `METER_REMOVED`, a meter with a reading of any status 409
  `METER_HAS_READINGS` (the `meter_reading` foreign key is the backstop), a meter that is or ever was somebody's parent
  in the tree 409 `METER_HAS_CHILDREN` (their periods refer to it; `meter_parent_parent_fk` is the backstop). `deletable`
  in the API says when it is allowed by readings; the tree check is not part of it.
- **List** (`MeterQueries`): plot meters in street order (`PlotCatalog.inStreetOrder`) and, within a plot, active first,
  electricity before water, newest installation first; then the named meters by name (`Collator` for Ukrainian). Filters
  `resource`, `kind`, `street` (ignoring case and surrounding spaces), `noReadingSince` (active meters whose last point,
  the latest approved reading or else the initial reading, is before the date), `includeRemoved` (default false). Bulk
  loads: the plots, the latest approved readings, the meters with readings.
- `lastReading` in the API is the final reading of a removed meter, else the latest approved reading, else the initial
  reading. Pending, rejected and superseded readings never count.

### Plot guard

`MeterPlotGuard` implements `plots.PlotChangeGuard` (ADR 0011): delete and a change to common land are refused while the
plot has any meter (active or removed: its history stays readable), archive while it has an active meter, with 409
`PLOT_HAS_METER`; the import's plan of the Plots sheet reports the same code on `kind`. It only reads. Registration takes
the plot `FOR SHARE` and the plot changes hold it `FOR UPDATE`, so the two run one after another.

`WaterConnectionPlotGuard` implements the same SPI (feature 014): archive and a change to common land are refused while
the plot has a lasting water connection (`valid_to IS NULL`, `WaterConnectionRepository.plotIdsWithLastingConnection`),
with 409 `PLOT_HAS_WATER_CONNECTION` (`errors[{field: kind}]` for the update, and on `kind` in the import's plan); end
the connection first. A connection with an end date, even a later one, does not refuse, and a delete is not refused
here (the connections go with the plot, `ON DELETE CASCADE`). Connecting, ending and the import's water column take the
plot `FOR SHARE` first, so they run one after another with a plot change. The guards are asked in a fixed order
(`@Order`: `ledger` 100, `MeterPlotGuard` 200, `WaterConnectionPlotGuard` 300), so a plot with an active meter and a
lasting connection is refused with `PLOT_HAS_METER`. Tests: `WaterConnectionPlotGuardTests`.

### Audit

Entity type `METER`, label = the name of a named meter or the serial number of a plot meter. `METER_REGISTERED` (`kind`,
`resource`, `plot` = the plot number, `serial`, `name`, `installedOn`, `initialReading`, `note`), `METER_UPDATED` (changed
`serial`, `name`, `note`), `METER_REMOVED` (`removedOn`, `finalReading`), `METER_DELETED` (every old value). Values are
codes, ISO dates and readings with three decimals; never logged.

## Readings and the round (slice 3)

Internal: `MeterReadingRecord` (entity of `meter_reading`) and `MeterReadingRepository`, `ReadingValidator` (pure: the
rules and the warning), `ReadingProblem`, `ReadingCommands`, `ReadingQueries`, `RoundQueries`, `ReadingDtoMapper`,
`ReadingsController`.

| Endpoint | Operation | Who |
|---|---|---|
| `GET /readings/round` | `getReadingRound` | `READINGS_ENTER`; `note` only with `METERS_VIEW` |
| `POST /readings` | `enterReadings` | `READINGS_ENTER` |
| `PUT /readings/{id}` | `correctReading` | `READINGS_ENTER` |
| `DELETE /readings/{id}` | `deleteReading` | `READINGS_ENTER` |
| `GET /meters/{id}/readings` | `listMeterReadings` | `METERS_VIEW` for any meter, otherwise a current owner of the meter's plot; anyone else 404, the platform operator 403 |

- **The round** lists the active meters in the order of the register (`MeterQueries`): plot meters in street and
  number order, electricity before water, then the named meters by name. Filters `resource`, `street`, `noReadingSince`.
  A plot without an active meter has no row.
- **Entry** (`POST /readings`, at most 100 rows) saves every row in its own transaction (`TransactionTemplate`; the
  method itself has none) and answers 200 with a result per row: `SAVED` with the reading, the interval it closes, the
  superseded ids and the warnings, or `REJECTED` with `error {code, field, params}`. A missing `takenOn` is today
  (Europe/Kyiv `Clock`). A malformed row is a 400 for the whole request (Bean Validation at the boundary).
- **Rules** (`ReadingValidator`), against the points of the meter (initial reading, approved readings, final reading;
  a point on the same date is not a neighbour): the date not after today (`READING_DATE_IN_FUTURE` on `takenOn`,
  param `today`), within `[installedOn, removedOn]` (`READING_DATE_OUTSIDE_METER` on `takenOn`, params `installedOn`,
  `removedOn`), `previous <= value <= next` (`READING_OUT_OF_ORDER` on `value`, params `previousTakenOn` and
  `previousValue` or `nextTakenOn` and `nextValue`). A removed meter still takes readings within its period.
- **Same date.** The approved reading of the date becomes `SUPERSEDED` and is flushed before the new approved row is
  inserted (`meter_reading_approved_uq`), then linked to it (`superseded_by_id`). The same value again changes nothing.
- **Correction** (`PUT`) of an `APPROVED` reading is a new row that supersedes the old one (and an approved reading on
  the new date); history kept. **Deletion** of an `APPROVED` reading removes the row; a reading it had replaced stays
  `SUPERSEDED` without a link. Any other status is 409 `READING_STATUS_INVALID`. A no-op correction is not audited.
- **Use check.** Before a change every `ReadingUsage` bean is asked about the days the change reaches: from the day
  after the previous point to the day before the next point around the date, or the date itself where there is no such
  point (for a correction around both the old and the new date); any "yes" is 409 `READING_IN_USE` (a row error in a
  batch). The neighbouring points stay out, so a new reading after a charged interval is accepted while a change inside
  or at the end of a charged interval is not (feature 011). `changeable` in the history says when a reading can still
  change. A used reading is corrected, deleted or entered inside a charged interval only together with a
  recalculation (feature 012: `POST /recalculations` with a `READING_*` cause, through `ReadingChanges`); the endpoints
  keep answering 409.
- **Charges.** Every saved change of a counted reading of a plot meter (entry, approval, correction, deletion) and a
  removal or replacement publish `PlotMeterPointsChanged`; the ledger charges the intervals asynchronously. A meter
  registration or import publishes nothing (an initial value closes no interval).
- **Unusual consumption.** `UNUSUAL_CONSUMPTION` when `ConsumptionCalculator.isUnusual` says so for the interval the new
  reading closes against the meter's other intervals (counted readings only); the reading is saved anyway.
- **Locks.** Every reading write takes the meter `FOR UPDATE` first; a correction or deletion finds the meter of the
  reading by id and reads the reading only after the lock.
- **History** (`GET /meters/{id}/readings`): every reading, newest date first (on a date the approved one first), each
  approved one with the interval it closes; the intervals of the counted points newest first. Consumption of an interval
  is exact (two readings with three decimals).

### Audit of readings

Entity type `METER_READING`, entity id = the meter (the log links to the meter card, as `OWNERSHIP` entries carry the
plot), label = the meter's label. `READING_ENTERED` (`takenOn`, `value` new), `READING_REPLACED` (`takenOn` old and new
the same date, `value` old and new), `READING_CORRECTED` (old and new `takenOn` and `value`, only those that changed), `READING_DELETED`
(`takenOn`, `value` old). Values are ISO dates and readings with three decimals. A change booked together with a
recalculation (feature 012, `ReadingChanges`) is published by the ledger with the field `recalculationId` (new value:
the recalculation's id) added, in one `AuditBatch` before the recalculation's `RECALCULATION_BOOKED`; a change whose
plan is empty is published alone, without the field.

## Replacement and range consumption (slice 4)

Internal: `MeterParentRecord` (entity of `meter_parent`) and `MeterParentRepository`, `MeterParents` (the tree part of a
replacement), `MeterChains` (the chain of a meter and the histories the calculation needs), `ConsumptionQueries`.

| Endpoint | Operation | Who |
|---|---|---|
| `POST /meters/{id}/replace` | `replaceMeter` | `METERS_VIEW` and `METERS_MANAGE` |
| `GET /meters/{id}/consumption?from&to` | `getMeterConsumption` | `METERS_VIEW` |

- **Replace** (`replacedOn`, `finalReading`, `serial`, `initialReading`) is one transaction: the old meter is removed
  with the rules of a removal (`METER_REMOVED`, `METER_HAS_PENDING_READINGS`, the date errors on `replacedOn`, the value
  error on `finalReading`) and flushed, which frees the plot's active slot (`meter_active_plot_uq`), then the new meter
  is inserted: the same kind, resource, plot, name and note, the new serial and initial reading, installed on
  `replacedOn`, `replaces_meter_id` = the old meter. A counter that wrapped around is a replacement by itself: the same
  serial, initial reading 0. Answer 201 with the new meter. Locks: the plot `FOR SHARE` (as a registration), the meter
  `FOR UPDATE`, then the meters under it (`FOR UPDATE`, id order).
- **Replacement links.** Every `Meter` the API returns (register, card, plot meters, round, pending approvals, the
  answers to register, update, remove and replace) carries its neighbours in the chain so the cards can show it:
  `replaces` (`MeterPredecessor`: id, serial, `removedOn`, `finalReading` of the meter it replaced) and `replacedBy`
  (`MeterSuccessor`: id, serial; the day and value of that replacement are the meter's own `removedOn` and
  `finalReading`). `replacesMeterId` stays for older clients. A wrap-around links to a meter with the same serial.
  `MeterChains.neighboursOf` loads them with two queries per answer, whatever the number of meters, and links only a
  neighbour of the same plot (a replacement keeps the plot), so a member who sees the meters of their own plot never
  sees a meter of another plot through a link.
- **Pending readings, decided conservatively.** Both a removal and a replacement are refused while the meter has a
  `PENDING` reading, whatever its date: approving it later could put a counted reading after the removal or make the
  final reading out of order, and a member's reading is never moved to another meter silently. The approvals screen
  decides it first.
- **The tree** (`MeterParents.takeOver`): the old meter's parent period that reaches the day ends on that day (the old
  meter worked that day) and continues for the new meter from that day; a period that starts later belongs to the new
  meter alone. For a meter that is not a plot meter, the meters under it move under the new one: a period that started
  before the day ends on the day before and continues under the new meter from the day (one parent at a time,
  `meter_parent_no_overlap_ex`; the ended rows are flushed before the new ones are inserted); a period that starts on
  or after the day is put under the new meter. Earlier periods are kept, so a past range shows the tree as it was.
- **Range consumption** of a meter is `ConsumptionCalculator.rangeConsumption(chain, from, to)` over its chain
  (`MeterChains.chainOf`: the meters it replaced, back to the first, and the meters that replaced it, oldest first),
  each meter clipped to the days it worked; only approved readings. `known: false` with `lastReadingOn` when a part has
  no point after its end; `calculated` when an end was interpolated; the consumption is rounded once
  (`Quantity.toMeterValue`). 400 `VALIDATION_FAILED` with `to` `INVALID` when `to` is before `from`. The tree of slice 5
  reuses `MeterChains`.

### Audit of a replacement

Two entries: `METER_REPLACED` about the old meter (`removedOn`, `finalReading`, `replacementMeter` = the serial number of
the new meter, user content) and `METER_REGISTERED` about the new meter (the fields of a registration), so the log of each
meter card is complete. A refused replacement writes nothing.

## The member flow (slice 4)

Internal: `ApprovalQueries`; the commands are in `ReadingCommands` (`submit`, `approve`, `reject`).

| Endpoint | Operation | Who |
|---|---|---|
| `POST /plots/{id}/readings` | `submitPlotReading` | a current owner of the plot (`MeterAccess.ownsNow`), whatever their permissions; anyone else, an unknown plot and one of another cooperative the same 404 `PLOT_NOT_FOUND`, the platform operator 403 |
| `GET /readings?status=PENDING` | `listReadings` | `READINGS_APPROVE`; the meter's `note` only with `METERS_VIEW` |
| `POST /readings/{id}/approve` | `approveReading` | `READINGS_APPROVE` |
| `POST /readings/{id}/reject` | `rejectReading` | `READINGS_APPROVE` |

- **Submission** (`meterId`, `takenOn` or today, `value`): an active meter of that plot (another meter is 404 with
  `errors[{field: meterId}]`, a removed one 409 `METER_REMOVED`), checked by `ReadingValidator` against the counted
  readings like an entry (a counted reading of the same date is not a neighbour). The row is `PENDING`, source `MEMBER`,
  `entered_by_member_id` the member. A pending reading of the same meter and date is superseded by it (the same value
  again changes nothing and is not audited). A pending reading never supersedes a counted one and never reaches the
  calculation: intervals, the register's `lastReading`, the round and range consumption read approved readings only.
  201 with `ReadingSaved` (no interval, the warnings it would carry).
- **Approval** locks the meter, takes only a `PENDING` reading (else 409 `READING_STATUS_INVALID`), checks it again
  against the counted readings as they are now (`READING_DATE_OUTSIDE_METER`, `READING_OUT_OF_ORDER`), asks the
  `ReadingUsage` ports about the span around its date (`READING_IN_USE`), then supersedes a counted reading of the same
  date (flushed out of `APPROVED` first, then linked) and sets `APPROVED` with `decided_by_member_id` and `decided_at`.
  The source stays `MEMBER`. 200 with the interval it now closes and its warnings. Approval has no recalculation step
  (feature 012): a pending reading that falls inside a charged interval stays 409 `READING_IN_USE`; the board enters
  the reading on the meter card instead (a back-dated entry, which goes through the recalculation step) and rejects the
  member's pending one.
- **Rejection** needs a `reason` (trimmed, 1 to 500 characters; blank is 400 `REQUIRED`), takes only a `PENDING`
  reading, sets `REJECTED` with the reason and the decider. The member reads the reason in the meter's history
  (`rejectReason`). The reason is user content: it is stored and audited, never logged.
- **The approvals list** reads every pending reading of the cooperative (index `meter_reading_pending_idx`), oldest date
  first, with its meter and, when the reading fits between the counted readings now, the interval it would close and its
  warnings. Only `status=PENDING` is supported (400 `INVALID` on `status` otherwise). Bulk loads: the meters, plots,
  latest and approved readings.

### Audit of the member flow

Entity type `METER_READING` about the meter, as for the board's readings: `READING_SUBMITTED` (`takenOn` new, `value`
new and, when it replaced a pending reading, old), `READING_APPROVED` (`takenOn` and `value` new and, when it replaced a
counted reading of that date, old), `READING_REJECTED` (`takenOn`, `value` and `reason` new). The actor of a submission
is the member.

## The tree (slice 5)

Internal: `TreeRules` (pure: which kind may be under which), `TreeCommands` (the move), `MeterParents.move` (the rows),
`TreeQueries` (the tree over a range), `MeterTreeController` (tag `meter-tree`).

| Endpoint | Operation | Who |
|---|---|---|
| `PUT /meters/{id}/parent` | `setMeterParent` | `METERS_VIEW` and `METERS_MANAGE` |
| `GET /meter-tree?from&to` | `getMeterTree` | `METERS_VIEW` |

- **Rules** (`TreeRules`): a substation has no parent; a quarter meter is under a substation; a plot meter and a
  common consumer are under a substation or a quarter meter; water meters have no tree. The kinds alone keep the tree
  at most three levels deep and free of cycles. 409 `METER_TREE_INVALID` with one field error naming the rule: on
  `parentMeterId` `TREE_WATER`, `TREE_QUARTER_UNDER_QUARTER`, `TREE_PARENT_KIND` (any other pair, the meter itself and
  any parent of a substation included), `TREE_PARENT_INACTIVE` (the parent is removed or installed after the date); on
  `effectiveFrom` `TREE_DATE_BEFORE_LATEST` (before the meter's latest change) or `READING_DATE_OUTSIDE_METER` (before
  its installation). A date after today is 409 `READING_DATE_IN_FUTURE`, a removed meter 409 `METER_REMOVED`, an
  unknown parent 404 with `errors[{field: parentMeterId}]`.
- **A move** (`effectiveFrom`, `parentMeterId` or none to take the meter out of the tree) keeps the history in
  `meter_parent`: the lasting period ends the day before and a new one starts on the date. On the start date of the
  latest period the move corrects it (a new parent, or the row goes when the meter leaves the tree, or it joins the
  period before when that one had the new parent and ended the day before); on the day after a period that ended (the
  meter left the tree) a return to the same parent lets that period last again. The history before the latest change
  is never rewritten. The same parent again changes nothing and writes no audit entry. Rows that end or go are flushed
  before a new row is inserted (`meter_parent_no_overlap_ex`). The children of a moved meter stay under it.
- **Locks**: the new parent `FOR SHARE`, then the meter `FOR UPDATE` (a parent before its children, as a replacement).
  A removal or replacement of the parent takes it `FOR UPDATE`, so the two run one after another.
- **The tree** (`TreeQueries`, four queries: electricity meters, parent rows, approved readings, plots). A node is a
  chain of meters (`MeterChains.chainsAmong`), so a replacement does not split it; `meter` is the chain's meter
  installed last by the end of the range, `meterIds` the chain. The parent rows of the chain's meters, with parents read
  as chains, become its placements: clipped to the days it worked (up to the day before its final removal), a
  replaced meter's period cut where its successor's starts, neighbouring periods under the same parent joined, the days
  without a parent among the roots. A node is listed under every parent it had in the range with the slices of
  `ConsumptionCalculator.splitByParent` (neighbouring slices share their boundary day); its children are looked for
  within those slices only. Each slice has the value at its start and end (`REAL`, `CALCULATED` rounded once to three
  decimals, `UNKNOWN` with `lastReadingOn`; on a replacement day the start reads the new meter, the end the old one) and
  its consumption; `total` adds up the slices exactly. Order: substations, quarter meters, common consumers by name,
  then plot meters in street order. `TreeQueries.nodes(from, to)` is the one calculation (internal `TreeNode`,
  `TreeSlice`, `NodeValue`): the endpoint maps it to the API model and `MeterReports.tree` to its public records, so
  the two cannot drift (`MeterReportsTests` compares them node by node).

### The tree for the reports (feature 013)

`MeterReports.tree(from, to)` (implemented by `internal.JpaMeterReports`) gives the electricity report the tree of
`GET /meter-tree` without reading the meter tables. It runs in the **caller's transaction** (`Propagation.MANDATORY`,
RLS-bound); `to` before `from` is an `IllegalArgumentException`. The answer `MeterTreeReport(from, to, rows)` lists one
`MeterTreeReportRow` per node and parent, depth first in the order of the tree, with:

- `level` (0 for a root), `parentMeterId` (the meter the parent node shows), `meterId`, `meterIds`, `kind`, `serial`,
  `name`, `plotId`, `plotNumber`, `street`, `installedOn`, `removedOn`, `active`: the endpoint's node and meter;
- `slices`: `MeterTreeReportSlice(from, to, start, end, consumption)` with `MeterTreeValue(kind, meterId, value,
  lastReadingOn)` (`MeterValueKind` `REAL`, `CALCULATED`, `UNKNOWN`; the value an exact `Quantity`, rounded only for
  display, null when unknown) and the slice's consumption;
- `consumption`: the node's total over its slices;
- `childrenConsumption` (substations and quarter meters only, null for the other kinds): the exact sum of the
  children's totals under the node, zero without children, calculated when any child's is;
- `losses` (substations and quarter meters only): `MeterTreeLosses(kwh, percent)`, consumption minus the children's
  sum (exact, possibly negative), and the percent of the consumption rounded HALF_UP to two decimals for display (null
  when the consumption is not above zero), as the losses runs show it (money rules 6.3);
- `fedPlotIds` (`plotsFed()` its size): the distinct plots of the plot meters in the node's subtree under this parent,
  itself included (a plot meter feeds its own plot, a common consumer none). Members fed are not known here; the
  caller counts them through `plots`.

A quantity is a `MeterTreeQuantity(kwh, calculated, reason, lastReadingOn)`: known, or unknown with a
`MeterTreeUnknownReason` (`VALUE_UNKNOWN`: the node's own value at an end; `CHILD_VALUE_UNKNOWN`: a child's
consumption) and the latest date after which that meter has no reading. An unknown figure is never zero; the losses are
unknown with the node's reason first, else the children's. The derived figures are pure (`TreeFigures`,
`TreeFiguresTests`). Test: `MeterReportsTests` (a substation with two quarter meters, a common consumer read off
interpolated values, a plot meter replaced in the range, a quarter meter without a reading after the range, the
fully known tree, the endpoint's payload for three ranges, another cooperative, invalid range, the caller's
transaction).

### Water use and missing readings for the reports (feature 013)

Both run in the **caller's transaction** (`Propagation.MANDATORY`, RLS-bound) like `tree`; `to` before `from` (and a
`limit` below 1) is an `IllegalArgumentException`.

- `waterUse(from, to, source)` (`internal.WaterUseQueries`; the water report, 013 §3.5): one `WaterUseRow` per plot
  and water source (`WaterSourceKind` `COOPERATIVE_SUPPLY` or `METER`, a well, with its meter id and name) it was
  connected to on at least one day of the range (`plot_water_connection`, `findAllOverlapping`); a plot that changed
  source has a row per source, a plot never connected in the range none (money rules 11). `source` keeps only one kind
  (null: both). Each row has the clipped `periods`, `connectedDays` (both ends counted) and the m³ of those days: the
  range consumption of 010 restricted to the connected days, by `ConsumptionCalculator.splitByParent` with the plot's
  connections as the periods, so each connection is a tree slice (`max(from, valid_from)` to `min(to, valid_to + 1
  day)`, money rules 6.4): connected for the whole range is exactly the range consumption, and the slices of a change
  of source add up to it. The meters are the plot's water meters that worked on a day of a slice, each clipped to its
  own working days (a replacement adds up); `meterId`/`meterSerial` show the one installed last. No such meter: no
  m³ and no reason (nothing measured, an empty cell). A value not known at an end of a slice: `cubicMetres` null with
  `MeterTreeUnknownReason.VALUE_UNKNOWN` and `lastReadingOn`, never zero; `calculated` when a value was interpolated.
  Rows in the street order of the plots, a plot's sources by their first connected day. Archived plots and common
  land are listed when they have such a connection (the caller decides). Queries: the connections, the water meters,
  their approved readings, the wells' names and the plots (bulk, 500 ids per `IN` list).
- `missingReadings(from, to, resources, kinds, plotIds, limit)` (`internal.MissingReadingQueries`; the missing
  readings report, 013 §3.6): one `MissingReadingRow` per meter of the resources and kinds (empty: all) that worked
  through the whole range (installed before `from`, not removed on or before `to`) and has no approved reading dated
  `from`..`to`. **An installation or removal in the range counts as a point** (its initial or final value), as the
  register's `noReadingSince` filter and `MeterNetwork.staleMeters` count it, so such a meter is not listed. A water
  meter is listed only when its plot was connected to water on a day of the range (money rules 11). Each row: resource,
  kind, plot (id, number, street) or name, serial, installation, the last point before the range (the latest approved
  reading, else the initial value with `lastReadingInitial`), the days from it to `to`, and `pendingApprovalInRange`
  (a `PENDING` reading dated in the range; pending, rejected and superseded readings never count as a reading).
  `plotIds` (a street resolved by the caller) keeps the plot meters of those plots only, 500 per query; null means
  every meter. One native query (lateral latest reading, `exists` for pending, approved and the connection), then the
  meters and plots in bulk; rows in the order of the register (plot meters in street order, electricity before water;
  then the other kinds in enum order and by name, Ukrainian collation), at most `limit`.
- Test: `MeterReportsWaterAndMissingTests` (a whole-range connection, a connection from mid-range with interpolated
  m³, a change of source whose slices add up, a plot connected only before the range, the source filter, a meter
  without a reading after the range, a plot without a water meter; a meter read in the range, a pending reading, a
  water meter of a plot not connected, meters installed after, in and removed before the range, the initial value as
  the last point, kinds, resources, plots and the limit; another cooperative, invalid arguments, the caller's
  transaction).

### Audit of a move

`METER_PARENT_CHANGED` about the moved meter (entity type `METER`): `parent` old and new (the labels of the parents,
user content; absent outside the tree) and `effectiveFrom` (ISO date).

## The Excel sheet (slice 6)

`MeterImportService` implements `MeterImport` for the `Лічильники`/`Meters` sheet (rules and codes in `imports/README.md`,
section Meters). Only plot meters; the import never replaces or updates a meter. `plan` resolves the plot from the Plots
sheet's plan or `PlotCatalog.findActiveByNumbers`, loads the active meters of those plots in one query and decides `NEW`,
`UNCHANGED` (same serial ignoring case) or `METER_ACTIVE_EXISTS` (`params.plot`). `lock` takes the stored plots of new
meters `FOR SHARE` (`PlotCatalog.findAllAndLockShared`, id order) and the unchanged meters `FOR UPDATE`. `apply`
persists the new meters with the ids of the plan (derived from the import id, plot number and resource; Hibernate batches
the inserts), maps `meter_active_plot_uq` at the flush to 409 `IMPORT_STALE` and returns one `METER_REGISTERED` event per
meter (`MeterAuditPublisher.event`, the same fields as a registration by hand) for the import to publish in its batches.
Tests: `ImportPreviewMetersTests`, `ImportCommitTests` (in the imports module), `MeterCatalogTests`.

## Water connections (feature 011, slice 5)

Table `plot_water_connection` (V16): `plot_id`, `source_meter_id` (a common consumer meter, a well pump; null = the
cooperative's water supply), `valid_from`, `valid_to` (both connected days; null while the connection lasts). RLS
`tenant_isolation`, `plot_water_connection_tenant_idx`, composite foreign keys (the plot `ON DELETE CASCADE`, the source
`ON DELETE RESTRICT`), the period CHECK, the exclusion constraint `plot_water_connection_no_overlap_ex` (a plot is
connected once on any day, 23P01) and the partial unique index `plot_water_connection_active_uq` (one lasting
connection, 23505); `DELETE` and `TRUNCATE` revoked from `nadel_app` (the history is kept, a disconnection ends the
row). Tests: `WaterConnectionTenantIsolationTests`.

Internal: `WaterConnectionRecord`, `WaterConnectionRepository`, `WaterConnectionCommands`, `WaterConnectionQueries`,
`JpaWaterConnections`, `WaterConnectionImportService`, `WaterController` (tag `water`).

| Endpoint | Operation | Who |
|---|---|---|
| `GET /plots/{id}/water-connections` | `getPlotWaterConnections` | `PLOTS_VIEW` for any plot, otherwise a current owner of the plot; anyone else, an unknown plot and one of another cooperative the same 404 `PLOT_NOT_FOUND`, the platform operator 403 |
| `POST /plots/{id}/water-connections` | `connectPlotWater` | `METERS_MANAGE` |
| `POST /plots/{id}/water-connections/{connectionId}/end` | `endPlotWaterConnection` | `METERS_MANAGE` |
| `GET /water-sources` | `listWaterSources` | `PLOTS_VIEW` |
| `POST /water-sources/{sourceId}/connections` | `connectWaterSourcePlots` | `METERS_MANAGE` |
| `POST /water-sources/{sourceId}/disconnections` | `disconnectWaterSourcePlots` | `METERS_MANAGE` |

- **A plot's connections**: newest first, `connected` (today in Europe/Kyiv) and `current` (the connection of today).
  Each connection names its source (`id` `cooperative` or the meter id, `kind` `COOPERATIVE_SUPPLY` or `METER`, the
  meter's `name`), `from` and `until`.
- **Connect** (`sourceMeterId?`, `from`): the plot locked `FOR SHARE` (404 `PLOT_NOT_FOUND`, 409 `PLOT_ARCHIVED`, 409
  `PLOT_COMMON_LAND`); a source that is not an active common consumer meter (unknown, another kind, removed) 409
  `WATER_SOURCE_INVALID` on `sourceMeterId`; the plot's connections `FOR UPDATE`: a lasting one or one that reaches
  `from` is 409 `WATER_ALREADY_CONNECTED` on `from` (and the constraints, mapped at the flush, for two at once). 201
  with the plot's connections.
- **End** (`until`, the last connected day): 404 `NOT_FOUND` for a connection that is not the plot's, 409
  `WATER_NOT_CONNECTED` when it has ended already, 400 `INVALID` on `until` before its `from`.
- **Water sources**: `cooperative` first, then the active common consumer meters by name (Ukrainian collation), each
  with the plots whose connection to it has not ended before today, in the natural order of the numbers. **Bulk**
  connect and disconnect are all or nothing: `plotIds[i]` `DUPLICATE`, `UNKNOWN_PLOT`, and for a connection
  `PLOT_ARCHIVED`, `PLOT_COMMON_LAND` (400); `plotIds` `REQUIRED` when empty; 409 `WATER_ALREADY_CONNECTED` or
  `WATER_NOT_CONNECTED` (no lasting connection to this source) with `plotIds[i]`; `until` before a plot's `from` is 400
  `INVALID` on `plotIds[i]`. The path's `sourceId` is `cooperative` or a meter id: unknown or malformed 404, not a
  common consumer meter 409 `WATER_SOURCE_INVALID` (a removed one too, for a connection only). Answer: the source.
- **Missing readings**: a water meter of a plot that is not connected to water is never missing readings: the
  `noReadingSince` filter of the register and the round leaves it out unless the plot is connected today, and
  `MeterNetwork.staleMeters` unless it is connected on the check date. Without the filter the meter stays in the
  register and the round.
- **Delete guard**: a meter that plots are or were connected to as their water source is not deleted, 409
  `WATER_SOURCE_IN_USE` (the foreign key is the backstop).
- **Audit** (entity type `PLOT`, entity id the plot, label its number): `WATER_CONNECTED` (`source`, `from`),
  `WATER_DISCONNECTED` (`source`, `from`, `until`); `source` is the meter's name (user content) or
  `base:COOPERATIVE_SUPPLY`. One entry per plot of a bulk change. Guarded by `WaterAuditActionsTests` (the actions
  are values of the contract's `AuditAction`).
- **The Excel import** (`WaterConnectionImport`, slice 8): the optional `water` column of the Plots sheet. The import
  passes only the cells that ask for a connection: a yes word is the cooperative's water supply, any other text the
  name of an active common consumer meter; a no word and an empty cell never reach the facade, so the import never
  disconnects a plot. Plan, per row with the rules of a connection by hand: a plot whose Plots row has a problem is
  not checked; common land (the row's kind, else the stored one) is `PLOT_COMMON_LAND`; a name matches the active
  common consumer meters ignoring case and extra spaces (`WATER_SOURCE_INVALID` for none, `WATER_SOURCE_AMBIGUOUS` for
  two or more); a plot of the file is `NEW`; a stored plot with a lasting connection is `UNCHANGED` for a yes (to any
  source) or the name of its current source, `WATER_ALREADY_CONNECTED` for another source; a stored plot with a
  connection that ends today or later is `WATER_ALREADY_CONNECTED`; any other plot is `NEW`. Lock: the stored plots of
  the `NEW` rows `FOR SHARE`, then the connections of every planned plot `FOR UPDATE` (the order of a connection by
  hand). Apply: the `NEW` plots are connected from the day of the commit (Europe/Kyiv); a constraint violation is 409
  `IMPORT_STALE`; one `WATER_CONNECTED` event per plot is returned to the import, which publishes it in an
  `AuditBatch`.
- Tests: `WaterConnectionsTests` (history, one lasting connection, overlap 409, bulk, sources, permissions, audit,
  missing readings, the facade, the delete guard), `MeterNetworkTests` (stale water meters); the import column in
  `imports` (`ImportPlotsWaterTests`).
- **Known gaps (not guarded, review of slice 5).** A well meter that is removed keeps the lasting connections to it,
  and the settings page lists only active wells, so those connections can be ended only on each plot card. A replaced
  well meter keeps its connections on the old meter's id (a run of the pump reads the whole chain,
  `WaterConnections.connectedToSource` per meter). Archiving a plot or turning it into common land while it has a
  lasting connection is refused since feature 014 (`WaterConnectionPlotGuard`, see Plot guard).

## Permissions

`METERS_VIEW` (reads; the Treasurer has it), `METERS_MANAGE` (register, replace, remove, delete, the tree; writes need
`METERS_VIEW` and `METERS_MANAGE`), `READINGS_ENTER`, `READINGS_APPROVE`. The Chairman has all four. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V12); existing cooperatives got the rows by targeted inserts (see
`MetersMigrationTests`).

## Demo data

The repeatable script `db/demo/R__demo_data.sql` (block `-- BEGIN demo meters` ... `-- END demo meters`, Ukrainian text, fixed or
derived ids, every insert `ON CONFLICT DO NOTHING`, dates relative to `CURRENT_DATE`) loads, for the demo cooperative:

- 5 network meters: "Підстанція №1" (substation) with "Квартал А" and "Квартал Б" (quarters), "Свердловина №1" (a common consumer
  under the substation) and "Освітлення вул. Вишнева" (a common consumer under quarter A);
- 29 electricity plot meters in the tree (plots 1-14 and 12а under quarter A, 15-29 under quarter B; plots 25 and 28 have no
  meter) and 4 water meters (plots 1, 7, 15, 22, outside the tree). Plot 9's meter was replaced 30 days ago (old meter removed
  with a final value, the new one `replaces_meter_id`); plot 20's last reading is 75 days old;
- 186 readings with irregular gaps of 6-55 days over about 120 days (any range that starts or ends between two readings is an
  interpolation case): 183 approved (plot meters, the node meters on the plot rounds' days, the well and the lighting,
  the water meters), 1 pending (member@demo.local's plot 7, for the approvals screen), 1 rejected (plot 1) and 1
  superseded (plot 2, replaced by its correction);
- the other cooperative: one meter and one reading (isolation checks);
- the permissions `METERS_VIEW` and `READINGS_ENTER` for the custom role "Електрик" and its description
  "Вносить покази лічильників на обході" (the earlier text is replaced only while the chairman has not edited it).

`DemoMeterDataTests` asserts these numbers. Tests that need an empty meter state call `DemoMeterData.clear` and
`DemoMeterData.restore` (like `DemoTariffData`); the rest of the meter tests use throw-away data (serials `MT-...`).
`DemoMeterData.clear` first removes the demo ledger (`DemoLedgerData.clear`), whose rows refer to meters.

The block `demo ledger` (feature 011; runs only in the dev profile, see the ledger README) adds to the meters: the water
connections of the demo cooperative (every plot to "Свердловина №1" since 01.05.2020 except 12а, 27 and 29, plot 19
only from the 12th day before the readings' anchor; so the four water meters are in the missing-readings filters again)
and a meter of plot 25 installed on 15.12.2025 under quarter Б with one reading 5 days before the anchor (its interval
starts before the first electricity tariff version and is the demo's "not charged" item). The template readings and
the node readings of this block leave plot 25 out, so a re-run gives the same values whether or not that meter exists.
In the dev demo the readings of the charged intervals and of the runs are in use (`READING_IN_USE` on a correction;
the chairman corrects them with the recalculation step, the electrician sees "Показ уже нараховано — зверніться до
голови").
