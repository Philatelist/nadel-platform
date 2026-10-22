# 011. Ledger, charges and period closing

Roadmap phase 2, feature 3 of 5. Input for `/feature-pipeline`. Requires 006 (plots), 007 (ownership, cooperative
settings), 008 (Excel import), 009 (charge types, tariffs, `ChargeCalculator`) and 010 (meters, readings, the meter tree,
`ConsumptionCalculator`).

## 1. Goal

The system starts charging. Every plot gets an account: what was charged, for what, for which days, and the debt per
charge type. Charges come from four sources:

1. **Recurring tariffs** (membership fee, land tax, special-purpose fees): the board runs the month, the server creates
   the charges with `ChargeCalculator` (009).
2. **Electricity by readings:** every new approved reading of a plot meter creates a charge for the interval since the
   previous point, straight away.
3. **Losses in the network:** a run by the board for a date range, per node of the meter tree (or a fixed percentage,
   as the chairman chooses).
4. **Common consumers** (well pumps, street lighting): a separate run by the board, charged as a separate line on the
   plot's account.

Months are closed: a closed month never changes. The ledger is append-only: a mistake is cancelled by a reversal, never
by editing or deleting. Payments arrive in phase 3; until then a plot's debt is the sum of its charges.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/money-rules.md` (all sections; this feature implements 2, 3, 4.1, 6, 6.3, 6.4 and 9 and changes them),
`docs/domain/glossary.md`, `docs/features/009-charge-types-and-tariffs.md`, `docs/features/010-meters-and-readings.md`,
ADRs 0001, 0002, 0006, 0007, 0009, 0011, 0012, 0014 and the design reference `docs/design/screens/`.

Phase 2 sequence: `009 Charge types and tariffs` → `010 Meters and readings` → `011 Ledger, charges and period
closing` → `012 Retroactive recalculation` → `013 Reports`.

General rules as in phase 1: contract-first, Flyway only, tenant-scoped tables with RLS and an isolation test,
permissions not role names, Problem Details with a `code`, one UI language at a time (every text through the i18n
catalogs), admin changes audited through the outbox, only the server calculates money, unit tests for every money rule
(pure calculators without a database), coverage floor raised to the new measured level. Verification levels of
`CLAUDE.md` apply.

## 2. Domain

### 2.1 Charges and the ledger

- A **charge** belongs to a plot and a charge type and has: amount (`Money`), kind, source, the **billing month** it
  belongs to, the **service period** (`from`–`to`, the days it pays for), the accrual date (used for debt age and, in
  phase 3, for FIFO allocation), a calculation record (rates, units, kWh, readings and tariff versions used, so anyone
  can see how the amount was obtained), author, timestamp and, for manual operations, a reason.
- Kinds: `REGULAR`, `REVERSAL` (cancels one charge, same amount with the opposite sign, references it, mandatory
  reason), `OPENING` (opening balance, 2.9). `ADJUSTMENT` is reserved for 012.
- Sources: `TARIFF` (recurring and one-off tariffs), `CONSUMPTION` (electricity by a reading), `LOSSES`,
  `COMMON_CONSUMER`, `OPENING`, `MANUAL_REVERSAL`.
- Charges are **append-only**: the database refuses `UPDATE` and `DELETE` on the charge table (a trigger), so no code
  path can change one. A charge is cancelled only by a reversal; a reversal itself cannot be reversed; a charge is
  reversed at most once.
- **Debt per charge type** = Σ charges of that type (reversals are negative). Payments and allocations arrive in phase 3
  (money-rules 2).
- The ports from 009 and 010 are implemented here: `TariffVersionUsage` and `ReadingUsage` answer "used" when a
  non-reversed charge refers to the version or the reading. From then on they cannot be corrected in place; corrections
  go through 012.

### 2.2 Billing months and closing

- Billing is monthly. A month is `OPEN` or `CLOSED`. A month exists from the cooperative's first charge onwards; a
  future month cannot be charged.
