# Product Definition: Nadel

- **Version:** 1.1
- **Status:** Proposed

> Business rules in this document follow `README.md`, `docs/domain/glossary.md` and
> `docs/domain/money-rules.md`. Where this document describes a calculation, a status or an
> allocation rule, `docs/domain/money-rules.md` remains the source of truth.

---

## 1. The Big Picture (The "Why")

### 1.1. Project Vision & Purpose

Treasurers of dacha (garden) cooperatives track fees in paper ledgers and spreadsheets, chase debtors
by phone, and argue with members about where a debt came from. Nadel replaces all of that with one
transparent system: every charge is calculated automatically from tariffs, every payment is traceable
to the charge it settled, every member sees their own balance and receives a ready-to-pay invoice each
month, and the whole cooperative is visible on a map colored by debt.

The north star is that no dispute about money in a cooperative should ever be unanswerable. Every
amount a member is asked to pay can be traced back, in the system, to the tariff that produced it and
the reading or area it was calculated from.

### 1.2. Target Audience

Dacha (garden) cooperatives in Ukraine — typically dozens to a few hundred plots — and the people who
run them. Buying decisions are made by the cooperative's board; the daily user is the treasurer. The
members themselves are not financially sophisticated and in many cases are older people who will use
the product only through a monthly invoice message and a simple mobile screen.

The first customer is our own cooperative. After that the product is sold to other cooperatives as
SaaS: a one-time purchase plus a monthly subscription covering support and data hosting. Every
cooperative's data is fully isolated from every other cooperative's.

### 1.3. User Personas

- **Persona 1: "Oksana the Treasurer"** — primary user
  - **Role:** Treasurer of a 180-plot cooperative; does the books in the evenings alongside a full-time job.
  - **Goal:** Close the month, send everyone an invoice, and apply the bank statement to the right plots — without spending a weekend on it.
  - **Frustration:** Her spreadsheet cannot explain a balance. When a member insists they paid, she has to dig through bank printouts and her own handwritten notes, and she often cannot prove who is right.

- **Persona 2: "Petro the Member"**
  - **Role:** Owns one plot of 6 sotkas; visits on weekends in season.
  - **Goal:** Know exactly what he owes and pay it in under a minute from his phone.
  - **Frustration:** He gets a verbal figure with no breakdown, has to type bank details by hand, and only finds out he is in arrears when someone calls him.

- **Persona 3: "Mykola the Chairman"**
  - **Role:** Head of the cooperative; answerable to the general meeting.
  - **Goal:** See at a glance how much the cooperative is owed and by whom, and set tariffs and debt thresholds that the system then applies consistently.
  - **Frustration:** He has no reliable total. The figure he reports to the meeting is assembled by hand and he cannot defend it line by line.

- **Persona 4: "Ivan the Electrician"**
  - **Role:** Admin with meter-only permissions.
  - **Goal:** Walk the plots once a month, enter readings on his phone, and approve the readings members submitted themselves.
  - **Frustration:** Readings on paper get transcribed twice and arrive too late to close the month on time.

### 1.4. Success Metrics

- The treasurer spends **under one hour per month** on billing and payment allocation, against several days today.
- **90% of members receive their invoices electronically** within the first three months of a cooperative going live.
- **Zero disputes that cannot be answered from the system's history** — every balance traces to its charges, allocations and corrections.
- The **share of plots with debt older than 90 days falls by half** within the first year.
- A **new cooperative is onboarded in one evening** — map, members and tariffs ready for the first billing period.

---

## 2. The Product Experience (The "What")

### 2.1. Core Features

- **Cooperative map.** Every plot and every piece of common land is drawn on a map and colored by debt
  status: **red** when the debt exceeds the cooperative's amount threshold or is older than its day
  threshold; **yellow** when there is any smaller or newer debt; **green** when nothing is owed,
  including when the plot is in prepayment; **grey** for common land and for plots with no current
  owner. Both thresholds are configured per cooperative. A member sees the number and color of every
  plot on the map, but amounts, owners and personal details only for their own plots; admins see
  everything their role allows.

- **Tariffs and automatic charges.** Admins define tariffs for the four charge types — membership fee,
  special-purpose fee, electricity and land tax. Each tariff has versions with an effective date, and
  is calculated per plot, per sotka of area, per kWh, or as a one-off fixed amount, on a monthly,
  quarterly, yearly or one-off frequency. Land tax is charged yearly on plot area at the cooperative's
  rate.

- **Retroactive recalculation with full history.** A new tariff version or a corrected reading dated in
  the past is recalculated automatically. Within an open period the old charge is reversed and replaced.
  A **closed period is never altered**: the difference appears as an adjustment charge in the current
  open period, referencing the period it came from, and affected members are notified.

- **Electricity with shared transformer losses.** Readings are entered by admins or submitted by members
  and used only once an admin approves them. Consumption is the difference between period-end and
  period-start readings; the cooperative's transformer losses are the common meter minus the sum of
  plot consumption, and each plot carries a share of them — either proportional to its consumption or
  at a fixed percentage, as the cooperative configures. A plot with a missing reading gets no
  electricity charge and appears in the "missing readings" list when the period is closed.

