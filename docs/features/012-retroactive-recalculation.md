# 012. Retroactive recalculation

Roadmap phase 2, feature 4 of 5. Input for `/feature-pipeline`. Requires 009 (tariffs), 010 (meters and readings) and
011 (ledger, charges, billing months, runs, the "not charged" queue, the usage ports).

## 1. Goal

After 011 a tariff version or a reading that a charge used cannot be corrected (409 `TARIFF_VERSION_IN_USE`,
`READING_IN_USE`), and a new version dated in the past does not change charges already made. This feature lets the
board correct the past safely: the system calculates what every affected charge **should** be with the data as it is
now, shows the difference before anything is booked, and books it without ever changing a booked charge:

- in an **open** month: the old charge is reversed and a new one is created;
- in a **closed** month: nothing in that month changes; the earliest open month gets an **adjustment** for the
  difference, referring to the original charge and its period.

The same mechanism serves every cause: a back-dated tariff version, a corrected or deleted tariff version, a corrected,
deleted or back-dated reading, and a manual recalculation the chairman starts after other changes (plot area, tariff
scope, water connection dates, the meter tree).

## Context for the pipeline

Read together with `CLAUDE.md`, `docs/domain/money-rules.md` (sections 1, 3, 4, 5, 6, billing months and closing),
`docs/domain/glossary.md`, `docs/features/011-ledger-charges-period-closing.md` and the 011 spec folder
`context/spec/011-ledger-charges-period-closing/` (technical considerations: `charge`, `charge_part`, `source_key`,
`meter_value_use`, `BillingCalendar`, preview digest, runs), the `ledger` module README, ADRs 0006 and 0007, and the
guides in `docs/guide/`.

Phase 2 sequence: `009` → `010` → `011` → `012 Retroactive recalculation` → `013 Reports`.

General rules as in earlier features: contract-first, Flyway only (expand-only), tenant-scoped tables with RLS and an
isolation test, permissions not role names, Problem Details with a `code`, one UI language at a time (every text
through the i18n catalogs), audit through the outbox, only the server calculates money, every worked example a unit test
of a pure calculator, verification levels and guides rules of `CLAUDE.md`.

## 2. Domain

### 2.1 The rule

For a **recalculation scope** (plots × charge types × a date range, see 2.3) the system:

1. calculates the **expected charges** with the current data, using exactly the calculators of 011 (month run, reading
   intervals with fixed-percentage losses, proportional losses runs, common consumers runs) — no second implementation;
2. compares them with the **effective booked amount** of each existing charge: the charge plus its adjustments, minus
   nothing for a reversed charge (a reversed charge counts as 0 and is not compared again);
3. produces a **plan** of lines; each line is one of:

| Case | Month of the original charge | Booked |
|---|---|---|
| Amount differs, same charge (same `source_key` meaning: same plot, tariff, month or interval endpoints, run node) | open | `REVERSAL` of the old charge (reason) + new `REGULAR` charge with the expected amount and parts |
| Amount differs, same charge | closed | `ADJUSTMENT` for `expected − effective` in the earliest open month, referring to the original charge, same service period |
| Charge should no longer exist (its interval is gone, the tariff no longer applies, expected 0.00) | open | `REVERSAL` |
| Charge should no longer exist | closed | `ADJUSTMENT` for `−effective` |
| Expected charge that does not exist yet (a back-dated tariff now covers the days, an interval changed its endpoints) | — | new `REGULAR` charge booked like any 011 charge (own month if open, else the earliest open month with its own service period) |
| Expected equals effective | — | nothing |

4. books the whole plan in one transaction, after a preview the user confirmed (2.4).

Consequences, stated so the pipeline does not invent alternatives:

- **Booked charges are never changed or deleted** (011 trigger stays). Closed months keep their charges; corrections of
  a closed month appear only as adjustments in an open month.
- An interval whose endpoints changed (a reading deleted or inserted inside it) is not "the same charge": the old
  interval charges are reversed (open) or cancelled by an adjustment `−effective` (closed), and the new intervals are
  charged as new charges. The total can be the same; the structure follows the readings.
