# meters

The cooperative's meters, the electricity meter tree, the readings taken on any date and the consumption calculated from
them (feature 010, ADR 0014). Nothing is charged here; electricity charges, losses and the split of common consumers arrive
with feature 011, which reuses `ConsumptionCalculator` and feeds the exact `Quantity` to money.

Status: slices 1 to 4 of spec 010 are in code: tables, permissions, the pure calculation, the `ReadingUsage` port, the
meter register (entity, commands, queries, endpoints, the plot guard, audit), readings with the round (entry, batch,
superseding, correction, deletion, the unusual-consumption warning, the history with intervals), and replacement, range
consumption over a chain of meters and the member flow (submission, approval, rejection). The tree endpoints and the
Excel sheet follow in the later slices and extend this file.

Depends on `plots` (`PlotCatalog`, and it implements `plots.PlotChangeGuard`), `identity` (`CurrentUser`, `AccessProfile`,
`OwnedPlotsSource`, `Permission`), `audit` (events) and `problem`. `plots` never depends on `meters`.

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
| `Quantity` | An exact reduced fraction (`BigInteger` over `BigInteger`): `plus`, `minus`, `times(long)`, `dividedBy(long)`, `compareTo`, `signum`, `numerator()`, `denominator()`. `toMeterValue()` rounds HALF_UP to three decimals: the only rounding of the module, for display (ADR 0014) |
| `ConsumptionCalculator` | Pure and static (no Spring, no database, no clock); see below |
| `ReadingUsage` | Port: `isUsed(tenantId, meterId, from, to)`; the ledger (011) implements it, `internal.NoChargesYetUsage` answers `false` until then. Every bean is asked; the readings are used when any says so |

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
| `GET /plots/{id}/meters` | `listPlotMeters` | `METERS_VIEW` for any plot, otherwise the plot's current owner (without `note`); anyone else 404, the platform operator 403 |

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
  period of its meter.
- **Delete** locks the meter: a removed meter is 409 `METER_REMOVED`, a meter with a reading of any status 409
  `METER_HAS_READINGS` (the `meter_reading` foreign key is the backstop). `deletable` in the API says when it is allowed.
  A meter that is somebody's parent in the tree is not checked yet (slice 5, `METER_HAS_CHILDREN`).
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
- **Use check.** Before a change every `ReadingUsage` bean is asked about the span from the previous to the next point
  around the date (for a correction around both the old and the new date); any "yes" is 409 `READING_IN_USE` (a row
  error in a batch). `changeable` in the history says when a reading can still change.
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
the same date, `value` old and new), `READING_CORRECTED` (`takenOn` and `value` old and new), `READING_DELETED`
(`takenOn`, `value` old). Values are ISO dates and readings with three decimals.

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
| `POST /plots/{id}/readings` | `submitPlotReading` | a current owner of the plot (`MeterAccess.ownsNow`), whatever their permissions; anyone else 404, the platform operator 403 |
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
  The source stays `MEMBER`. 200 with the interval it now closes and its warnings.
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

## Permissions

`METERS_VIEW` (reads; the Treasurer has it), `METERS_MANAGE` (register, replace, remove, delete, the tree; writes need
`METERS_VIEW` and `METERS_MANAGE`), `READINGS_ENTER`, `READINGS_APPROVE`. The Chairman has all four. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V12); existing cooperatives got the rows by targeted inserts (see
`MetersMigrationTests`).
