# 010. Meters and readings

Roadmap phase 2, feature 2 of 5. Input for `/feature-pipeline`. Requires 006 (plots), 007 (ownership, cooperative
settings page), 008 (Excel import) and 009 (charge types and tariffs: `PER_KWH`, `PER_M3`).

## 1. Goal

The cooperative keeps a register of its meters and their readings: plot meters, the substation and quarter meters and
the common consumers (well pumps, street lighting) arranged in a tree that the chairman configures. The electrician
enters readings on a simple screen; a member can submit the reading of their own meter for approval. Readings are
taken on any date, not on a fixed schedule: the server calculates consumption between consecutive readings and, for the
board, over any date range. Nothing is charged yet: electricity charges, losses and the split
of common consumers are feature 011.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/money-rules.md` (sections 5, 6, 6.1, 6.2, 6.3), `docs/domain/glossary.md` (meter terms, Electrician),
`docs/features/008-excel-import.md`, `docs/features/009-charge-types-and-tariffs.md` (section 2.3 and the
`TariffVersionUsage` port as the pattern for "editable until a charge uses it"), ADRs 0001, 0002, 0006, 0007, 0009,
0011, 0012 and the design reference `docs/design/screens/`.

Phase 2 sequence: `009 Charge types and tariffs` → `010 Meters and readings` → `011 Ledger, charges and period
closing` → `012 Retroactive recalculation` → `013 Reports`.

General rules as in phase 1: contract-first, Flyway only, tenant-scoped tables with RLS and an isolation test,
permissions not role names, Problem Details with a `code`, one UI language at a time (Ukrainian by default, English
available, every text through the i18n catalogs), admin changes audited through the outbox, unit tests for every
consumption rule, coverage floor raised to the new measured level.

## 2. Domain

### 2.1 Meters

- A meter measures one resource: `ELECTRICITY` (kWh) or `WATER` (m³). Water meters can be registered now; water
  charges stay off while the `WATER` charge type is inactive (009).
- Meter kinds: `PLOT` (belongs to one plot), `SUBSTATION` (root of an electricity tree), `QUARTER` (feeds a group of
  plots), `COMMON_CONSUMER` (well pump, street lighting, office).
- A meter has: kind, resource, serial number, a name for non-plot meters ("Свердловина №1", "Освітлення вул. Вишнева"),
  installation date, initial reading, removal date and final reading (when removed), optional note.
- Readings are decimals with up to 3 places (`numeric(14,3)`, JSON string `MeterValue`, pattern
  `^\d+(\.\d{1,3})?$`): electricity is usually whole kWh, water needs litres.
- A plot of kind `PLOT` has zero or one active meter per resource (money-rules 6.1). Common land never has a `PLOT`
  meter.
- A meter with readings is never deleted; it is removed (with a date and a final reading) and keeps its history. A
  meter entered by mistake without readings can be deleted.

### 2.2 The meter tree (electricity)

- The chairman builds the tree in the cooperative settings (money-rules 6.3): substations at the root; under a
  substation, quarter meters, common consumers and plot meters; under a quarter meter, plot meters and common
  consumers. Depth: substation → quarter → plot (no quarter under a quarter in this feature).
- A meter's parent is changed by the chairman (a plot moved to another quarter) with an effective **date**; the
  history of parents is kept so a past date range is calculated with the tree as it was then.
- A new meter installed by replacement takes the parent of the meter it replaces.
- A cooperative without a configured tree is valid: plot meters without a parent. Losses then need the substation meter
  only (011).
- Water meters have no tree in this feature.

### 2.3 Replacement

One operation (money-rules 6.2): date, final reading of the removed meter, serial number and initial reading of the new
meter. A counter that wraps around (99 999 → 00 012) is a replacement of the meter by itself: same serial, final
reading 99 999, initial reading 0.

### 2.4 Readings

Readings are not tied to months or billing periods: an electrician may come after a month, after three months or
after a few days. A reading is a value on a date.

- A reading has a meter, a value and the **date it was taken** (`taken_on`). The person entering it sets the date; the
  field is prefilled with today and shows a quiet hint next to it, not a dialog: "Дата показу — сьогодні,
  21.10.2026. Змініть, якщо знімали в інший день" (en: "Reading date is today, 21.10.2026. Change it if the meter was
  read on another day"). On the electrician's list one date applies to the whole round and can be changed for a single
  row.
- The date cannot be in the future, before the meter's installation or after its removal.
- One reading per meter and date. Entering a reading for a date that already has one replaces it: the old one is kept
  with status `SUPERSEDED` and a link to the new one.
- Sources and statuses:
  - entered by someone with `READINGS_ENTER` (electrician, board) or by import: `APPROVED` at once;
  - submitted by a member for their own plot meter: `PENDING` until someone with `READINGS_APPROVE` approves it or
    rejects it with a reason (money-rules 6). A pending reading is never used for consumption.
- Validation, against the approved readings of the same meter ordered by date:
  - lower than the previous reading or higher than the next one: rejected (409 `READING_OUT_OF_ORDER`); a lower value
    is only valid as a new meter's initial reading (replacement). A reading dated between two existing ones is allowed
    if it fits between them;
  - average daily consumption since the previous reading more than 3 times the meter's average daily consumption over
    the last 180 days: accepted with a warning shown to the person entering it ("Перевірте показ: споживання значно
    більше звичайного").
- Correcting or deleting a reading: allowed while no charge uses it; a `ReadingUsage` port returns "not used" in this
  feature, 011 implements it (same pattern as `TariffVersionUsage` in 009). Once used, a correction goes through
  recalculation (012).

### 2.5 Consumption (server only)

```
interval consumption  = reading − previous approved reading (or the initial reading if none)
                        for the days from the previous reading's date to this reading's date