- **Rounding**: every expected charge is calculated and rounded once exactly as in 011; the difference of two rounded
  amounts is exact, no further rounding.
- **Idempotent**: running the same recalculation twice books nothing the second time ("Змін немає").
- New charges created by a recalculation get a `source_key` with a revision suffix (for example
  `CONSUMPTION:{from}:{to}#r{recalculationId}`) so the 011 uniqueness holds; lookups by meaning ignore the suffix.
- Items of the "not charged" queue that the recalculation charges are resolved; new causes found become queue items as
  in 011.
- Opening balances (`OPENING`) are never recalculated; manual reversals of 011 stay as they are.
- **Debt can only go down to zero in this phase**: without payments (phase 3) the effective amount never goes below 0.
  Rule recorded now for phase 3: when a correction lowers a debt that was already paid, the excess is a prepayment of
  that plot, never a negative debt.

### 2.2 What starts a recalculation

| Cause | Where | Flow |
|---|---|---|
| New tariff version with `valid_from` in the past, overlapping months that have charges of that tariff | tariff card, "Нова версія" | the dialog shows the recalculation preview for that tariff and the affected months; "Провести" saves the version and books the plan atomically |
| Correction or deletion of a tariff version that a charge used (today 409 `TARIFF_VERSION_IN_USE`) | tariff card, "Редагувати" / "Видалити" | same: change + reason → preview → one atomic booking |
| Correction or deletion of a reading a charge or run used, or a new reading dated inside a charged interval (today 409 `READING_IN_USE`) | meter card, the round, approvals | same: change + reason → preview → atomic booking; affects the plot's interval charges and fixed-% losses, and any losses or common-consumer run whose range used that meter's values |
| Anything else that changes past amounts: plot area, tariff scope or exclusions, water connection dates, the meter tree, a charge type made active again | "Нарахування" → "Перерахунок" | manual: period (from month – to month), charge types (all or chosen), plots (all or chosen), reason → preview → "Провести" |

- Those other changes do **not** recalculate by themselves (they affect only charges not yet made); the screen where they
  are changed shows a hint: "Минулі нарахування не змінено. За потреби — Нарахування → Перерахунок".
- A run (losses or common consumers) in an open month whose numbers change is reversed as a whole and booked again for
  the same range; in a closed month each plot line gets an adjustment for its difference.

### 2.3 Scope of a recalculation

- Cause-driven scopes are computed by the system: a tariff version → the plots in that tariff's scope and the months the
  version (old and new validity) touches; a reading → that meter's affected intervals (previous and next points) and
  the runs whose range used the meter's values in the changed days.
- Manual scope: months from–to (not after the current month), charge types, plots.
- The preview lists the scope it used.

### 2.4 Preview and booking

- The preview is a calculation without writes: lines grouped by plot and month — charge type, original amount (and
  adjustments so far), expected amount, difference, action ("Сторно й нове нарахування", "Коригування в <місяць>",
  "Нове нарахування", "Сторно"), the reason the amount changed (version, reading, area…), totals per charge type and
  overall, and the number of plots.
- A preview with no differences says "Змін немає" and offers no booking (for a cause-driven change the change itself is
  still saved: a correction that changes no amount is allowed without a recalculation).
- Booking needs the preview digest; anything that changed the data in between → 409 `PREVIEW_OUTDATED` (011 pattern).
- A reason is mandatory (up to 500 characters); it is stored on every reversal and adjustment of the plan.
- One recalculation at a time per cooperative (the 011 advisory lock); runs and recalculations exclude each other.

### 2.5 Adjustments

- New charge kind `ADJUSTMENT`, source `RECALCULATION`: amount ≠ 0 and may be negative, refers to the original charge
  (`adjusts_charge_id`), keeps the original service period and accrual date of the original charge (debt age stays
  honest), is booked in the earliest open month, carries the reason and the recalculation id.
- An adjustment is never reversed by hand; a later recalculation adjusts the same original charge again (effective =
  charge + all its adjustments).
- The plot account shows it as "Коригування за <місяць оригіналу>: −5,00 ₴ — <причина>", with a link to the original
  charge and to the recalculation.

### 2.6 Recalculation record