- Months are closed in order: a month can be closed only when every earlier month is closed.
- Before closing, the board sees a check list: is the month's recurring run done (2.3), the "not charged" queue for the
  month (2.7), plots with an active meter and no reading for more than 60 days (010). None of these blocks the closing;
  the person closing confirms with them in view.
- After closing, nothing is added to that month. A charge whose service period or reading falls in a closed month is
  booked in the **earliest open month**, keeping its own service period and accrual date, and shows "за <period>,
  нараховано в <month>".
- A closed month is never reopened in this feature. A correction of a closed month is an adjustment (012).

### 2.3 Recurring tariffs: the month run

- "Нарахувати місяць" for an open month: for every plot and every tariff that applies to it (scope, exclusions,
  archived plots and charge types, common land — money-rules 4.2), the amount from `ChargeCalculator` for that month.
  Meter-based tariffs are not part of this run (2.4).
- Preview first: number of plots, total per charge type, the "not charged" items; then "Провести".
- A rerun of the same month creates only what is missing (one `TARIFF` charge per plot, tariff and month) and never
  duplicates; it is how a plot added later, or a tariff added later, gets its charge for an open month.
- A tariff amount of `0.00` creates no charge.
- **Conflicting tariffs.** If two tariffs of the same charge type apply to one plot on the same day (for example two
  scopes overlap), neither is charged for that plot and the case goes to the "not charged" queue with both tariffs
  named. Only tariffs that would charge the plot more than `0.00` that month take part (two one-time fees of one type
  due in different months never conflict; money rules §4.1). The tariffs page shows the conflict too (a warning, the
  tariffs themselves stay valid).

### 2.4 Electricity by readings

- When a reading of a `PLOT` meter becomes `APPROVED` (entered by the board or the electrician, imported, or a member's
  reading approved), the server creates one `CONSUMPTION` charge for the interval from the previous point of the same
  meter to this reading (money-rules 6.4): the initial value after an installation, the previous approved reading, or —
  for a removal — the final value. A replacement therefore gives two charges: the last interval of the old meter (at its
  final value) and the first interval of the new one at its first reading.
- The initial value of a meter creates no charge.
- Amount: the electricity tariff (`PER_KWH`) that applies to the plot, for the days of the interval. When the tariff
  changes inside the interval, the interval's kWh are split by days (linear, the same days rule as 4.1: each version
  gets the kWh of the days it is valid), each part times its rate, summed exactly and rounded once.
- Days of an interval are counted from the day after the previous point to the day of this reading, both included
  (a reading on 4 October after one on 22 September covers 23 September – 4 October, 12 days).
- No charge, and an item in the "not charged" queue, when: no `PER_KWH` tariff covers every day of the interval, the plot
  is outside the tariff's scope or excluded, or two tariffs conflict. The item is retried automatically by the next
  month run or by "Повторити" in the queue, after the board fixes the cause.
- Water (`PER_M3`) works the same way once the `WATER` charge type is active and has a tariff, and only for the days
  the plot is connected to the water supply (2.8); while the type is inactive, water readings create nothing and
  nothing is queued.
- **Fixed-percentage losses** (2.5, mode `FIXED_PERCENT`): the same reading also creates a `LOSSES` charge of
  `kWh × percent` at the same rate parts.

### 2.5 Losses in the network

The chairman chooses the mode in the cooperative settings (money-rules 6):

- `FIXED_PERCENT`: a percentage set by the chairman (for example 5 %) added to every electricity interval (2.4). Node
  readings are used only for the board's information and the reports (013).
