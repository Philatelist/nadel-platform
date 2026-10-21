# meters

The cooperative's meters, the electricity meter tree, the readings taken on any date and the consumption calculated from
them (feature 010, ADR 0014). Nothing is charged here; electricity charges, losses and the split of common consumers arrive
with feature 011, which reuses `ConsumptionCalculator` and feeds the exact `Quantity` to money.

Status: slice 1 of spec 010 is in code (tables, permissions, the pure calculation, the `ReadingUsage` port). Entities,
commands, queries and endpoints follow in the later slices and extend this file.

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

## Permissions

`METERS_VIEW` (reads; the Treasurer has it), `METERS_MANAGE` (register, replace, remove, delete, the tree; writes need
`METERS_VIEW` and `METERS_MANAGE`), `READINGS_ENTER`, `READINGS_APPROVE`. The Chairman has all four. Defined in
`identity.Permission`, seeded by `seed_base_roles` (V12); existing cooperatives got the rows by targeted inserts (see
`MetersMigrationTests`).