Every booked recalculation is a record: cause (tariff version, reading, manual), scope, reason, author, time, totals,
and its lines (original charge, expected, difference, action, created charge ids). The list "Перерахунки" shows them
newest first; a record opens with its lines. A recalculation is not reversible as a whole: a mistake is corrected by
another recalculation after the data is fixed.

## 3. Worked examples (each becomes a unit test of the pure planner; the booking tests repeat the key ones end to end)

Membership 50.0000 UAH/sotka/year unless stated; plot 600 m² (25.00 per month); electricity 4.3200 UAH/kWh. "Sept
closed, Oct open" unless stated.

| Case | Input | Expected plan |
|---|---|---|
| Wrong rate corrected (the agreed example) | version 60.0000 from 01.09 should have been 50.0000; Sept 30.00, Oct 30.00 booked | Sept: adjustment −5.00 in Oct; Oct: reversal −30.00 + new 25.00; total 50.00 |
| Back-dated version, open month only | new version 60.0000 from 01.10 entered on 20.10 | Oct: reversal −25.00 + new 30.00; Sept unchanged |
| Back-dated version into a closed month | new version 60.0000 from 16.09 (30-day September) | Sept: expected 12.50 + 15.00 = 27.50, adjustment +2.50 in Oct; Oct: reversal −25.00 + new 30.00 |
| Version deleted, the tariff no longer applies in October | the only Oct version deleted, tariff now starts in Nov | Oct: reversal −25.00; Sept unchanged |
| Reading corrected, both intervals open | 05.10: 8 205 → 8 215; intervals 8 140→8 205 (65 kWh, 280.80) and 8 205→8 270 (65 kWh, 280.80), Oct and Nov open | first: reversal + new 75 kWh = 324.00; second: reversal + new 55 kWh = 237.60 |
| Reading corrected, first interval in a closed month | same, the first interval booked in closed Sept | first: adjustment +43.20 in the earliest open month; second: reversal + new 237.60 |
| Reading deleted, intervals merge | readings 03.09 8 140, 05.10 8 205, 05.11 8 270, all charged in open months; 05.10 deleted | both interval charges reversed; one new charge 03.09→05.11, 130 kWh = 561.60; total unchanged |
| Reading deleted, one interval in a closed month | same, the first interval booked in closed Sept | first: adjustment −280.80; second: reversal −280.80; new 561.60 in the earliest open month |
| Back-dated tariff charges a queued interval | interval 20.09–05.10 queued "немає тарифу"; version from 01.09 added | new charge for the interval; the queue item resolved |
| Fixed-percentage losses follow the interval | 5 %, interval 65 → 75 kWh in an open month | consumption: reversal + new 324.00; losses: reversal of 14.04 + new 3.75 kWh = 16.20 |
| Proportional losses run, open month | quarter A reading corrected after the Oct run | the run reversed as a whole and booked again with the new numbers |
| Proportional losses run, closed month | same, the run booked in closed Sept | per plot adjustment for the difference of its share |
| Manual: area corrected | plot area 600 → 700 m² fixed in Nov for Sept–Oct | Sept: adjustment +4.17 (29.17 − 25.00); Oct: reversal −25.00 + new 29.17 |
| Already adjusted charge corrected again | Sept 30.00 adjusted −5.00 earlier, rate now 40.0000 | effective 25.00, expected 20.00 → adjustment −5.00 |
| Run twice | the same recalculation booked again | preview "Змін немає", nothing booked |
| Stale preview | a reading entered between preview and booking | 409 `PREVIEW_OUTDATED` |
| Correction that changes nothing | note of a used version edited | saved, no recalculation |
| Electrician corrects a charged reading | without `RECALCULATION_RUN` | 403; the round says "Показ уже нараховано — зверніться до голови" |

## 4. API (`api/openapi.yaml`)

- `POST /recalculations?preview=true|false`: manual scope (months, charge types, plots), reason, digest for booking;
  `GET /recalculations`, `GET /recalculations/{id}`.