- `PROPORTIONAL`: the board runs "Розрахунок втрат" for a date range `[from, to]` (any dates; usually a month or a
  quarter):
  - for every node (quarter meter, then substation) bottom-up: `node losses = node range consumption − Σ range
    consumption of its children` (plot meters, common consumers, child quarter meters), with the tree as it was on each
    day (010 tree slices);
  - the losses of a node are shared among the plots under it (directly or through child nodes) in proportion to each
    plot's range consumption; a plot with zero consumption gets nothing;
  - each plot's share in kWh is priced with the plot's `PER_KWH` tariff over the range (split by days when the tariff
    changes), rounded once per plot; one `LOSSES` charge per plot per run, with the node in the calculation record;
  - **negative losses** (children used more than the node shows: a wrong reading, different reading dates, a faulty
    meter): the node charges nothing, and the preview and the run show a warning for that node with the numbers;
  - **unknown values**: if the range consumption of a node or of any of its children is unknown (no reading after the
    range end, 010), that node is skipped with a warning, and so are the nodes above it; the rest of the tree is
    charged;
  - interpolated ("розрахунковий") values are allowed and marked in the preview;
  - a run is previewed (per node: consumption, children, losses in kWh and %, plots, amount) and then confirmed;
  - two runs cannot cover the same days of the same node (409 `LOSSES_RANGE_OVERLAP`); a skipped node can be charged
    later by a run for that node's range;
  - a whole run can be reversed while its charges are in open months ("Сторнувати розрахунок", reason required).
- The charges are booked in the month of the range end (or the earliest open month, 2.2).

### 2.6 Common consumers (separate line)

- Their consumption is charged to plots as a **separate charge type**, seeded for every cooperative: `COMMON_CONSUMERS`,
  "Спільні споживачі" / "Common consumers" (renamable like the others, 009). It has its own line in the plot's account
  and its own debt.
- The board runs "Спільні споживачі" for a date range, separately from the losses run, with a preview and a
  confirmation, the same overlap rule (per consumer) and the same reversal of a whole run.
- For every common consumer meter, its range consumption (010) is split as chosen for that consumer in the settings
  (default from the cooperative setting):
  - `CONNECTED_PLOTS` (the default for a well pump): equally among the plots connected to that water source on each day
    of the range (2.8); a plot not connected pays nothing for the pump;
  - `EQUAL_PER_PLOT` (for example street lighting): equally among all active plots of kind `PLOT`, with or without
    meters;
  - `SERVICE_USERS`: equally among the plots marked as using this consumer (a list kept in the settings);
  - `NOT_CHARGED`: covered by the membership fee; shown in the run and the reports, nothing is charged.
- The price is the rate of the `COMMON_CONSUMERS` tariff (`PER_KWH`, created by the chairman; split by days when it
  changes). Without such a tariff the run shows "немає тарифу" and charges nothing.
- One `COMMON_CONSUMER` charge per plot per consumer per run, rounded once; the consumer is named on the charge.
- Unknown consumption of a consumer: that consumer is skipped with a warning; the others are charged.

### 2.7 The "not charged" queue

One list for the board of everything the system could not charge, each with the reason and a link to fix it: no
tariff for the days, conflicting tariffs, plot outside the scope of every electricity tariff, unknown node or consumer
values (from the last runs). Items are resolved automatically when a retry succeeds; they never block a month from
closing.

### 2.8 Water connection

Not every plot is connected to the water supply, as with electricity (money-rules 6.1).

- A plot has water connections: the water source (a well, i.e. a `COMMON_CONSUMER` meter, or "водопровід кооперативу"
  when there is one source), connected from a date and, if disconnected, until a date. History is kept; one active
  connection per plot.
- **Any `WATER` tariff** — `PER_M3` by water meter readings, or a fixed fee such as `PER_PLOT` per season — applies only
  to the days a plot is connected. A plot that is not connected gets no water charge, is not in any "missing readings"
  list for water and does not appear as "not charged". A connected plot without a water meter can still pay a fixed
  water fee; `PER_M3` additionally needs an active water meter (010).
- When the connection starts or ends inside a month, recurring water tariffs are split by days like a tariff version
  (money-rules 4.1).
- The well pump's electricity is shared among the plots connected to that well (`CONNECTED_PLOTS`, 2.6).
- Entered on the plot card ("Підключено до води: Свердловина №1 з 01.05.2020"), in bulk on the water source's page
  in the settings (select plots, date), and by a new optional column `Вода` / `Water` (так/ні, source name) in the
  import sheet `Ділянки` / `Plots` (008), both languages.
- Connecting and disconnecting are audited.

