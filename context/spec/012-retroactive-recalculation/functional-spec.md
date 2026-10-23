# Functional Specification: Retroactive recalculation

- **Topic:** The board corrects the past safely. When a tariff rate, a meter reading or other data turns out to be
  wrong after charges were made, the app works out what every affected charge should be with the data as it is now,
  shows the differences before anything is booked, and books them without ever changing a booked charge: in an open
  month the old charge is cancelled and charged again, in a closed month an adjustment appears in the earliest open
  month.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/012-retroactive-recalculation.md` (roadmap phase 2, feature 4 of 5)

---

## 1. Overview and Rationale (The "Why")

Since feature 011 the app charges plots, but the past cannot be corrected. A tariff version or a reading that a charge
used is locked: the chairman sees "Цю версію вже використано в нарахуваннях — виправлення через перерахунок" and
nothing else. A new tariff version dated in the past does not change the charges already made. A wrong plot area, a
water connection entered late, or a meter moved in the tree changes only future charges. The board has to reverse
charges by hand, one by one, and cannot correct a closed month at all.

This feature adds the recalculation. Whatever the cause, the app calculates the charges the way it would calculate them
today, compares them with what was booked, and shows a preview: each affected charge, what was booked, what it should
be, the difference and what will happen to it. Only when the chairman confirms with a reason is anything booked, all
at once. Booked charges are never changed: in an open month the old charge is reversed and a new one is made; a closed
month stays exactly as it was, and its correction appears as an adjustment in the earliest open month, with the
original period and a link to the original charge. Every recalculation is kept as a record with its lines.

**Success looks like:** the chairman sees that the membership rate 60,00 from September should have been 50,00,
corrects the version, sees the preview "Вересень: коригування −5,00 у жовтні; Жовтень: сторно −30,00 і нове 25,00",
books it with a reason, and the member of that plot sees "Коригування за вересень: −5,00 ₴" on their account. The
chairman corrects a charged reading in the same way, and the electrician, who may not, is told to ask the chairman.

---

## 2. Functional Requirements (The "What")

All texts are shown in the user's language only (Ukrainian by default, English when chosen). Amounts are shown in
hryvnias with kopecks, quantities in kWh or m³ as in 010 and 011. Only the app calculates amounts; the screens show
them. Examples use membership 50,00 ₴ per sotka per year (a 600 m² plot pays 25,00 ₴ a month) and electricity
4,32 ₴ per kWh; "September closed, October open" unless stated.

### 2.1 The rule: expected against booked

- For a recalculation the app calculates the **expected** charges with today's data, exactly as 011 would charge them
  now (month run of recurring tariffs, electricity and water by readings with fixed-percentage losses, proportional
  losses runs, common consumers runs). Every expected charge is rounded once, to kopecks, as in 011; a difference of two
  rounded amounts needs no further rounding.
- Each expected charge is compared with the **effective amount** of the booked charge it corresponds to: the booked
  amount plus all its adjustments. A reversed charge counts as 0,00 and is never compared again.
- A booked charge "corresponds" to an expected one when it charges the same thing: the same plot, tariff and month for
  a recurring fee; the same meter interval (both readings the same) for electricity, water and their fixed losses; the
  same plot and node (or consumer) for a run.
- What happens to each line:

| Case | Month of the booked charge | What is booked |
|---|---|---|
| The amount differs | open | the booked charge is reversed with the reason, and a new regular charge with the expected amount and calculation is made |
| The amount differs | closed | an **adjustment** for "expected − effective" in the earliest open month, linked to the original charge, with its service period |
| The charge should no longer exist (its interval is gone, the tariff no longer applies) | open | reversal |
| The charge should no longer exist | closed | adjustment for "−effective" |
| An expected charge has no booked counterpart (a back-dated tariff now covers the days, an interval changed its readings) | — | a new regular charge, booked like any 011 charge (its own month if open, else the earliest open month with its own service period) |
| Expected equals effective | — | nothing |

- An interval whose readings changed (a reading deleted or added inside it) is a different charge: the old interval
  charges are reversed (open month) or cancelled by an adjustment (closed month), and the new intervals are charged as
  new charges. The total can stay the same.
- A losses or common consumers run whose numbers change: in an open month the whole run is reversed and booked again
  for the same range with the new numbers; in a closed month each plot line of the run gets an adjustment for its
  difference.
- Booked charges are never changed or deleted. A closed month keeps its charges and totals; its corrections appear only
  as adjustments in an open month.
- Opening balances are never recalculated. Reversals made by hand in 011 stay as they are (their charges count as 0,00).
- A "не нараховано" item that the recalculation now charges is resolved; a new problem found (for example, no tariff
  for some days) becomes a "не нараховано" item as in 011.
- In this phase there are no payments, so an effective amount never goes below 0,00. (Rule recorded for phase 3: a
  correction that lowers a debt already paid makes a prepayment of that plot, never a negative debt.)
- Running the same recalculation again books nothing: the preview says "Змін немає" / "No changes".
- **Acceptance Criteria** (each worked example is checked by an automatic test of the calculation; the key ones are
  also checked end to end):
  - [ ] Given version 60,00 from 01.09 should have been 50,00 and September 30,00 ₴ and October 30,00 ₴ were booked,
    when the chairman corrects the version to 50,00, then the preview shows September: adjustment −5,00 ₴ in October,
    and October: reversal −30,00 ₴ and new charge 25,00 ₴, total after booking 50,00 ₴.
  - [ ] Given a new version 60,00 from 01.10 is entered on 20.10, when the chairman previews it, then October shows
    reversal −25,00 ₴ and new 30,00 ₴ and September is unchanged.
  - [ ] Given a new version 60,00 from 16.09 (September has 30 days), when the chairman previews it, then September
    expects 12,50 + 15,00 = 27,50 ₴ and gets an adjustment +2,50 ₴ in October, and October shows reversal −25,00 ₴ and
    new 30,00 ₴.
  - [ ] Given the only October version is deleted so the tariff starts in November, when the chairman previews the
    deletion, then October shows reversal −25,00 ₴ and September is unchanged.
  - [ ] Given readings 8 140 → 8 205 → 8 270 charged as two intervals of 65 kWh (280,80 ₴ each) in open months, when
    the chairman corrects 8 205 to 8 215, then the first interval is reversed and charged 75 kWh = 324,00 ₴, and the
    second is reversed and charged 55 kWh = 237,60 ₴.
  - [ ] Given the same, with the first interval booked in closed September, when the chairman corrects 8 205 to
    8 215, then the first interval gets an adjustment +43,20 ₴ in the earliest open month and the second is reversed
    and charged 237,60 ₴.
  - [ ] Given readings 03.09 8 140, 05.10 8 205 and 05.11 8 270 charged in open months, when the chairman deletes the
    05.10 reading, then both interval charges are reversed and one new charge 03.09–05.11 for 130 kWh = 561,60 ₴ is
    made; the total is unchanged.
  - [ ] Given the same, with the first interval booked in closed September, when the chairman deletes the 05.10
    reading, then the first interval gets an adjustment −280,80 ₴, the second is reversed (−280,80 ₴), and a new
    charge of 561,60 ₴ is made in the earliest open month.
  - [ ] Given the interval 20.09–05.10 is in "Не нараховано" with "Немає тарифу на всі дні", when the chairman adds an
    electricity version from 01.09 and books the recalculation, then the interval is charged and the item is resolved.
  - [ ] Given fixed losses of 5 % and an interval corrected from 65 to 75 kWh in an open month, when the chairman books
    the correction, then the consumption is reversed and charged 324,00 ₴, and the losses are reversed (−14,04 ₴) and
    charged 3,75 kWh = 16,20 ₴.
  - [ ] Given an October proportional losses run used quarter A's meter, when the chairman corrects a reading of that
    meter, then the preview shows the run reversed as a whole and booked again with the new numbers.
  - [ ] Given the same run was booked in closed September, when the chairman corrects the reading, then each plot of
    the run gets an adjustment for the difference of its share.
  - [ ] Given a plot's area was 600 m² and is corrected to 700 m² in November, when the chairman recalculates
    membership for September–October, then September gets an adjustment +4,17 ₴ (29,17 − 25,00) and October shows
    reversal −25,00 ₴ and new 29,17 ₴.
  - [ ] Given September 30,00 ₴ was already adjusted by −5,00 ₴ and the rate is now 40,00, when the chairman
    recalculates, then the effective amount 25,00 ₴ is compared with the expected 20,00 ₴ and the adjustment is
    −5,00 ₴.
  - [ ] Given a recalculation was booked, when the chairman previews the same recalculation again, then they see
    "Змін немає" / "No changes" and nothing can be booked.
  - [ ] When anyone tries to change or delete a booked charge in the database directly, then the change is refused and
    the charge stays as it was (the 011 protection still holds, adjustments included).

### 2.2 Preview and booking

- A preview books nothing. It lists the lines grouped by plot and month: charge type, the booked amount (and the
  adjustments so far), the expected amount, the difference, the action — "Сторно й нове нарахування" / "Reversal and
  new charge", "Коригування в <місяць>" / "Adjustment in <month>", "Нове нарахування" / "New charge", "Сторно" /
  "Reversal" — and why the amount changed (tariff version, reading, area, water connection, tariff plots, meter tree,
  run). It also shows the totals per charge type and overall, the number of plots, and the scope it used (months,
  charge types, plots, or the version or reading that caused it).
- A reason is required to book (up to 500 characters). It is stored on every reversal and adjustment the
  recalculation books.
- Booking books the whole plan at once, or nothing.
- If anything changed between the preview and the booking (a new reading, a run, another recalculation), the booking
  is refused with the 011 message "Нарахування змінилися після перегляду. Перегляньте знову перед проведенням." / "The
  charges changed since the preview. Preview again before booking."
- Only one recalculation or run can be booked at a time in a cooperative; a second one waits until the first finishes.
- **Acceptance Criteria:**
  - [ ] When the chairman opens the preview, then the lines are grouped by plot and month with charge type, booked
    amount, expected amount, difference, action and cause, and the totals per charge type, overall and the number of
    plots are shown.
  - [ ] When the chairman tries to book without a reason, then they see "Вкажіть причину" / "Enter a reason".
  - [ ] Given a reason longer than 500 characters, when the chairman books, then they see that the reason is too long
    and nothing is booked.
  - [ ] Given a reading was entered after the preview, when the chairman books, then they see "Нарахування змінилися
    після перегляду. Перегляньте знову перед проведенням." and nothing is booked.
  - [ ] When the chairman books a recalculation, then every reversal and adjustment it made shows the reason entered.

### 2.3 Adjustments on the account

- An **adjustment** is a new kind of charge: it can be positive or negative (never zero), belongs to the plot and
  charge type of the original charge, is booked in the earliest open month, keeps the original charge's service period
  and the date the debt counts from (so the debt age stays honest), and carries the reason and a link to its
  recalculation.
- An adjustment is never reversed by hand; a later recalculation adjusts the same original charge again.
- The plot account shows it as "Коригування за <місяць оригіналу>: −5,00 ₴ — <причина>" / "Adjustment for <original
  month>: −5,00 ₴ — <reason>", with links to the original charge and to the recalculation. The debt per charge type
  includes adjustments.
- The account has a filter "Лише коригування" / "Adjustments only".
- A member sees adjustments on the accounts of their own plots ("Мої нарахування"), without the recalculation link.
- **Acceptance Criteria:**
  - [ ] Given the 60 → 50 correction was booked, when the chairman opens the plot's account, then it shows
    "Коригування за вересень 2026: −5,00 ₴ — <причина>" in October with links to the September charge and to the
    recalculation, and the membership debt is 50,00 ₴.
  - [ ] When the chairman opens an adjustment, then it shows the original charge's service period, the month it is
    booked in, the reason and the recalculation, and "Сторнувати" is not offered.
  - [ ] When the chairman turns on "Лише коригування", then only adjustments are listed.
  - [ ] Given the plot belongs to the demo member, when the member opens "Мої нарахування", then they see the
    adjustment and the lowered debt, and no link to the recalculation.
  - [ ] Given September is closed, when the chairman books a recalculation that adjusts a September charge, then
    September's charges and totals are unchanged and the adjustment is in the earliest open month.

### 2.4 Correcting a tariff version

- On the tariff card, "Нова версія" with a start date in the past, and "Редагувати" or "Видалити" of a version that
  charges already used, open a second step in the dialog: the recalculation preview for that tariff and the months the
  version (old and new dates) touches, a reason, and "Провести перерахунок" / "Book the recalculation". Booking saves
  the version change and the recalculation together; if the booking is refused, the version is not changed either.
- If the change alters no amount, the preview says "Змін немає", and the change is saved without a recalculation.
- A version no charge used is still corrected or deleted directly, as today.
- Correcting a used version needs the permission to manage tariffs and the permission to run recalculations.
- **Acceptance Criteria:**
  - [ ] Given the membership version was used by the September and October charges, when the chairman edits its rate
    from 60,00 to 50,00, then the dialog shows the preview and "Провести перерахунок", and after booking with a reason
    the version shows 50,00 and the plot accounts show the adjustment and the new charge.
  - [ ] Given a used version, when the chairman deletes it and books the preview, then the version is gone and the
    charges it made are reversed or adjusted.
  - [ ] When the chairman adds a version dated before charges already made, then the preview is shown before saving.
  - [ ] Given a used version, when the chairman changes only its note, then it is saved and no recalculation is made.
  - [ ] Given a user may manage tariffs but may not run recalculations, when they try to correct a used version, then
    they see "Цю версію вже використано в нарахуваннях — виправлення через перерахунок" and nothing changes.

### 2.5 Correcting a reading

- On the meter card, in the round and in approvals, correcting or deleting a reading that a charge or run used, and
  entering a new reading dated inside an interval already charged, open the same second step: the preview of that
  meter's affected intervals (the previous and the next reading) and of the runs whose range used this meter's values
  on the changed days, a reason, and "Провести перерахунок". Booking saves the reading change and the recalculation
  together.
- If the change alters no amount, the change is saved without a recalculation.
- Correcting a used reading needs the permission to enter readings and the permission to run recalculations. A user
  without the second one (the demo electrician) sees "Показ уже нараховано — зверніться до голови" / "The reading has
  already been charged — ask the chairman" and the reading is not changed.
- **Acceptance Criteria:**
  - [ ] Given a charged reading, when the chairman corrects it on the meter card, then the preview of the affected
    intervals is shown, and after booking with a reason the reading shows its new value and the account shows the new
    charges.
  - [ ] Given a charged reading, when the chairman deletes it and books the preview, then the reading is gone and the
    two interval charges become one.
  - [ ] Given a charged interval 03.09–05.10, when the chairman enters a reading dated 20.09 on the same meter, then the
    preview splits the interval into two charges before the reading is saved.
  - [ ] Given a charged reading, when the electrician tries to correct it in the round, then they see "Показ уже
    нараховано — зверніться до голови" and the reading is unchanged.

### 2.6 Manual recalculation

- "Нарахування → Перерахунок" / "Charges → Recalculation" has a form: months from–to (not after the current month),
  charge types (all or chosen), plots (all or chosen), and a reason. "Переглянути" shows the preview; "Провести
  перерахунок" books it.
- It serves every other change that alters past amounts: plot area, tariff plots and exclusions, water connection
  dates, the meter tree, a charge type made active again. Those changes themselves do not recalculate anything; the
  screen where they are made shows the hint "Минулі нарахування не змінено. За потреби — Нарахування → Перерахунок." /
  "Past charges have not changed. If needed — Charges → Recalculation."
- **Acceptance Criteria:**
  - [ ] Given plot 7's area was corrected, when the chairman runs a manual recalculation for September–October,
    membership, plot 7, then the preview shows the September adjustment and the October reversal and new charge.
  - [ ] Given nothing differs, when the chairman previews a manual recalculation, then they see "Змін немає" / "No
    changes" and "Провести перерахунок" is not offered.
  - [ ] When the chairman chooses a "to" month after the current month, then they see that a future month cannot be
    recalculated and no preview is made.
  - [ ] When the chairman changes a plot's area, a tariff's plots or exclusions, a water connection, or the meter tree,
    then the screen shows "Минулі нарахування не змінено. За потреби — Нарахування → Перерахунок."

### 2.7 Recalculation records

- Every booked recalculation is a record: cause (tariff version, reading, or manual), scope, reason, author, time,
  totals, and its lines (original charge, expected amount, difference, action, the charges it created).
- "Перерахунки" / "Recalculations" lists them newest first; a record opens with its lines and links to the charges.
- A recalculation cannot be undone as a whole; a mistake is corrected by another recalculation after the data is
  fixed.
- **Acceptance Criteria:**
  - [ ] When the chairman opens "Перерахунки", then the booked recalculations are listed newest first with cause,
    author, time, reason and total.
  - [ ] When the chairman opens a recalculation, then they see its scope and lines, each with the original charge, the
    expected amount, the difference, the action and links to the charges it created.

### 2.8 Permissions, visibility and audit

- New permission **run recalculations** (Chairman): preview and book recalculations, and correct used tariff versions
  and readings (together with the tariff or reading permission).
- Viewing recalculations needs the permission to view charges (Chairman, Treasurer). A member sees adjustments only on
  their own plots' accounts and never the recalculations.
- The audit log shows "Перерахунок проведено" / "Recalculation booked" (cause, scope, reason, totals) and the version or
  reading change itself (old and new values). The charges are the ledger and are not repeated in the audit log.
- **Acceptance Criteria:**
  - [ ] When the treasurer opens "Перерахунки", then they see the records but no "Переглянути", "Провести
    перерахунок" or the form.
  - [ ] When the treasurer or a member tries to preview or book a recalculation through any channel, then the action is
    refused.
  - [ ] When a member asks for the recalculations, then the action is refused; when a member of another cooperative
    uses the app, then they never see this cooperative's recalculations or adjustments.
  - [ ] When the chairman books a recalculation, then the audit log shows "Перерахунок проведено" with the cause,
    scope, reason and totals, and, for a cause-driven one, the version or reading change with old and new values.

### 2.9 Demo data and guides

- The demo cooperative shows one booked recalculation: the 60 → 50 example on one plot, so its account shows an
  adjustment.
- The board guide chapter "Виправлення і перерахунок" becomes "works", with the screens, in both languages; the owner
  manual lists the new screens, buttons and the permission.
- **Acceptance Criteria:**
  - [ ] When the chairman opens "Перерахунки" in the demo cooperative, then one recalculation is listed, and the plot
    it touched shows the adjustment on its account.
  - [ ] When a reader opens the board guide in Ukrainian or English, then the chapter on corrections and recalculation
    is marked as working and shows the screens.

---

## 3. Scope and Boundaries

### In-Scope

- Expected-against-booked recalculation with reversals in open months and adjustments in the earliest open month.
- Cause-driven flows for back-dated, corrected and deleted tariff versions and for corrected, deleted and back-dated
  readings, with preview, reason and booking together with the change.
- Manual recalculation by months, charge types and plots; the hints on the screens whose changes affect past amounts.
- Losses and common consumers runs: reversed and booked again (open month) or adjusted per plot (closed month).
- Adjustments on the plot account and "Мої нарахування", with the filter and links.
- Recalculation records and their list; the new permission; audit; demo data; both languages; docs and guides.

### Out-of-Scope

- Payments, and allocating a prepayment created by a correction (phase 3).
- Notifying members about corrections (phase 8).
- Recalculating opening balances.
- A dated history of plot area or tariff plots (they stay current-only; corrections of the past go through the manual
  recalculation).
- Reopening closed months; undoing a recalculation as a whole.
- The mobile app.

---

## Assumptions & Decisions

Decided by the pipeline (no blocking question; the requirement document is detailed). Each is recorded in
`pipeline-log.md`.

- Recalculation is never automatic: every one is previewed and confirmed with a reason. This follows the requirement;
  the product definition's "automatic recalculation with member notification" is superseded (notifications are phase
  8), and an update of the product definition is suggested as a follow-up.
- Runs and recalculations exclude each other in a cooperative; a second one waits for the first rather than failing.
- A cause-driven change that changes no amount, or touches nothing already charged, is saved directly; a used version
  or reading changed without the recalculation step is refused with the 011 messages.
- The 009 dialog text "Нарахування за минулі періоди буде перераховано" stays: with this feature it is true, and the
  dialog's second step shows the actual preview.
- The manual form's "to" month cannot be after the current month; the "from" month can be any month with charges.
- The demo recalculation uses the agreed 60 → 50 example on one plot.
- New texts chosen in this spec: "Змін немає", "Провести перерахунок", the four action names, "Коригування за
  <місяць>", "Лише коригування", "Перерахунки", "Перерахунок проведено", "Показ уже нараховано — зверніться до голови"
  and the hint "Минулі нарахування не змінено. За потреби — Нарахування → Перерахунок.", each with its English text.

---

## Change Log

- 2026-10-23 — created by `/feature-pipeline` from `docs/features/012-retroactive-recalculation.md`.