range consumption(meter, from, to)
                      = reading at "to" − reading at "from"
reading at a date     = the approved reading on that date; if there is none, the value interpolated linearly by days
                        between the nearest readings before and after it; if there is no reading after it, unknown
```

- Interval consumption is what a member and the electrician see on a meter: "з 03.11 по 28.01: 412 кВт·год".
- Range consumption is for the board: the tree screen and later losses (011) and reports (013) use a date range the
  user sets ("з … по …"). A value obtained by interpolation is marked "розрахунковий" next to the real readings, so
  nobody mistakes it for a reading; a meter whose reading at "to" is unknown is listed as "немає показу після <date>".
- An interval or a range containing a replacement sums over the meters of that plot (money-rules 6.2).
- No reading for a long time: the electrician's list can be filtered by "без показу з <date>" (default: older than 60
  days); a plot without an active meter is never on it.
- Losses per node, the split of common consumers and how intervals become charges are **not** in this feature (011).

## 3. Worked examples (each becomes a unit test)

| Case | Input | Expected |
|---|---|---|
| Normal interval | 03.09: 8 140, 05.10: 8 205 | interval 03.09–05.10: 65 kWh |
| Long interval | 03.09: 8 140, next reading 28.01: 8 552 | interval 03.09–28.01: 412 kWh, nothing in between |
| Replacement | old: 03.09 8 140, removed 20.09 at 8 205; new: installed 20.09 with 3, 05.10: 69 | 03.09–05.10: 65 + 66 = 131 kWh |
| Counter wrap-around | 03.09: 99 950, self-replacement 15.09 final 99 999, initial 0, 05.10: 12 | 03.09–05.10: 49 + 12 = 61 kWh |
| Lower reading | 03.09: 8 140, 05.10 entered 8 100 | rejected, `READING_OUT_OF_ORDER` |
| Reading between two others | 03.09: 8 140, 05.10: 8 205, then 20.09 entered 8 170 | accepted; intervals 03.09–20.09: 30, 20.09–05.10: 35 |
| Installed recently | installed 10.10 with initial 0, 31.10: 120 | 10.10–31.10: 120 kWh |
| Range with interpolation | 01.10: 1 000, 31.10: 1 300; range 01.10–16.10 | reading at 16.10 = 1 000 + 300 × 15/30 = 1 150 (розрахунковий); consumption 150 kWh |
| Range without a later reading | last reading 05.10; range to 31.10 | consumption unknown, "немає показу після 05.10" |
| Member reading | member submits 28.10: 8 210, pending | ignored until approved; after approval a new interval 05.10–28.10 |
| Same date twice | 05.10: 8 205 entered, then 05.10: 8 250 | 8 205 `SUPERSEDED`, interval uses 8 250 |
| Default date | the electrician does not touch the date field | reading saved with today's date; the hint was shown |
| Future date | date 25.10 entered on 21.10 | rejected |
| Unusual consumption | 180-day average 2 kWh/day, new interval 10 days with 400 kWh | accepted with the warning |
| Plot moved to another quarter | plot moved from quarter A to B on 15.10; range 01.10–31.10 | plot's consumption 01.10–15.10 counted under A, 15.10–31.10 under B |
| Plot without a meter | no active meter | not on the electrician's list, no consumption, not "без показу" |

## 4. API (`api/openapi.yaml`)

- `GET/POST /meters`, `GET/PUT/DELETE /meters/{id}` (delete only without readings), `POST /meters/{id}/remove`,
  `POST /meters/{id}/replace`, `PUT /meters/{id}/parent` (parent and effective date).
- `GET /meter-tree?from=2026-10-01&to=2026-10-31`: the tree as it was in that range, with range consumption per
  meter (real or interpolated, see 2.5).
- `GET /readings/round?street=…&noReadingSince=…`: the electrician's list — every active meter with its last reading
  (value and date), consumption and status.
- `GET /meters/{id}/readings`: readings with interval consumption.
- `POST /readings` (one or a batch for the list screen, each with `takenOn`), `PUT` and `DELETE /readings/{id}`
  (correction rules of 2.4),
  `POST /readings/{id}/approve`, `POST /readings/{id}/reject` (reason).
- `GET /plots/{id}/meters` with readings history; `POST /plots/{id}/readings` for a member's own meter (pending).
- `GET /plots/{id}/tariffs` (009): `PER_KWH` and `PER_M3` tariffs now say whether the plot has an active meter of that
  resource instead of the 009 placeholder.

## 5. Data

New migration: `meter`, `meter_parent` (history of parents with the effective date), `meter_reading` (`taken_on`
date, value, source, status, author, link to the superseding reading). All
tenant-scoped with RLS, indexes on `tenant_id`, isolation tests. Constraints: one active `PLOT` meter per plot and
resource; one approved reading per meter and date; a `PLOT` meter only on a plot of kind `PLOT`.

## 6. Permissions

- New: `METERS_VIEW`, `METERS_MANAGE` (register, replace, remove, tree), `READINGS_ENTER`, `READINGS_APPROVE`.
- Chairman base role: all four. Treasurer: `METERS_VIEW`.
- The demo custom role "Електрик" gets `METERS_VIEW` and `READINGS_ENTER` (demo data; in a real cooperative the
  chairman gives these permissions to the role).
- A member sees the meters and readings of the plots they currently own and can submit a reading for them; other
  plots — "not found".

## 7. Excel import (extends 008)

New optional sheet `Лічильники` / `Meters`: plot number, resource, serial number, installation date, initial reading.
Only plot meters; the tree is configured in the UI. Same preview, validation and both-language rules as 008; the
template and its example row are updated in both languages.

## 8. Web UI (style of 003)

1. **Лічильники** (with `METERS_VIEW`): register with filters (resource, kind, street, без показу з <date>);
   meter card with readings history, "Замінити лічильник", "Зняти".
2. **Покази** (with `READINGS_ENTER`): the electrician's screen. A date field for the round, prefilled with today, with
   the quiet hint of 2.4; a list of plots in street and number order, each row with the last reading and its date and
   a field for the new one (its date can be changed for that row), saved row by row; interval consumption and warnings
   shown at once; filter "без показу з <date>"; works at phone width. A user whose only permissions are `METERS_VIEW` and
   `READINGS_ENTER` sees a menu with only this screen and the register.
3. **На підтвердження** (with `READINGS_APPROVE`): readings submitted by members, approve or reject with a reason.
4. **Налаштування → Дерево лічильників** (with `METERS_MANAGE`): substations, quarter meters and common consumers, the
   plots each one feeds, moving plots between quarters with an effective date; a "з … по …" range shows range
   consumption per node (interpolated values marked).
5. **Plot card → Лічильник**: active meter, history, replacements.
6. **My account → Мої лічильники**: own meters, readings with interval consumption, "Передати показ" (date
   prefilled with today, same hint).

## 9. Audit

Meter registration, change, removal, replacement, deletion and parent change; reading entry, replacement, correction,
approval and rejection — with old and new values.

## 10. Demo data

Ukrainian, like the rest after the one-language fix: one substation, two quarter meters, a well pump and street
lighting; meters on most demo plots, two plots without a meter, one replacement, readings on irregular dates over the last
four months (rounds a few days to two months apart, one plot not read for more than 60 days) and one pending member
reading.

## 11. Out of scope

Electricity charges, losses per node, the common consumers split and its setting (011); recalculation after a
correction of a used reading (012); the board's electricity report (013); photo of a reading and the mobile electrician
app (phase 5); water charges (until the cooperative has water meters).

## 12. Acceptance criteria

- [ ] Meters of all kinds and both resources can be registered, replaced (including wrap-around), removed and deleted
      as in section 2; constraints of section 5 hold.
- [ ] The chairman builds the tree and moves a plot to another quarter from a given date; a past range shows the old
      tree.
- [ ] Every row of section 3 is a passing unit test of the consumption rules.
- [ ] Readings on any date: the date is set by the user, prefilled with today with the hint; entry with
      `READINGS_ENTER`, member submission, approval and rejection, same-date replacement, correction and deletion
      rules, ordering validation and the unusual-consumption warning.
- [ ] Interval consumption and range consumption with interpolation, marked as calculated, as in 2.5.
- [ ] Permissions and member visibility as in section 6, with allowed and denied tests per endpoint.
- [ ] The `Лічильники` import sheet works in both languages.
- [ ] All changes audited; isolation tests for every new table.
- [ ] `money-rules.md` sections 6–6.3 and `glossary.md` updated: a reading is a value on a date (no billing month),
      interval and range consumption, interpolation, statuses and `SUPERSEDED`, `MeterValue` with 3 decimals, `PLOT`
      meter kind; the formula in section 6 ("reading at end of period − at start") is restated with range
      consumption.
- [ ] `make check` green; e2e: the demo electrician enters a reading on the Покази screen with the default date and
      with a changed date and sees the interval consumption;
      the chairman approves a member's reading; a member sees only their own meter.