### 2.9 Opening balances

- To start with a cooperative that already has debts, the board enters an opening debt per plot and charge type, as of a
  date (the go-live date): kind `OPENING`, source `OPENING`, a reason or a document reference.
- Entered one by one on the plot account or in bulk with a new Excel import sheet `Вхідне сальдо` / `Opening balances`
  (plot number, charge type, amount, date) — same preview, validation and both-language rules as 008.
- Opening prepayments (people who paid ahead) need payments and are phase 3.

### 2.10 Plot status

With charges in place the plot status (money-rules 9) starts working: it is recalculated after every charge and
reversal (from the outbox event), from the debt and the debt age. If the threshold settings (`red_amount`, `red_days`,
optional yellow ones) do not exist yet, they are added to the cooperative settings page with defaults the chairman can
change. Until payments exist (phase 3) a plot with charges shows a debt; that is correct, not a bug.

## 3. Worked examples (each becomes a unit test of a pure calculator)

Electricity tariff 4.3200 UAH/kWh unless stated.

| Case | Input | Expected |
|---|---|---|
| Reading charge | previous 22.09: 112.4, reading 04.10: 205.7 | 93.3 kWh × 4.32 = 403.06; service 23.09–04.10 (12 days) |
| Tariff change inside the interval | 01.10: 1 000, 31.10: 1 300 (30 days: 02.10–31.10); 4.3200 until 16.10, 5.0000 from 17.10 | 15 days each → 150 × 4.32 + 150 × 5.00 = 648.00 + 750.00 = 1 398.00 |
| Rounded once | one interval: 7 kWh at 4.3250 and 1 kWh at 4.3350 | 30.275 + 4.335 = 34.610 → 34.61 (rounding each part would give 30.28 + 4.34 = 34.62) |
| Replacement | old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 at 3, reads 69 on 05.10 | two charges: 65 kWh = 280.80 and 66 kWh = 285.12 |
| Initial value | meter installed with 0 | no charge |
| Pending member reading | submitted, not approved | no charge; on approval the charge is created |
| Reading in a closed month | September closed; reading dated 28.09 approved on 05.10 | charge booked in October, service period up to 28.09 |
| No tariff for some days | interval 20.09–05.10, first electricity version from 01.10 | no charge; queue item "немає тарифу" |
| Conflicting tariffs | plot 12 in the scopes of two electricity tariffs | no charge for plot 12; queue item names both tariffs |
| Fixed-percentage losses 5 % | interval 65 kWh | `CONSUMPTION` 280.80 and `LOSSES` 3.25 kWh = 14.04 |
| Proportional losses, quarter | quarter A 1 100 kWh; plots P1 600, P2 400; lighting 0 | losses 100: P1 60 kWh = 259.20, P2 40 kWh = 172.80 |
| Proportional losses, substation | substation 2 300; children A 1 100, B 1 050, well 50; plots P1 600, P2 400 (A), P3 1 000 (B) | A: 100 (as above); B: 50 → P3 50 kWh; substation: 2 300 − 2 200 = 100 shared 600/400/1 000 of 2 000 → P1 30, P2 20, P3 50 kWh |
| Negative losses | quarter 950, children 1 000 | node charges nothing, warning "−50 кВт·год" |
| Unknown value | quarter B has no reading after the range end | B and the substation skipped with warnings; quarter A charged |
| Overlapping run | quarter A charged for 01.10–31.10, new run 15.10–15.11 | 409 `LOSSES_RANGE_OVERLAP` for A |
| Common consumer, equal | well pump 50 kWh, 4 active plots (one without a meter), `COMMON_CONSUMERS` tariff 4.3200 | 12.5 kWh each = 54.00 per plot |
| Well pump, connected plots | well pump 60 kWh in October; plots P1, P2, P3 connected all month, P4 not connected | 20 kWh each = 86.40 for P1–P3; P4 nothing |
| Connection inside the range | well pump 60 kWh over 01.10–30.10 (30 days); P1, P2 all month, P3 connected from 16.10 | plot-days 30 + 30 + 15 = 75 → 0.8 kWh per plot-day: P1 24, P2 24, P3 12 kWh |
| Water, fixed fee | `WATER` tariff 300.0000 per plot per year, monthly billing; P1 connected, P4 not | P1 25.00 per month; P4 nothing, not in the queue |
| Water, connected from mid-month | same tariff, P3 connected from 16.10 (31-day month) | 300 / 12 × 16/31 = 12.90 |
| Water by meter, not connected | plot with a water meter reading but disconnected for the whole interval | no charge, not queued |
| Common consumer, users only | lighting 30 kWh, 3 plots marked as users | 10 kWh each = 43.20; other plots nothing |
| Common consumer, not charged | `NOT_CHARGED` | listed in the run, no charges |
| Month run | membership 50.0000/sotka/year, plot 600 m² | `TARIFF` 25.00 for the month; a rerun creates nothing |
| Month run, plot added later | rerun after a new plot | only the new plot's charge is created |
| Closing order | September open, October requested | refused, 409 `PERIOD_CLOSE_ORDER` |
| Manual reversal | charge 25.00 reversed with a reason | `REVERSAL` −25.00; debt drops by 25.00; reversing it again or reversing the reversal is refused |
| Reversed run | losses run reversed | every `LOSSES` charge of the run reversed with the run's reason |
| Opening balance | plot 12, membership 1 500.00 as of 01.11.2026 | `OPENING` 1 500.00; debt 1 500.00; status follows money-rules 9 |
| Database guard | `UPDATE charge …` in a test | refused by the trigger |

