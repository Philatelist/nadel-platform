# Functional Specification: Ledger, charges and period closing

- **Topic:** Every plot gets an account of what it was charged, for what and for which days, with the debt per
  charge type. Charges come from the monthly run of recurring tariffs, from approved electricity (and water) readings,
  from losses in the network and from common consumers. Months are closed in order and never change afterwards; a
  mistake is cancelled by a reversal, never edited away. Also: the "not charged" queue, water connections, opening
  balances and the plot status on the map.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/011-ledger-charges-period-closing.md` (roadmap phase 2, feature 3 of 5)

---

## 1. Overview and Rationale (The "Why")

Tariffs (009) say how much a plot should pay, and meters (010) say how much it used, but the app still charges
nothing. The treasurer keeps the real debts in a spreadsheet, the chairman cannot see who owes what for which month,
and the plot map shows no debt colours. Mistakes are fixed by overwriting cells, so nobody can say later what was
charged and why.

This feature starts charging. The chairman runs a month and the app charges every plot its membership fee, land tax
and other recurring fees. Every approved meter reading immediately charges the electricity used since the previous
reading. Losses in the network and common consumers (the well pump, street lighting) are charged by separate runs for
any date range, previewed before they are booked. Every charge shows how it was calculated. A closed month never
changes; a wrong charge is cancelled by a reversal with a reason, and the account keeps both. Whatever the app could
not charge is listed with its reason, so the board can fix the cause and retry. The plot map finally shows red,
yellow and green by debt.

Payments arrive in phase 3; until then a plot's debt is the sum of its charges.

**Success looks like:** the chairman runs October in one click after a preview and sees each plot's membership fee
and land tax; the electrician's round produces electricity charges without any extra step; the chairman runs losses
for October, sees one quarter with a warning, books the rest and closes September; a member opens "Мої нарахування"
and sees every charge with its calculation.

---

## 2. Functional Requirements (The "What")

All texts are shown in the user's language only (Ukrainian by default, English when chosen). Amounts are shown in
hryvnias with kopecks, quantities in kWh or m³ as in 010. Only the app calculates amounts; the screens show them.

### 2.1 Charges and the plot account

- A **charge** belongs to a plot and a charge type and shows: its amount, kind, source, the billing month it is booked
  in, the days it pays for (service period), the date it counts from for the debt age, who created it and when, the
  reason (for manual operations) and how it was calculated (rates, units, kWh, readings, tariff versions used).
- Kinds: **regular**, **reversal** (cancels one charge with the same amount and the opposite sign, names it and has a
  mandatory reason) and **opening balance** (2.9).
- Sources: tariff, consumption (by a reading), losses, common consumer, opening balance, manual reversal.
- Charges are never changed or deleted, by anyone or anything. A charge is cancelled only by a reversal; a reversal
  cannot itself be reversed; a charge is reversed at most once.
- A charge booked in a closed month cannot be reversed by hand; its correction is an adjustment (feature 012).
- **Debt per charge type** = the sum of the plot's charges of that type (reversals count negative). The account shows
  the debt per charge type and the total.
- The account lists the charges (date, charge type, service period, amount, kind, source) newest first and can be
  filtered by a date range. A charge booked in a later month than its service period shows "за <period>, нараховано в
  <month>".
- Each charge opens its calculation, for example "93,3 кВт·год × 4,32 ₴ = 403,06 ₴, 23.09–04.10 (12 днів)".
- **Acceptance Criteria:**
  - [ ] Given plot 7 has a membership charge of 25,00 ₴ and a land tax charge of 17,50 ₴, when the chairman opens
    plot 7 → "Рахунок", then they see both charges and the debt per charge type 25,00 ₴ and 17,50 ₴, total 42,50 ₴.
  - [ ] When the chairman reverses the 25,00 ₴ charge with the reason "Помилкове нарахування", then the account shows
    a reversal of −25,00 ₴ with that reason and the membership debt drops by 25,00 ₴.
  - [ ] When the chairman tries to reverse the same charge again, then they see "Нарахування вже сторновано" /
    "The charge has already been reversed".
  - [ ] When the chairman tries to reverse a reversal, then they see "Сторно не можна сторнувати" / "A reversal cannot
    be reversed".
  - [ ] When the chairman tries to reverse without a reason, then they see "Вкажіть причину" / "Enter a reason".
  - [ ] Given a charge is booked in a closed month, when the chairman opens it, then "Сторнувати" is not offered and
    the account says the correction is made by an adjustment.
  - [ ] When anyone tries to change or delete an existing charge in the database directly, then the change is refused
    and the charge stays as it was.
  - [ ] When the chairman opens the electricity charge of plot 7, then they see the kWh, the rate, the readings and the
    days it was calculated from.

### 2.2 Billing months and closing

- Billing is monthly. A month is **open** or **closed**. Months exist from the cooperative's first charge onwards; a
  month after the current one cannot be charged.
- The "Нарахування" screen lists the months, newest first, with their status and the total charged per charge type.
- A month can be closed only when every earlier month is closed.
- Before closing, the month page shows a check list: whether the month run (2.3) is done, the "not charged" items
  of the month (2.7) and the plots with an active meter and no reading for more than 60 days (010). None of these blocks
  the closing; the person closing confirms with the list in view.
- After closing, nothing is added to that month. A charge whose service period or reading falls in a closed month is
  booked in the **earliest open month**, keeping its own service period and date, and shows "за <period>, нараховано в
  <month>". If no open month exists after it, the next month is opened for it.
- A closed month is never reopened in this feature.
- **Acceptance Criteria:**
  - [ ] When the chairman opens "Нарахування", then they see every month from the first charge to the current one with
    its status ("Відкритий" / "Закритий") and totals per charge type.
  - [ ] Given September is open, when the chairman tries to close October, then they see "Спершу закрийте попередні
    місяці" / "Close the earlier months first".
  - [ ] When the chairman closes September after viewing the check list, then September shows "Закритий" and no action
    adds a charge to it.
  - [ ] Given September is closed, when a reading dated 28.09 is approved on 05.10, then its charge is booked in
    October, its service period ends on 28.09 and the account shows "за 13.09–28.09, нараховано в жовтні 2026".
  - [ ] When the chairman tries to run a month after the current one, then they see "Майбутній місяць не можна
    нарахувати" / "A future month cannot be charged".
  - [ ] Given the month run of October is not done and two "not charged" items exist, when the chairman opens
    "Закрити місяць", then the check list shows both facts and the month can still be closed.

### 2.3 The month run of recurring tariffs

- "Нарахувати місяць" on an open month charges every plot every recurring or one-off tariff that applies to it that
  month (scope, exclusions, archived plots and charge types, common land as in 009) with the amount of 009. Meter-based
  tariffs are not part of this run (2.4).
- A preview comes first: the number of plots, the total per charge type, and the items that cannot be charged; then
  "Провести" books it.
- A rerun of the same month creates only what is missing (one tariff charge per plot, tariff and month) and never a
  duplicate; it is how a plot or a tariff added later gets its charge for an open month.
- A tariff amount of 0,00 creates no charge.
- **Conflicting tariffs:** if two tariffs of the same charge type apply to one plot on the same day, neither is charged
  for that plot and the case goes to the "not charged" queue with both tariffs named. The tariffs page shows the
  conflict as a warning; both tariffs stay valid.
- A tariff charge counts its debt age from the first day of its billing month (a one-off fee: from its due date).
- **Acceptance Criteria:**
  - [ ] Given membership is 50,0000 ₴ per sotka per year and plot 7 has 600 m², when the chairman previews October,
    then the preview shows 25,00 ₴ for plot 7 in the membership total, and after "Провести" plot 7's account has a
    membership charge of 25,00 ₴ for 01.10–31.10.
  - [ ] When the chairman runs October a second time, then the preview shows nothing new to charge and no charge is
    added.
  - [ ] Given a new plot was added after the October run, when the chairman reruns October, then only the new plot's
    charges are created.
  - [ ] Given plot 12 is in the scopes of two membership tariffs, when the chairman runs October, then plot 12 gets no
    membership charge and "Не нараховано" lists plot 12 with both tariffs named.
  - [ ] Given a tariff gives 0,00 ₴ for a plot in October, when the chairman runs October, then no charge is created
    for it.

### 2.4 Electricity (and water) by readings

- When a reading of a plot meter becomes approved (entered by the board or the electrician, imported, or a member's
  reading approved), the app creates one consumption charge for the interval from the previous point of the same meter
  to this reading: the initial value after an installation, the previous approved reading, or — for a removal — the
  final value. A replacement therefore gives two charges: the last interval of the old meter and the first interval of
  the new one at its first reading.
- The initial value of a meter creates no charge. A pending member reading creates nothing until it is approved.
- Days of an interval run from the day after the previous point to the day of the reading, both included.
- Amount: the electricity tariff (per kWh) that applies to the plot for the days of the interval. When the tariff
  changes inside the interval, the interval's kWh are split by days (each version gets the kWh of the days it is
  valid), each part times its rate, added exactly and rounded once.
- No charge, and an item in "Не нараховано", when no electricity tariff covers every day of the interval, the plot is
  outside the scope of every electricity tariff or excluded, or two tariffs conflict. The item is retried by the next
  month run or by "Повторити", after the board fixes the cause.
- Water (per m³) works the same way once the "Вода" charge type is active and has a tariff, and only for the days the
  plot is connected to the water supply (2.8). While the type is inactive, water readings create nothing and nothing is
  queued.
- **Fixed-percentage losses** (2.5): the same reading also creates a losses charge of kWh × percent at the same rate
  parts.
- The charge counts its debt age from the reading date.
- **Acceptance Criteria:**
  - [ ] Given the previous reading of plot 7 is 112,4 on 22.09, when a reading of 205,7 on 04.10 is approved, then plot
    7 gets an electricity charge of 403,06 ₴ for 93,3 kWh × 4,32 ₴, service 23.09–04.10 (12 days).
  - [ ] Given the electricity rate is 4,3200 ₴ until 16.10 and 5,0000 ₴ from 17.10, when readings 1 000 on 01.10 and
    1 300 on 31.10 are counted, then the charge is 1 398,00 ₴ (150 kWh × 4,32 + 150 kWh × 5,00).
  - [ ] Given an interval of 7 kWh at 4,3250 ₴ and 1 kWh at 4,3350 ₴, when it is charged, then the amount is 34,61 ₴
    (rounded once, not 34,62).
  - [ ] Given the old meter read 8 140 on 03.09 and was replaced on 20.09 at 8 205 by a new meter installed at 3, when
    the new meter's reading of 69 on 05.10 is approved, then plot 7 has two charges: 65 kWh = 280,80 ₴ and 66 kWh =
    285,12 ₴.
  - [ ] When a meter is installed with the initial value 0, then no charge is created.
  - [ ] Given a member submitted a reading, when it is still waiting for approval, then no charge exists, and when the
    chairman approves it, then the charge appears on the account.
  - [ ] Given the first electricity tariff version starts on 01.10, when a reading closes the interval 20.09–05.10,
    then no charge is created and "Не нараховано" lists the plot with "Немає тарифу на всі дні" / "No tariff for every
    day".
  - [ ] Given the "Вода" charge type is inactive, when a water reading is approved, then no charge and no queue item is
    created.
  - [ ] Given losses are 5 % of consumption, when a 65 kWh interval is charged, then the plot gets a consumption charge
    of 280,80 ₴ and a losses charge of 14,04 ₴ (3,25 kWh).

### 2.5 Losses in the network

- The chairman chooses the losses mode in the cooperative settings: **a fixed percentage** (for example 5 %, added to
  every electricity interval, 2.4) or **proportional** (by runs). In the fixed-percentage mode the node meters are for
  information only.
- In the proportional mode the board runs "Розрахунок втрат" for any date range:
  - for every node (quarter meter, then substation), from the bottom up: node losses = node consumption − the sum of the
    consumption of its children (plot meters, common consumers, child quarter meters), with the tree as it was on each
    day (010);
  - a node's losses are shared among the plots under it (directly or through child nodes) in proportion to each plot's
    consumption in the range; a plot with zero consumption gets nothing;
  - each plot's share in kWh is priced with the plot's electricity tariff over the range (split by days when the tariff
    changes) and rounded once; one losses charge per plot per run, naming the node;
  - **negative losses** (children used more than the node shows): the node charges nothing and the preview and the run
    show a warning with the numbers;
  - **unknown values** (no reading after the range end for the node or one of its children): that node and the nodes
    above it are skipped with a warning; the rest of the tree is charged;
  - calculated values are allowed and marked "розрахунковий" in the preview;
  - the preview shows per node: consumption, the children's consumption, losses in kWh and %, the plots and the amount;
    then "Провести" books it;
  - two runs cannot cover the same days of the same node; a skipped node can be charged later by a run for its range;
  - a whole run can be reversed with a reason while all its charges are in open months ("Сторнувати розрахунок").
- The charges are booked in the month of the range end (or the earliest open month, 2.2) and count their debt age from
  the range end.
- The screen lists past runs with their range, author, totals and status (booked or reversed).
- **Acceptance Criteria:**
  - [ ] Given quarter A shows 1 100 kWh and its plots P1 600 kWh and P2 400 kWh (lighting 0), when the chairman runs
    losses, then P1 gets 60 kWh = 259,20 ₴ and P2 40 kWh = 172,80 ₴.
  - [ ] Given the substation shows 2 300 kWh with quarters A 1 100 and B 1 050 and the well 50 (P1 600, P2 400 in A,
    P3 1 000 in B), when the chairman runs losses, then quarter B gives P3 50 kWh and the substation's 100 kWh is
    shared P1 30, P2 20, P3 50 kWh.
  - [ ] Given a quarter shows 950 kWh and its children 1 000 kWh, when the chairman previews losses, then that quarter
    charges nothing and shows the warning "−50 кВт·год".
  - [ ] Given quarter B has no reading after the range end, when the chairman previews losses, then quarter B and the
    substation are skipped with warnings and quarter A is charged.
  - [ ] Given quarter A was charged for 01.10–31.10, when the chairman runs 15.10–15.11, then they see "Втрати за ці дні
    вже розраховано для вузла …" / "Losses for these days have already been calculated for node …".
  - [ ] When the chairman reverses a losses run with a reason, then every losses charge of the run is reversed with
    that reason and the run shows "Сторновано".
  - [ ] Given a value in the range is calculated between readings, when the chairman previews losses, then the value
    is marked "розрахунковий".

### 2.6 Common consumers (a separate line)

- The consumption of common consumers is charged to plots as a separate charge type, "Спільні споживачі" /
  "Common consumers", present in every cooperative (renamable like the others). It has its own line in the plot's
  account and its own debt.
- The board runs "Спільні споживачі" for a date range, separately from losses, with a preview and a confirmation, the
  same overlap rule (per consumer) and the same reversal of a whole run.
- Each common consumer's consumption in the range is split as chosen for that consumer in the settings (default from
  the cooperative setting):
  - **among connected plots** (the default for a well pump): equally among the plots connected to that water source,
    day by day (plot-days, 2.8); a plot not connected pays nothing for the pump;
  - **equally per plot** (for example street lighting): equally among all active plots, with or without meters;
  - **service users**: equally among the plots marked as using this consumer;
  - **not charged** (covered by the membership fee): shown in the run, nothing is charged.
- The price is the rate of the "Спільні споживачі" tariff (per kWh, created by the chairman; split by days when it
  changes). Without such a tariff the run shows "Немає тарифу" and charges nothing.
- One charge per plot per consumer per run, rounded once; the consumer is named on the charge.
- A consumer whose consumption is unknown is skipped with a warning; the others are charged.
- **Acceptance Criteria:**
  - [ ] When a new cooperative is created, then its charge types include "Спільні споживачі".
  - [ ] Given street lighting used 50 kWh, 4 active plots (one without a meter) and the rate 4,3200 ₴, when the
    chairman runs common consumers equally per plot, then each of the 4 plots gets 12,5 kWh = 54,00 ₴.
  - [ ] Given the well pump used 60 kWh in October and P1, P2, P3 were connected all month and P4 not, when the
    chairman runs it among connected plots, then P1–P3 get 20 kWh = 86,40 ₴ each and P4 nothing.
  - [ ] Given the pump used 60 kWh over 01.10–30.10 and P3 was connected from 16.10, when the chairman runs it, then
    P1 and P2 get 24 kWh and P3 12 kWh.
  - [ ] Given lighting used 30 kWh and 3 plots are marked as its users, when the chairman runs it, then each of them
    gets 10 kWh = 43,20 ₴ and other plots nothing.
  - [ ] Given a consumer is "not charged", when the chairman previews the run, then it is listed with its consumption
    and no charges.
  - [ ] Given there is no "Спільні споживачі" tariff, when the chairman previews the run, then they see "Немає тарифу"
    and nothing can be booked.
  - [ ] Given the pump was charged for 01.10–31.10, when the chairman runs it for 15.10–15.11, then the run is refused
    for that consumer with "Спільного споживача за ці дні вже розраховано" / "This common consumer has already been
    charged for these days".
  - [ ] When the chairman reverses a common consumers run with a reason, then every charge of the run is reversed.

### 2.7 "Не нараховано" (the not-charged queue)

- One list for the board of everything the app could not charge, each with its reason and a link to fix it: no
  tariff for every day, conflicting tariffs, plot outside the scope of every electricity tariff, unknown node or
  consumer values from the last runs.
- "Повторити" retries one item; the next month run retries the items of tariffs and readings. An item disappears when
  a retry succeeds. Items never block a month from closing.
- **Acceptance Criteria:**
  - [ ] Given a reading could not be charged for lack of a tariff, when the chairman adds the missing tariff version
    and clicks "Повторити", then the charge is created and the item disappears.
  - [ ] Given the cause is not fixed, when the chairman clicks "Повторити", then the item stays with its reason.
  - [ ] When the chairman opens "Не нараховано", then each item shows the plot, the charge type, the reason and a link
    to the tariff, the meter or the run.

### 2.8 Water connection

- A plot has water connections: the water source (a well, i.e. a common consumer meter, or "Водопровід кооперативу"
  when there is one source), connected from a date and, if disconnected, until a date. The history is kept; a plot has
  at most one active connection.
- Any water tariff — by meter readings or a fixed fee — applies only to the days a plot is connected. A plot that is
  not connected gets no water charge, is not in any missing-readings list for water and does not appear in "Не
  нараховано". A connected plot without a water meter can still pay a fixed water fee; charging by m³ also needs an
  active water meter.
- When a connection starts or ends inside a month, recurring water tariffs are split by days like a tariff version.
- Entered on the plot card ("Підключено до води: Свердловина №1 з 01.05.2020", "Підключити", "Відключити" with a
  date), in bulk on the water source's page in the settings (select plots, a date), and by a new optional column
  "Вода" / "Water" in the "Ділянки" / "Plots" import sheet (так/ні or the source name), in both languages.
- Connecting and disconnecting are audited.
- **Acceptance Criteria:**
  - [ ] When the chairman connects plot 7 to "Свердловина №1" from 01.05.2020, then the plot card shows "Підключено до
    води: Свердловина №1 з 01.05.2020".
  - [ ] Given plot 7 is connected, when the chairman connects it again, then they see "Ділянка вже підключена до води" /
    "The plot is already connected to water".
  - [ ] When the chairman disconnects plot 7 on 15.10, then the card shows the connection with its end date in the
    history and the plot is not connected.
  - [ ] When the chairman connects 5 selected plots to the well from 01.10 on the source's page, then all 5 show the
    connection.
  - [ ] Given a water fee of 300,0000 ₴ per plot per year billed monthly, P1 connected and P4 not, when the chairman
    runs October, then P1 gets 25,00 ₴ and P4 gets nothing and is not in "Не нараховано".
  - [ ] Given the same fee and P3 connected from 16.10, when the chairman runs October, then P3 gets 12,90 ₴.
  - [ ] Given a plot with a water meter is disconnected for the whole interval, when its water reading is approved,
    then no charge and no queue item is created.
  - [ ] When the chairman imports a "Ділянки" sheet with "Вода: так" for plot 9, then plot 9 is connected to the
    cooperative's water source from the import's date; the English sheet with "Water: yes" does the same.

### 2.9 Opening balances

- To start with a cooperative that already has debts, the board enters an opening debt per plot and charge type as of
  a date (the go-live date), with a reason or a document reference. The date may not be in the future. The amount is
  positive.
- Entered one by one on the plot account, or in bulk with a new Excel import sheet "Вхідне сальдо" / "Opening
  balances" (plot number, charge type, amount, date) with the same preview, validation and both-language rules as the
  other import sheets.
- An opening balance is booked in the month of its date (or the earliest open month).
- **Acceptance Criteria:**
  - [ ] When the chairman enters an opening balance of 1 500,00 ₴ for membership on plot 12 as of 01.09.2026 with the
    reason "Борг за 2025 рік", then plot 12's account shows an opening balance of 1 500,00 ₴ and the membership debt
    rises by 1 500,00 ₴.
  - [ ] When the chairman enters an opening balance of 0 or a negative amount, then they see "Сума має бути більшою за
    нуль" / "The amount must be greater than zero".
  - [ ] When the chairman imports an "Вхідне сальдо" sheet with three rows, then the preview shows three opening
    balances and applying it creates them; an English "Opening balances" sheet works the same way.
  - [ ] When an imported row names an unknown plot or charge type, then the preview shows the row as an error and
    nothing from that row is created.

### 2.10 Plot status on the map

- The plot status is recalculated after every charge and reversal from the plot's debt and the age of its oldest
  unpaid charge: **red** if the debt is above the red amount or the debt is older than the red days; **yellow** if there
  is any other debt; **green** without a debt; **grey** for common land or a plot without an owner.
- The cooperative settings page gets "Поріг червоного статусу": an amount (default 1 000,00 ₴) and days (default 90),
  which the chairman can change; a change is audited and applies to the next recalculation of each plot.
- Until payments exist, a plot with charges shows a debt; that is correct.
- **Acceptance Criteria:**
  - [ ] Given plot 7 has no charges and an owner, when it gets a charge of 25,00 ₴, then the map shows plot 7 yellow.
  - [ ] Given the red amount is 1 000,00 ₴, when plot 12 gets an opening balance of 1 500,00 ₴, then the map shows
    plot 12 red.
  - [ ] Given plot 12 is red only because of its debt amount, when its charges are reversed below the threshold, then
    it turns yellow (or green with no debt left).
  - [ ] When the chairman changes the red amount to 2 000,00 ₴, then the settings show the new value and the change
    appears in the audit log.

### 2.11 Tariff versions and readings in use

- A tariff version used by a charge that is not reversed can no longer be corrected or deleted; a reading used by a
  charge or a run that is not reversed can no longer be corrected or deleted. Corrections of used values arrive with
  feature 012.
- **Acceptance Criteria:**
  - [ ] Given the October run used the membership version, when the chairman tries to correct that version, then they
    see "Цю версію вже використано в нарахуваннях — виправлення через перерахунок" (the 009 message).
  - [ ] Given a reading was charged, when the chairman tries to correct or delete it, then they see "Цей
    показ уже використано в нарахуванні — виправлення через перерахунок" (the 010 message).
  - [ ] Given the charge of a reading was reversed, when the chairman corrects the reading, then the correction is
    accepted.

### 2.12 Permissions, visibility and the screens

- New permissions: **view charges** (Chairman and Treasurer): accounts, months, runs, the queue; **run charges**
  (Chairman): month run, losses and common consumers runs, retries, opening balances; **reverse charges** (Chairman):
  reversing a charge or a run; **close months** (Chairman).
- Charges from readings need no permission of their own: they follow the approval of a reading.
- A member sees the account of the plots they currently own ("Мій кабінет → Мої нарахування", read-only); any other
  plot's account is "Ділянку не знайдено". The member sees no months, runs or queue.
- Screens: "Нарахування" (months, month page with the check list, run and close), "Втрати", "Спільні споживачі",
  "Не нараховано", the plot card's "Рахунок" and water connection, "Мої нарахування", and new sections of the
  cooperative settings (losses mode and percentage, common consumers split, service users, water sources and their
  plots, the red threshold). Each menu item and button shows only to those with the permission.
- **Acceptance Criteria:**
  - [ ] When the treasurer opens "Нарахування", then they see the months and accounts but no "Нарахувати місяць",
    "Закрити місяць" or "Сторнувати".
  - [ ] When the treasurer tries to run a month through any channel, then the action is refused.
  - [ ] When a member opens "Мої нарахування", then they see the charges and debts of their own plots only.
  - [ ] When a member asks for another plot's account, then they see "Ділянку не знайдено".
  - [ ] When a member of another cooperative uses the app, then they never see this cooperative's charges, months,
    runs, queue items or water connections.
  - [ ] When the chairman runs a month, closes a month, books or reverses a run, reverses a charge, enters an opening
    balance, connects or disconnects water or changes these settings, then the audit log shows who, when and what.

### 2.13 Demo data

- The demo cooperative shows the feature in Ukrainian: the October 2026 run for membership and land tax; electricity
  charges from the demo readings; one proportional losses run for October with 5–15 % losses per node and one node
  with a warning; one common consumers run (well pump among the connected plots, street lighting equally); most plots
  connected to the well, three not connected, one connected in the middle of October; September closed; one reversed
  charge; one plot with an opening balance; one "not charged" item: a plot whose first meter interval starts before
  the first electricity tariff version.
- **Acceptance Criteria:**
  - [ ] When the chairman opens "Нарахування" in the demo cooperative, then September is closed and October is open
    with membership, land tax, electricity, losses and common consumers totals.
  - [ ] When the chairman opens "Не нараховано" in the demo cooperative, then there is exactly one item with the reason
    "Немає тарифу на всі дні".
  - [ ] When the chairman opens the demo map, then plots show red, yellow or green by their debt.

---

## 3. Scope and Boundaries

### In-Scope

- The plot account, charges with their calculation, reversals, debt per charge type; changes refused by the database.
- Billing months, closing in order with a check list, late charges in the earliest open month.
- Month run of recurring tariffs with preview, idempotent rerun and conflicts.
- Electricity and water charges per approved reading; fixed-percentage losses.
- Proportional losses runs and common consumers runs with preview, warnings, overlap refusal and reversal; the seeded
  "Спільні споживачі" charge type.
- The "Не нараховано" queue with retry.
- Water connections (card, bulk, import column) and their effect on water charges and the well pump split.
- Opening balances (by hand and by an import sheet in both languages).
- Plot status recalculation with the red threshold in settings.
- Tariff versions and readings in use become uncorrectable.
- Permissions, member visibility, audit, demo data, both languages, docs and guides.

### Out-of-Scope

- Payments, allocations, prepayments and opening prepayments (phase 3).
- Adjustments for closed months and recalculation after a change of a used tariff version or reading (feature 012).
- Reports (feature 013); invoices and notifications (phases 4 and 8).
- Automatic monthly runs on a schedule (phase 8).
- Reopening a closed month.

---

## Assumptions & Decisions

Decided by the pipeline (no blocking question; the requirement document is detailed). Each is recorded in
`pipeline-log.md`.

- Runs are booked at once when the chairman confirms them; nothing runs on a schedule.
- Plot status: yellow is any debt that is not red; there are no separate yellow thresholds. The red threshold defaults
  to 1 000,00 ₴ and 90 days.
- A charge booked in a closed month cannot be reversed by hand (like a run, 2.5); its correction is an adjustment
  (012).
- A reading counts as used when a charge or a run that is not reversed was calculated from it: an electricity or water
  charge of that meter's interval, or a losses or common consumers run whose range covers it for that node or consumer.
- An opening balance has a positive amount and a date not in the future; it is booked in the month of its date (or the
  earliest open month). The 01.11.2026 example of the requirement is a calculation example only.
- Debt age counts from: the first day of the billing month for a month-run charge (the due date for a one-off fee), the
  reading date for a reading charge, the range end for a run, the given date for an opening balance.
- When no open month exists after a closed one for a late charge, the next month is opened for it; a month after the
  current one is never charged by a run.
- The water column of the "Ділянки" sheet connects to the cooperative's only water source by "так" / "yes" and to a
  named source by its name; "ні" / "no" or empty leaves the plot as it is.
- Messages for refusals are chosen in this spec ("Нарахування вже сторновано", "Сторно не можна сторнувати",
  "Спершу закрийте попередні місяці", "Майбутній місяць не можна нарахувати", "Ділянка вже підключена до води", "Сума
  має бути більшою за нуль").

---

## Change Log

- 2026-10-22 — created by `/feature-pipeline` from `docs/features/011-ledger-charges-period-closing.md`.
