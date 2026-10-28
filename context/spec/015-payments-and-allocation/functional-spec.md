# Functional Specification: Payments, cash desk and allocation

- **Topic:** The treasurer records money received from members, as cash at the cooperative's cash desk or as a bank
  transfer entered by hand. The app places each payment on the plot's debts (oldest first), keeps what is left as a
  prepayment, places prepayments on later charges, and from then on every debt, plot colour, report and member account
  reflects the payments.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/015-payments-and-allocation.md` (roadmap phase 3, feature 1 of 3)

---

## 1. Overview and Rationale (The "Why")

Until now the app knows only what each plot was charged. Members pay, but the board keeps payments on paper or in a
spreadsheet, so the map shows almost every plot as a debtor, the debtors report is wrong and a member cannot see that
their payment was received. This feature closes that gap.

The treasurer enters each payment once: the plot (or several plots of one payer), cash or bank, the date, the amount.
The app proposes how the money covers the plot's debts, oldest first. The treasurer can change the proposal before
saving. A cash payment gets the cooperative's next receipt number, shown large so it can be written on the paper slip
given to the member. Money above the debts stays as a prepayment and is used automatically for the next charges.

Mistakes are corrected the same way as charges: a payment is never edited or deleted. It is reversed with a reason
(by the chairman), or its money is moved between charges (reallocated). When a charge that was already paid is
reversed or lowered by a recalculation, the paid money becomes the plot's prepayment again and goes to other debts.

**Success looks like:** the treasurer takes 700,00 ₴ in cash for plot 9, sees in one screen which charges it covers,
gives the member receipt № 42, and the map colour, the plot account, the debtors report and the member's own account
all show the lower debt within a few seconds.

---

## 2. Functional Requirements (The "What")

All texts are shown in the user's language only (Ukrainian by default, English when chosen). Amounts are shown in
hryvnias with two decimals. Names and anything typed by users are shown as entered.

Charge type order used in the examples: Membership, Electricity, Land tax.

### 2.1 Entering a payment for one plot

- "Платежі" ("Payments") is a new menu item for those allowed to see payments. Its page lists payments and has
  "Внести платіж" ("Enter payment") for those allowed to enter them. The plot card has "Внести платіж" too, with the
  plot already chosen.
- The form asks for:
  - the plot;
  - the source: "Готівка" ("Cash") or "Банк" ("Bank");
  - the date the money was received, today by default; a date other than today shows a gentle warning ("Дата
    відрізняється від сьогоднішньої" / "The date is not today") but is allowed; a future date is refused;
  - the amount, above zero, two decimals at most;
  - the payer: optional, any member of the cooperative (the plot's owners are offered first);
  - a note about the payer: optional, up to 200 characters (for example the name of a relative who brought the money);
  - for a bank payment, the bank document number: optional, up to 64 characters.
- As soon as the plot and the amount are known, the form shows the **proposal**: a table of the plot's charges that
  are not fully paid (charge type, period, open amount, amount to allocate) and a line "Аванс" ("Prepayment") with
  what is left over.
- The proposal fills the charges in this order: the oldest debt date first; on the same day, the cooperative's order of
  charge types; then the earliest service period; then the order the charges were booked in. Each charge receives at
  most what is still open on it.
- The treasurer may change the amount on any line, or add any other open charge of the plot, before saving. The total
  may not exceed the payment; no line may exceed what is open on its charge. What is not allocated stays as prepayment.
- "Зберегти" ("Save") records the payment. For cash, the screen shows the receipt number large ("Квитанція № 42") with
  "Ще один платіж" ("Another payment"). For bank, it shows the saved payment.
- A payment of a closed month (the date is in a month that is already closed) is accepted: it counts in the earliest
  open month and keeps its own date; the closed month's totals do not change.
- A payment for common land or an archived plot is refused.
- **Acceptance Criteria:**
  - [ ] Given plot 9 owes membership 600,00 ₴ (January) and electricity 250,00 ₴ (February), when the treasurer
        enters a cash payment of 700,00 ₴ for plot 9, then the proposal shows membership 600,00 ₴, electricity
        100,00 ₴ (open 150,00 ₴ after saving) and prepayment 0,00 ₴.
  - [ ] Given the same debts, when the treasurer enters 1000,00 ₴, then the proposal covers both charges in full and
        shows a prepayment of 150,00 ₴.
  - [ ] Given membership 300,00 ₴ and electricity 200,00 ₴ both have the debt date 01.03, when the treasurer enters
        400,00 ₴, then the proposal gives membership 300,00 ₴ and electricity 100,00 ₴.
  - [ ] Given an opening balance of membership 1200,00 ₴ on the plot, when the treasurer enters 500,00 ₴, then the
        proposal puts 500,00 ₴ on the opening balance and 700,00 ₴ stays open.
  - [ ] When the treasurer changes the proposal of a 700,00 ₴ payment to electricity 250,00 ₴ and membership
        400,00 ₴ and saves, then the payment is saved as chosen and 200,00 ₴ of membership stays open.
  - [ ] When the treasurer allocates 900,00 ₴ of a 700,00 ₴ payment, then they see "Розподілено більше, ніж сума
        платежу" / "More is allocated than the payment amount" and nothing is saved.
  - [ ] When the treasurer puts more on a line than is open on that charge, then they see "Сума перевищує залишок за
        нарахуванням" / "The amount exceeds what is open on the charge" and nothing is saved.
  - [ ] When the treasurer saves a cash payment, then the next receipt number of the cooperative is shown large with
        "Ще один платіж".
  - [ ] When the treasurer enters an amount of 0,00 ₴, then they see "Сума має бути більшою за нуль" / "The amount
        must be above zero".
  - [ ] When the treasurer enters tomorrow's date, then they see "Дата не може бути в майбутньому" / "The date cannot
        be in the future".
  - [ ] Given January is closed, when the treasurer enters on 03.02 a cash payment received on 31.01, then it is
        counted in February, the payment shows the date 31.01, and January's totals are unchanged.
  - [ ] When the treasurer tries to enter a payment for common land or an archived plot, then they see "Платіж для
        спільної землі не приймається" / "No payment is taken for common land" or "Ділянка в архіві" / "The plot is
        archived" respectively.
  - [ ] Given another user booked a charge on the plot after the proposal was shown, when the treasurer saves, then
        they see "Дані змінилися після перегляду. Перегляньте знову перед збереженням." / "The data changed after the
        preview. Review it again before saving." and nothing is saved.

### 2.2 One payment for several plots of one payer

- In the form, "Кілька ділянок одного платника" ("Several plots of one payer") switches to a split: the treasurer
  chooses the payer member, and the form lists that member's current plots (not common land, not archived) with their
  debts.
- The proposed split is in proportion to the debts: kopecks are rounded down and the leftover kopecks go to the plots
  with the largest remainders, ties going to the plot listed first. When the amount is above the total debt, the excess
  goes to the first plot as prepayment. When the plots owe nothing, the whole amount goes to the first plot as
  prepayment.
- The treasurer may change any part; the parts must add up to the amount. Each part then shows its own allocation
  proposal, which can be changed as in 2.1.
- Each part is saved as a payment of its plot; the parts belong to one group. A cash group gets one receipt number for
  all its parts.
- **Acceptance Criteria:**
  - [ ] Given the member owns plot 7 (debt 300,00 ₴) and plot 15 (debt 100,00 ₴), when the treasurer splits 200,00 ₴,
        then the proposal is 150,00 ₴ for plot 7 and 50,00 ₴ for plot 15.
  - [ ] Given three plots each owe 100,00 ₴, when the treasurer splits 100,00 ₴, then the proposal is 33,34 ₴ for the
        first plot and 33,33 ₴ for the other two.
  - [ ] Given plots 7 (300,00 ₴) and 15 (100,00 ₴), when the treasurer splits 500,00 ₴, then the proposal is 300,00 ₴
        and 100,00 ₴ plus 100,00 ₴ prepayment on plot 7.
  - [ ] When the treasurer changes the parts so they no longer add up to the amount, then they see "Сума частин не
        дорівнює сумі платежу" / "The parts do not add up to the payment amount" and nothing is saved.
  - [ ] When the treasurer saves a cash split over plots 7 and 15, then one receipt number is shown, and each plot's
        account shows its own payment with that receipt number and a link to the other part.

### 2.3 Receipt numbers

- Each cooperative has one sequence of receipt numbers for cash payments, starting at 1, shown as "№ 123".
- Numbers have no gaps and no duplicates, also when two treasurers save at the same moment; a payment that fails to
  save does not use up a number.
- A number is never reused or changed. A reversed cash payment keeps its number and is shown as reversed.
- Bank payments have no receipt number; they show the bank document number when one was entered.
- **Acceptance Criteria:**
  - [ ] Given the last receipt is № 40, when two treasurers save cash payments at the same moment, then the payments
        get № 41 and № 42, with no gap and no duplicate.
  - [ ] Given a cash payment № 41 was reversed, when the treasurer saves the next cash payment, then it gets № 42 and
        № 41 is still listed, marked "Сторновано" ("Reversed").

### 2.4 Payments list and payment card

- The list shows, newest first: receipt number or bank document number, date received, plot, payer (member's short
  name), source, amount, allocated, prepayment, and whether it is reversed. Filters: date range, billing month, source,
  plot.
- The payment card shows the payment's details, the payer note (only for those allowed to see member data), each
  allocation and each release (when money was taken back from a charge) with the charge, its period and the amount, the
  other parts of its group, who entered it and when.
- **Acceptance Criteria:**
  - [ ] When the treasurer opens "Платежі" and filters by source "Готівка", then only cash payments are listed, each
        with its receipt number.
  - [ ] When the treasurer opens a payment of a split group, then the card shows its allocations and links to the
        other parts of the group.

### 2.5 Reversing a payment

- "Сторнувати" ("Reverse") on the payment card, for the chairman only, needs a reason. A reversal counts in the
  payment's own month while that month is open, otherwise in the earliest open month. It takes back every allocation
  of the payment: the charges it paid are owed again.
- A payment is reversed once. A part of a split payment is reversed on its own; the other parts stay.
- A payment of an archived plot can still be reversed.
- **Acceptance Criteria:**
  - [ ] Given the 700,00 ₴ payment of 2.1 paid membership 600,00 ₴ and electricity 100,00 ₴, when the chairman
        reverses it with the reason "Помилкова ділянка", then the debts are again 600,00 ₴ and 250,00 ₴ and the
        payment shows "Сторновано" with the reason.
  - [ ] When the chairman tries to reverse the same payment again, then they see "Платіж уже сторновано" / "The
        payment has already been reversed".
  - [ ] When the chairman tries to reverse without a reason, then they see "Вкажіть причину" / "Enter a reason".
  - [ ] When the treasurer opens a payment card, then "Сторнувати" is not offered.

### 2.6 Reallocating a payment and allocating a prepayment

- "Перерозподілити" ("Reallocate") on the payment card shows the payment's current allocations next to a new proposal
  and lets the treasurer change it as in 2.1. Saving takes back the old allocations and writes the new ones at once.
- When automatic allocation is off and a plot has a prepayment, the plot account shows "Розподілити аванс" ("Allocate
  prepayment") with a proposal over the plot's open charges, changeable as in 2.1.
- **Acceptance Criteria:**
  - [ ] Given a 700,00 ₴ payment paid membership 600,00 ₴ and electricity 100,00 ₴, when the treasurer reallocates it
        as electricity 250,00 ₴ and membership 450,00 ₴, then the account shows membership open 150,00 ₴, electricity
        open 0,00 ₴, and the payment card lists the released and the new allocations.
  - [ ] Given automatic allocation is off and plot 9 has a prepayment of 150,00 ₴ and a land tax charge of 120,00 ₴
        open, when the treasurer uses "Розподілити аванс" and saves the proposal, then land tax is paid and the
        prepayment is 30,00 ₴.

### 2.7 Prepayment and automatic allocation

- A plot's prepayment is the money paid for it that is not placed on any charge. It is never negative.
- A new cooperative setting "Автоматично зараховувати аванс" ("Allocate prepayment automatically"), on by default,
  changeable by those who manage settings.
- With the setting on, a plot's prepayment is placed on the plot's open charges (same order as 2.1) whenever new
  charges are booked for the plot (a month run, a reading, a run, an opening balance, a recalculation) and whenever
  money is taken back from a charge (2.8). The plot account shows such a line as "Зараховано з авансу" ("Allocated from
  prepayment").
- When a payment is saved with money left over, that remainder is not placed beyond what the treasurer chose; it waits
  for new charges.
- With the setting off, the prepayment stays until the treasurer allocates it by hand (2.6).
- **Acceptance Criteria:**
  - [ ] Given plot 9 has a prepayment of 150,00 ₴ and automatic allocation is on, when a land tax charge of 120,00 ₴ is
        booked for plot 9, then the plot account shows land tax 120,00 ₴ "Зараховано з авансу" and a prepayment of
        30,00 ₴.
  - [ ] Given the same plot with automatic allocation off, when the land tax charge is booked, then land tax stays
        open 120,00 ₴ and the prepayment stays 150,00 ₴.
  - [ ] When the chairman switches "Автоматично зараховувати аванс" off and saves the settings, then the switch stays
        off after reloading the page, and a treasurer does not see the switch as changeable.

### 2.8 When paid money is taken back automatically

- When a paid charge is reversed (by hand, by reversing a run or by a recalculation), all money placed on it is taken
  back and becomes the plot's prepayment.
- When a recalculation lowers a paid charge below what was paid on it, the excess is taken back, starting from the
  latest allocation.
- When a payment is reversed, all its allocations are taken back (2.5).
- With automatic allocation on, the money taken back goes on to the plot's other open charges.
- **Acceptance Criteria:**
  - [ ] Given the 700,00 ₴ payment of 2.1 (membership 600,00 ₴, electricity 100,00 ₴) and automatic allocation on,
        when the chairman reverses the January membership charge, then 600,00 ₴ is taken back, 150,00 ₴ of it goes to
        the open electricity and the plot's prepayment is 450,00 ₴.
  - [ ] Given membership 600,00 ₴ is fully paid, when a recalculation adjusts it by −100,00 ₴, then 100,00 ₴ is taken
        back from the latest payment on it and becomes prepayment.
  - [ ] Given membership 600,00 ₴ is fully paid, when a recalculation adjusts it by +50,00 ₴, then 50,00 ₴ of
        membership is open, the debt is 50,00 ₴ and the plot is no longer green.

### 2.9 Debt, map colour and the plot account

- The debt of a plot is now what is charged minus what is paid on the charges; the oldest unpaid charge is the oldest
  charge with something still open. The map colour follows (red, yellow, green as before). A plot with no debt is green,
  also with a prepayment.
- The plot account (011) now shows:
  - payments, reversals of payments and allocations from prepayment, interleaved with the charges by date;
  - per charge: charged, paid, open;
  - the prepayment, with "Розподілити аванс" when automatic allocation is off;
  - a filter "Лише платежі" ("Payments only");
  - the debt after payments, per charge type and in total.
- **Acceptance Criteria:**
  - [ ] Given plot 9 is red with a debt of 1200,00 ₴, when the treasurer enters a payment that lowers the debt to
        300,00 ₴ (below the red amount, no debt older than the red days), then within a few seconds the map shows
        plot 9 yellow and the account shows the payment and the debt 300,00 ₴.
  - [ ] Given a plot's debt is fully paid with 50,00 ₴ left over, when anyone opens the map, then the plot is green and
        its account shows a prepayment of 50,00 ₴.
  - [ ] When the treasurer chooses "Лише платежі" on a plot account, then only payments and their reversals are listed,
        and the debts above the list are unchanged.

### 2.10 What a member sees

- On "Мій рахунок" a member sees, for each of their own plots: the payments with their date, receipt or bank document
  number, amount and what each paid; reversed payments as reversed; the prepayment; and the debt after payments.
- A member never sees payments of other plots, the payer note or who entered a payment.
- **Acceptance Criteria:**
  - [ ] Given a cash payment № 42 for plot 15 of the demo member, when the member opens "Мій рахунок", then they see
        the payment with "№ 42", its amount, the charges it paid and the lower debt.
  - [ ] When the member tries to open the payments of a plot that is not theirs, then they get "not found" and see no
        amount.

### 2.11 Reports and the billing months screen

- **Plot balances:** charged, paid, debt and prepayment per charge type and in total.
- **Debtors:** the debt after payments; the oldest unpaid charge follows 2.9.
- **Charges by month:** a "Сплачено" ("Paid") column: what is paid on the charges of that month, as of the report date.
  Its charged totals still equal the billing months screen.
- **New report "Платежі" ("Payments"):** filters by billing months or dates, source and street; one row per payment
  (receipt or bank number, date, plot, payer short name, the payer note only for those allowed to see member data,
  amount, allocated, prepayment, reversed); totals per source. Downloadable like the other reports.
- **Templates:** the template catalog offers paid totals per charge type, the prepayment total and the payments rows
  (without the payer note).
- **Billing months screen:** "Надійшло" ("Received") next to "Нараховано" ("Charged") per month.
- **Acceptance Criteria:**
  - [ ] Given plot 9 was charged 850,00 ₴ and paid 700,00 ₴, when the treasurer opens the plot balances report, then
        plot 9 shows charged 850,00 ₴, paid 700,00 ₴, debt 150,00 ₴ and prepayment 0,00 ₴.
  - [ ] Given a plot whose debt was fully paid, when the treasurer opens the debtors report, then that plot is not
        listed.
  - [ ] When the treasurer opens the payments report for October with source "Готівка", then each cash payment of
        October is one row and the total equals the sum of those payments.
  - [ ] When the treasurer opens the billing months screen, then each month shows "Надійшло" next to "Нараховано".

### 2.12 Permissions

- "Перегляд платежів" ("View payments"): Chairman and Treasurer — the payments list, cards and the payments report.
- "Внесення платежів" ("Enter payments"): Chairman and Treasurer — enter, split, reallocate, allocate prepayment.
- "Сторнування платежів" ("Reverse payments"): Chairman only. A treasurer who made a mistake asks the chairman, as for
  charges.
- Changing "Автоматично зараховувати аванс" needs the settings permission.
- A member sees the payments of their own plots on their account (2.10). The demo electrician sees no payments.
- Every action that enters, reverses or reallocates a payment, or allocates a prepayment by hand, is written to the
  audit log (the payer note hidden from those not allowed to see member data). Automatic allocations and take-backs are
  ledger lines, not audit entries.
- **Acceptance Criteria:**
  - [ ] When the demo electrician signs in, then "Платежі" is not in the menu and opening its address shows "no
        access".
  - [ ] When the treasurer reverses a payment through any route, then it is refused with "no access".
  - [ ] When the chairman opens the audit log after a payment was entered, reallocated and reversed, then three entries
        are listed: payment recorded (plot, amount, source, date, receipt number), payment reallocated (old and new
        lines) and payment reversed (the reason).
  - [ ] Given a payment with a payer note, when someone reads the stored data directly (bypassing the app) or the
        server logs, then the note is not found in readable form.
  - [ ] Given a member of another cooperative, when they try to open a payment of this cooperative, then they get "not
        found".

### 2.13 Demo data, docs and guides

- Demo data: cash and bank payments for about half of the owned plots — some paid in full, some in part, one with a
  prepayment, one split over plots 7 and 15 of the demo member, one reversed — while some plots stay red so the map
  stays varied.
- The money rules, the glossary, the ledger notes and both guides (board guide and owner manual, Ukrainian and English)
  describe payments, with the new screens in the screenshot list and the PDFs rebuilt.
- **Acceptance Criteria:**
  - [ ] When the chairman opens the map on the demo cooperative, then green, yellow and red plots are all present, and
        "Платежі" lists cash and bank payments including one reversed and one split over plots 7 and 15.
  - [ ] When the owner opens the board guide, then it has a chapter "Платежі та каса" ("Payments and cash desk") in
        both languages, and Part II explains the allocation order with the examples of 2.1, 2.2, 2.7 and 2.8.

---

## 3. Scope and Boundaries

### In-Scope

- Cash and hand-entered bank payments for one plot or split over several plots of one payer.
- Allocation proposal, the treasurer's changes, reallocation, manual prepayment allocation.
- Receipt numbers for cash.
- Reversal of payments (chairman); automatic take-back on charge reversals, negative adjustments and payment reversals.
- Prepayment and the automatic allocation setting.
- Debt, oldest unpaid charge, map colour, plot account and member account after payments.
- Reports: plot balances, debtors, charges by month ("paid"), the new payments report, template fields, billing months
  "received".
- Permissions, audit, encryption of the payer note, demo data, docs and both guides.

### Out-of-Scope

- Bank statement import and matching (feature 016).
- The cash book, cash handover to the bank and reconciliation (feature 017).
- The printed cash receipt order and the debt certificate (phase 4).
- Refunds of prepayment in cash.
- Online payment (phase 8).
- Interest or penalties on debts.
- A negative opening balance imported as a prepayment.
- The mobile app (phase 5).

---

## Assumptions & Decisions

Decided with the owner (28.10.2026, in the requirement): allocation order oldest first then charge type order, with the
treasurer's change before saving; internal receipt numbers, no fiscal cash register; prepayment auto-allocated by
default (setting); split over several plots proposed in proportion to debts, one receipt per cash group.

Taken automatically by the pipeline (autonomy `minimal`, no blocking questions; see `pipeline-log.md`):

- A member sees their plots' payments (date, receipt or bank number, amount, what it paid, reversed or not) but not the
  payer note and not who entered the payment.
- Bank payments have no receipt number; cash always has one, one per group.
- A payment reversal takes back the payment's allocations; it creates no prepayment, because the money itself is gone.
- The split lists the payer's current plots except common land and archived plots; when they owe nothing, the whole
  amount is a prepayment of the first plot.
- A plot without an owner (grey) can receive a payment; common land and archived plots cannot.
- "Within a few seconds" for the map: the colour refreshes right after the payment is saved, in the background, as for
  charges in 011.
- The rule in the money rules that prepayment is placed "when a period is closed" is replaced by this feature's rule
  (placed whenever charges are booked or money is taken back), as decided with the owner.
- Demo payments carry no payer note (the note is encrypted and demo data is plain SQL).
- The error texts above are the proposed wording; the catalogs may polish them without changing their meaning.

---

## Change Log

- (none yet)