## 4. API (`api/openapi.yaml`)

- `GET /billing-months`, `GET /billing-months/{month}` (status, check list, totals), `POST /billing-months/{month}/run`
  with `?preview=true`, `POST /billing-months/{month}/close`.
- `GET /plots/{id}/account?from=&to=`: charges with their calculation records, debt per charge type; for a member only
  own plots.
- `POST /charges/{id}/reverse` (reason).
- `POST /losses-runs` with `preview` (range, nodes), `GET /losses-runs`, `GET /losses-runs/{id}`,
  `POST /losses-runs/{id}/reverse`.
- `POST /common-consumer-runs` with `preview`, list, details, reverse — same shape.
- `GET /not-charged`, `POST /not-charged/{id}/retry`.
- `GET/POST /plots/{id}/water-connections`, `POST /plots/{id}/water-connections/{cid}/end`, and a bulk
  `POST /water-sources/{meterId}/connections` (plots, date).
- `POST /plots/{id}/opening-balances`; the import sheet goes through the 008 endpoints.
- Settings (007 page): losses mode and percentage, default split for common consumers, per-consumer split and service
  users, status thresholds.
- Amounts are `Money` strings, kWh are `MeterValue` strings; nothing is calculated on the client.

## 5. Data

New migration: `billing_month`, `charge`, `charge_part` (rate parts of a charge: tariff version, days, units, amount
before rounding), `losses_run` and `losses_run_node`, `common_consumer_run` and its lines, `not_charged_item`,
`common_consumer_user` (service users), `plot_water_connection` (plot, source meter or none, from, until),
settings columns. Seed the `COMMON_CONSUMERS` charge type for every existing
and new cooperative (like 009). All tenant-scoped with RLS, indexes on `tenant_id` and on `(plot_id, charge_type_id)`,
isolation tests. The trigger of 2.1 on `charge` and `charge_part`. A unique key so the month run cannot create two
`TARIFF` charges for the same plot, tariff and month, and one `CONSUMPTION` charge per reading.

## 6. Permissions

- New: `CHARGES_VIEW` (Chairman and Treasurer base roles): accounts, months, runs, the queue.
- New: `CHARGES_RUN` (Chairman): month run, losses and common consumers runs, retries, opening balances.
- New: `CHARGES_REVERSE` (Chairman): reversing a charge or a run.
- New: `PERIODS_CLOSE` (Chairman): closing a month.
- Charges from readings need no permission of their own: they follow the approval of a reading (010).
- A member sees the account of the plots they currently own; other plots — "not found".