- Cause-driven changes accept an optional recalculation block: `POST /tariffs/{id}/versions`,
  `PUT|DELETE /tariffs/{id}/versions/{versionId}`, `PUT|DELETE /readings/{id}`, `POST /readings` (back-dated inside a
  charged interval), with `preview=true` returning the plan and `preview=false` + digest + reason saving the change and
  booking atomically. Without the block, a change that needs a recalculation keeps answering 409
  (`TARIFF_VERSION_IN_USE`, `READING_IN_USE`) with a pointer to the preview.
- The plot account (011) returns adjustments with their original charge and recalculation.

## 5. Data

New migration: charge kind `ADJUSTMENT` and source `RECALCULATION` (CHECK changes: `ADJUSTMENT` amount ≠ 0 and may be
negative, `adjusts_charge_id` required for it), `adjusts_charge_id` with an index; `recalculation` and
`recalculation_line`. Tenant-scoped, RLS, isolation tests; `charge` stays append-only. The usage ports keep their
contract; their callers now offer the recalculation flow instead of a plain refusal.

## 6. Permissions

- New `RECALCULATION_RUN` (Chairman base role): preview and book recalculations, and the cause-driven corrections of
  used versions and readings.
- Correcting a used tariff version needs `TARIFFS_MANAGE` + `RECALCULATION_RUN`; a used reading needs
  `READINGS_ENTER` + `RECALCULATION_RUN` (the demo electrician cannot, by design).
- Viewing recalculations: `CHARGES_VIEW`. A member sees adjustments on their own plots' account.

## 7. Web UI (style of 003)

1. **Tariff card**: "Нова версія" with a past date, "Редагувати" / "Видалити" of a used version → step 2 of the dialog:
   the preview table, reason, "Провести перерахунок".
2. **Meter card, the round, approvals**: the same for used readings; without the permission, the hint above.
3. **Нарахування → Перерахунок**: the manual form (months, charge types, plots, reason) → preview → book; the list of
   recalculations with details.
4. **Plot account**: adjustment rows and links; a filter "лише коригування".
5. The hints of 2.2 on the plot form (area), the tariff scope editor, water connections and the meter tree.

## 8. Audit

`RECALCULATION_BOOKED` (cause, scope, reason, totals) and the cause-driven change itself (version or reading, old and
new values) as one action. The charges are the ledger and are not duplicated in the audit log.

## 9. Docs and guides

- `money-rules.md` section 5 rewritten with 2.1–2.5 and the examples; glossary: adjustment, recalculation, effective
  amount.
- Guides, both languages: board guide chapter "Виправлення і перерахунок" from "planned" to "works" with the screens;
  owner manual screens-and-buttons table and permissions matrix (`RECALCULATION_RUN`); new screens in
  `docs/guide/scripts/capture-screens.mjs`.
- Demo data: one booked recalculation (the agreed 60 → 50 example on one plot) so the account shows an adjustment.

## 10. Out of scope

Payments and allocating a prepayment created by a correction (phase 3); notifying members (phase 8); recalculating
opening balances; dated history of plot area or tariff scope (they stay current-only; corrections of the past go
through the manual recalculation); reopening closed months.

## 11. Acceptance criteria

- [ ] Every row of section 3 is a passing unit test of the pure planner; booking tests cover open month, closed month,
      new charge, interval merge, runs, idempotency and the stale preview.
- [ ] Cause-driven flows for back-dated, corrected and deleted tariff versions and for corrected, deleted and back-dated
      readings: preview, reason, atomic booking; without the block the 011 409s stay.
- [ ] Manual recalculation with months, charge types and plots; "Змін немає" when nothing differs.
- [ ] Charges never updated or deleted (011 trigger tests still green); closed months unchanged; adjustments in the
      earliest open month with the original service period and accrual date; effective amount = charge + adjustments.
- [ ] Not-charged items resolved when charged; runs reversed and rebooked (open) or adjusted per plot (closed).
- [ ] Permissions and member visibility as in section 6, allowed and denied tests per endpoint; isolation tests for the
      new tables; audit as in section 8.
- [ ] `money-rules.md`, glossary and both guides (two languages) updated; demo recalculation present.
- [ ] `make check` green; e2e: the chairman corrects a used version and sees the preview and the adjustment on the plot
      account (`@smoke`); the chairman corrects a charged reading; the electrician is refused with the hint.