- **Payments and allocation.** The treasurer imports bank statements or enters payments by hand,
  matches them to plots, and allocates them across charge types. Cash taken at the cooperative's cash
  desk is recorded by the treasurer with a sequentially numbered receipt that the member can see in
  their account, so a cash payment is as traceable as a bank transfer. The system proposes an allocation — oldest debts first, then by
  the cooperative's charge type priority — and the treasurer can adjust it before saving. Partial
  payments are normal, and any remainder becomes a prepayment that can be applied automatically to new
  charges when the next period closes. Nothing is ever deleted: a wrong entry is cancelled by a visible
  reversal that names its reason and its author.

- **Monthly invoices and delivery.** Invoices are generated with the amount due, bank details and an NBU
  payment QR code that prefills the amount, account and plot code, and are delivered by email, Telegram
  or Viber according to each member's own preference.

- **Plot sale and ownership history.** Debt belongs to the plot, not to the person. A plot may have
  several co-owners, each with a share and a validity period. Before a sale the system issues a debt
  certificate as of a chosen date, broken down by charge type, or a full-payoff invoice. Ownership
  history is kept permanently.

- **Roles and permissions.** Every cooperative starts with Chairman, Treasurer, Electrician and
  Member. The chairman can create additional admin roles from a fixed list of permissions (for
  example, "Secretary: manage members and ownership, no access to payments") and assign them to
  people. Each person sees and changes only what their roles permit, and every admin change is
  recorded with who made it and when.

- **Web account and mobile apps.** Members and admins use a web account and iOS and Android apps.
  Members see balances, charges, payments and invoices for their plots and can submit meter readings
  with a photo. The interface is Ukrainian by default with English available; all amounts are in
  hryvnia.

### 2.2. User Journey

**The treasurer at month end.** Oksana opens the period-close screen and sees which plots are still
missing meter readings. She chases the three that matter, closes the period, and the system calculates
every charge from the tariffs in effect and sends each member their invoice by their preferred channel.
A few days later she imports the bank statement; the system proposes which plot each payment belongs to
and how to spread it across charge types. She confirms most of the suggestions, adjusts two, and saves.
The map turns green for the plots that paid.

**The member.** Petro receives his invoice in Telegram, opens his banking app, scans the NBU QR code —
amount, account and plot code already filled in — and pays. The next time he opens the Nadel app he sees
the payment applied against his charges and his plot green on the map.

**The electrician.** Ivan walks the plots with his phone, entering readings as he goes, and approves the
readings members submitted themselves. His readings feed the same period close the treasurer is waiting on.

**The chairman.** Mykola sets the cooperative's tariffs and its red and yellow debt thresholds once, then
uses the map and the debt totals to see where the cooperative stands before a board meeting.

---

## 3. Project Boundaries

### 3.1. What's In-Scope for this Version

The first version serves our own cooperative, built in this order:

1. **Plots, map and members** — map import, plot polygons, members, co-owners, ownership history,
   and configurable admin roles.
2. **Tariffs, meters and charges** — charge types, tariff versions with effective dates, meters,
   transformer losses, period closing, the charges ledger and retroactive recalculation.
3. **Payments** — manual entry, cash desk receipts, bank statement import, matching, allocation across
   charge types, prepayments, and the resulting map colors.
4. **Invoices and delivery** — PDF invoice with NBU QR code, debt certificate, and delivery by email,
   Telegram and Viber.
5. **Member web account and mobile apps** — balances and history, meter readings with a photo, and push
   notifications.

Throughout, every cooperative's data is fully isolated from every other cooperative's, admins enter and
maintain members' personal data, and the platform operator cannot see cooperative data.

### 3.2. What's Out-of-Scope (Non-Goals)

- **SaaS operations for other cooperatives** — self-service onboarding, subscriptions and platform billing.
- **Automatic bank integration** — retrieving statements through bank APIs and fully automatic payment matching. (The first version imports statements and *proposes* matches; a human confirms them.)
- **Online card payment directly from an invoice.**
- **Automation around members** — scheduled debtor reminders, a Telegram bot for submitting readings, photo recognition of meter values, and smart meter integration.
- **Cooperative governance** — general meeting reports, announcements and voting.
- **An AI assistant for the treasurer.**
- **Any currency other than the hryvnia.**

---

## 4. Notes on Sources

Everything above is drawn from the brief supplied with this command and from the repository's existing
business documentation (`README.md`, `docs/domain/glossary.md`, `docs/domain/money-rules.md`,
`docs/roadmap.md`). Two points were tightened against `docs/domain/money-rules.md` rather than taken
verbatim from the brief, because that document is the stated source of truth:

- **Yellow status** is any debt that is not red, rather than a debt below a separate yellow threshold.
- **Grey status** covers both common land and a plot with no current owner.

The persona names and their individual details are illustrative, as the template intends. The scope
ordering in section 3.1 matches phases 1–5 of `docs/roadmap.md`, and the non-goals in section 3.2 match
its phases 6–7.