## 7. Web UI (style of 003)

1. **Нарахування** (with `CHARGES_VIEW`): months with their status and totals; a month page with the check list,
   "Нарахувати місяць" (preview → "Провести"), "Закрити місяць" (confirmation with the check list).
2. **Втрати** and **Спільні споживачі** (with `CHARGES_VIEW`; actions with `CHARGES_RUN`): date range, preview table per
   node or consumer with warnings and "розрахунковий" marks, confirm, history of runs, reverse.
3. **Не нараховано**: the queue with reasons, links and "Повторити".
4. **Plot card → Рахунок**: debt per charge type, the list of charges (date, type, service period, amount, kind,
   "за <period>, нараховано в <month>"), each charge opens its calculation ("93,3 кВт·год × 4,32 ₴"); "Сторнувати"
   with a reason; opening balance entry.
5. **My account → Мої нарахування**: the same account read-only for own plots.
6. **Налаштування кооперативу**: losses mode and percentage, common consumers split, service users, the plots connected
   to each water source (bulk connect / disconnect with a date), status thresholds.
7. **Plot card**: water connection with its history, "Підключити" / "Відключити" with a date.

## 8. Audit

Month runs, closings, losses and common consumer runs and their reversals, manual reversals, opening balances,
settings changes — who, when, what (the charges themselves are the ledger and are not duplicated in the audit log).

## 9. Demo data

Ukrainian. October 2026 run for membership and land tax; electricity charges from the demo readings; one proportional
losses run for October with realistic losses (5–15 % per node, 010 demo fix) and one node with a warning; one common
consumers run (well pump among the connected plots, street lighting equally); most plots connected to the well, three not
connected, one connected in the middle of October; September closed; one reversed charge; one plot
with an opening balance; one "not charged" item: a plot whose first meter interval starts before the first electricity
tariff version.

## 10. Out of scope

Payments, allocations, prepayments and opening prepayments (phase 3); adjustments for closed months and recalculation
after a change of a used tariff version or reading (012); reports (013); invoices and notifications (phases 4 and 8);
automatic monthly runs on a schedule (phase 8).

## 11. Acceptance criteria

- [ ] Charges are append-only, enforced by the database trigger; reversals as in 2.1; debt per charge type correct.
- [ ] Month run with preview, idempotent rerun, conflicting tariffs to the queue; months close only in order and stay
      closed; late charges go to the earliest open month with their own service period.
- [ ] Electricity charge per approved reading with the days rule, tariff split and one rounding; replacements; water
      ignored while inactive; fixed-percentage losses.
- [ ] Proportional losses runs: per node, negative and unknown handling, preview, overlap refusal, reversal of a run.
- [ ] Common consumers runs: the separate seeded charge type, three split modes, service users, missing tariff,
      overlap refusal, reversal.
- [ ] Water connections with history, bulk connect and the import column; water tariffs of any basis only for connected
      days; the well pump shared among connected plots by plot-days.
- [ ] "Не нараховано" queue with retry; opening balances by hand and by the import sheet in both languages.
- [ ] `TariffVersionUsage` and `ReadingUsage` implemented: a used version or reading can no longer be corrected (409).
- [ ] Plot status recalculated after charges; thresholds in settings.
- [ ] Every row of section 3 is a passing unit test of a pure calculator.
- [ ] Permissions and member visibility as in section 6, with allowed and denied tests per endpoint; isolation tests
      for every new table; audit as in section 8.
- [ ] `money-rules.md` updated: section 3 (kinds, sources, trigger), new section on billing months and closing,
      section 6 (charge per reading, days rule, losses modes and runs, negative and unknown losses), 6.3 (common
      consumers as a separate charge type and runs, `CONNECTED_PLOTS`), a new section on water connection
      ("not connected, no water charge"), opening balances; `glossary.md` updated.
- [ ] `make check` green; e2e: the chairman runs October, approves a member's reading and sees the electricity charge on
      the plot account, runs losses with a warning node, closes September, reverses a charge; a member sees only their
      own account.
