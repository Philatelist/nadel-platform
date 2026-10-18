# Functional Specification: Ownership

- **Topic:** Who owns each plot, in what share and since when, with full history; a change of owner as one safe
  operation; corrections; owner-based status and visibility; and the cooperative setting that decides whether members
  see the status of other people's plots.
- **Status:** Draft
- **Author:** Alex Usov
- **Source:** `docs/features/007-ownership.md` (roadmap phase 1, feature 3 of 4)

---

## 1. Overview and Rationale (The "Why")

The plots registry (feature 006) lists every plot, but nobody can say who owns it. The board keeps owners on paper,
sales and inheritances are hard to track, and a member cannot see their own plots in the app. Debts belong to the plot,
so the cooperative must know who was responsible on any date, including before a sale.

This feature records the owners of each plot, each with a share (½, ⅓ and so on) and the date from which they own it.
A sale or an inheritance becomes one step: the board picks the date and the new owners, and the previous owners' period
ends the day before. Mistakes are corrected, never erased, and every change stays in the audit log. Members see their own
plots, their co-owners and the history, and nothing about other people's plots beyond number, street and kind. Because a
plot's colour will soon show debts, the chairman decides whether members see the colour of other people's plots; by
default they do not.

**Success looks like:** the chairman records a sale of plot 7 to two new co-owners in one dialog in under a minute; the
treasurer sees every plot's owners in the plots list; a member opens "My account" and sees their plots, co-owners and
history, while other people's plots show no colour.

---

## 2. Functional Requirements (The "What")

### 2.1 Owners and shares

- A plot (not common land) can have one or more current owners. Each owner is a member of the cooperative and has a
  share written as a fraction ("1/2", "2/3", "1/1"; default "1/1") and the date from which they own it. An owner may
  also have a note about the basis (sale contract, inheritance, court decision).
- The current owners' shares always add up to exactly one whole, or the plot has no owner. Shares are exact: "1/3 + 1/3 +
  1/3" is one whole; "0.33 + 0.33 + 0.34" is not a valid way to enter them.
- A member may own several plots at the same time, each with its own share.
- Common land never has owners.
- A plot with no current owner shows grey "Без власника" / "No owner"; a plot with at least one current owner shows green
  "Без боргу" / "No debt" until debts are introduced in later phases.
  - **Acceptance Criteria:**
    - [ ] When the chairman records owners whose shares add up to less or more than one whole (for example 1/2 and 1/3),
      then the save is refused with "Частки мають разом становити 1" / "Shares must add up to 1" and nothing changes.
    - [ ] When the chairman records three owners at 1/3 each, then the save succeeds and the card shows each owner with
      "1/3".
    - [ ] When the chairman enters a share as "2/4", then it is saved and shown as "1/2".
    - [ ] When the chairman lists the same member twice among the new owners, then the save is refused with a message on
      the second row.
    - [ ] Given plot 25 has no owner, when the chairman records its first owner, then the plot's mark changes from grey
      "Без власника" to green in the list and on its card.
    - [ ] When the chairman opens the card of the common-land road, then no "Change owner" action is offered, and a change
      sent anyway is refused.

### 2.2 Change of owner

- "Change owner" is one action on the plot card: the chairman chooses a date, the new owners with their shares, and an
  optional basis. On that date the new owners start; every current owner's period ends the day before. The whole change
  is recorded as one entry in the audit log.
- The new owners are chosen with a member search (by name); the dialog also links to adding a new member. Archived
  members cannot be chosen.
- While the chairman types the shares, the dialog shows the running total and whether it equals one whole; saving is
  possible only when it does, or when no owner rows are left (the plot then has no owner from that date).
- The date cannot be in the future, and it must be later than the start date of every current owner and later than the
  end date of every past owner, so periods never overlap.
  - **Acceptance Criteria:**
    - [ ] Given plot 9 is owned by one member since 01.06.2025, when the chairman changes the owner on 01.03.2026 to two
      members at 1/2 each, then the card shows the two new owners from 01.03.2026 and the history shows the previous
      owner from 01.06.2025 to 28.02.2026.
    - [ ] When the chairman changes the owner with the date 1 January 2026, then the previous owner's period ends on
      31 December 2025; with the date 1 March 2024, then it ends on 29 February 2024.
    - [ ] When the chairman picks a date in the future, or a date not later than the current owner's start date, then the
      save is refused with a message under the date.
    - [ ] When the chairman types shares 1/2 and 1/3 in the dialog, then the dialog shows that the total is 5/6, not 1,
      and the Save button stays disabled.
    - [ ] When the chairman removes every owner row and saves, then the plot shows grey "Без власника" from that date and
      the former owners appear in the history.
    - [ ] When the chairman changes the owner and then opens the audit log filtered to ownership, then they see one entry
      for that plot naming the date and the new owners with shares.
    - [ ] When a person without permission to edit ownership opens a plot card, then no "Change owner" or "Correct"
      action is shown, and a change sent anyway is refused with "You don't have access".

### 2.3 Correction

- "Correct" fixes a mistake in an existing owner record: the member, the share, the start or end date, or the basis. A
  reason is required. When a share is corrected, the shares of the co-owners in the same period can be corrected in the
  same step, so that "1/2 + 1/2" can become "2/3 + 1/3".
- A correction must still satisfy every rule (shares add up to one whole, no overlaps, no future dates). The previous
  values stay visible in the audit log. Owner records are never deleted.
  - **Acceptance Criteria:**
    - [ ] Given plot 3 is owned 1/2 + 1/2, when the chairman corrects the first owner's share to 2/3 and the co-owner's to
      1/3 with the reason "Wrong shares in the contract copy", then the card shows 2/3 and 1/3, and the audit log shows
      the old and new shares and the reason.
    - [ ] When the chairman corrects only one co-owner's share so that the total is no longer one whole, then the
      correction is refused with "Частки мають разом становити 1" / "Shares must add up to 1".
    - [ ] When the chairman submits a correction without a reason, then the reason field shows "required" and nothing
      changes.
    - [ ] When the chairman corrects the start date of an owner so that it overlaps the previous owner's period, then the
      correction is refused with a message under the date.

### 2.4 Guards on members and plots

- A member who currently owns a plot cannot be archived until the plot's owner is changed.
- A plot that has ever had an owner cannot be deleted; it can be archived only when it has no current owner.
  - **Acceptance Criteria:**
    - [ ] Given a member currently owns plot 12, when the chairman archives that member and confirms, then the archive
      is refused with "Цей член кооперативу є власником ділянки — спочатку змініть власника" / "This member owns a plot —
      change the owner first".
    - [ ] Given plot 9 had an owner in the past, when the chairman tries to delete it, then the deletion is refused with
      a message that a plot with ownership history cannot be deleted.
    - [ ] Given plot 12 has a current owner, when the chairman archives it, then the archive is refused with a message to
      change the owner first.

### 2.5 Where owners are shown

- **Plot card → Owners:** current owners with names and shares, and a history of past owners with their periods,
  shares and basis, newest first. People allowed to edit ownership see "Change owner" and "Correct".
- **Plots list:** an "Owners" column (names, "+N" when there are many), and the search box also finds plots by a current
  owner's name, for people allowed to view plots.
- **Member card → Plots:** the plots the member owns now (with shares) and owned in the past (with periods), for people
  allowed to view plots or members.
- **My account → My plots:** the member's own current plots with their co-owners, shares and history; a member who owns
  nothing sees "You do not own any plots".
  - **Acceptance Criteria:**
    - [ ] When the treasurer opens the plots list, then each owned plot shows its owners' names in the "Owners" column,
      and typing an owner's last name in the search finds that owner's plots.
    - [ ] When the treasurer opens plot 9, then they see the current owners with shares and the history with the
      previous owner and period, and no "Change owner" or "Correct" actions.
    - [ ] When the chairman opens the member card of a member who owns plot 7 and owned another plot in the past, then
      the "Plots" section lists plot 7 with the share and the past plot with its period.
    - [ ] When the demo member signs in and opens "My account", then they see their plots, including plot 7 shared 1/2
      with a co-owner whose name is shown.

### 2.6 What a member sees

- On plots they currently own, a member sees everything except the board's notes: status, area, cadastral number,
  building, owners with shares and history.
- On every other plot, a member sees only number, street and kind — never owners, area or any personal data — and the
  status only when the cooperative allows it (section 2.7).
- People allowed to view plots always see every plot's owners and history.
  - **Acceptance Criteria:**
    - [ ] When the demo member opens plot 7, which they co-own, then they see the area, the green status and both
      co-owners with shares.
    - [ ] When the demo member opens plot 9, which they do not own, then they see only number, street and kind — no
      owners, no area and no history.
    - [ ] When a member who sold their plot opens it, then they see it like any other plot they do not own.

### 2.7 Cooperative setting: who sees plot colours

- A new page "Налаштування кооперативу" / "Cooperative settings" in the menu, visible to people allowed to manage
  settings (the chairman by default), with one option and two choices:
  - "Лише свої ділянки" / "Own plots only" (default for every cooperative, existing ones included): a member sees the
    colour only of plots they own; other plots are drawn neutral with no label.
  - "Усі ділянки" / "All plots": every member sees the colour of every plot, but never amounts, owners or personal data of
    other plots.
- The page explains: "Показувати колір боргу всіх ділянок усім членам — лише за рішенням загальних зборів".
- The setting affects only members; people allowed to view plots always see every colour.
- Every change of the setting is recorded in the audit log with the old and new value.
- Permission to manage settings is a new permission that can be added to any role on the roles page.
  - **Acceptance Criteria:**
    - [ ] Given the setting is "Own plots only" (the default), when the demo member opens the plots list, then their own
      plots show their status labels and every other plot shows a neutral mark with no label.
    - [ ] Given the setting is "Own plots only", when the demo member filters the list by a status, then only their own
      plots with that status are listed.
    - [ ] Given the chairman switches the setting to "All plots" and saves, when the demo member opens the plots list,
      then every plot shows its status label, and other people's plots still show no area and no owners.
    - [ ] When the treasurer opens the plots list under either setting, then every plot shows its status label.
    - [ ] When the chairman changes the setting and opens the audit log, then they see an entry with the old and the new
      value.
    - [ ] When the treasurer or a member signs in, then the menu has no "Cooperative settings" item, opening its address
      shows "You don't have access", and a change sent anyway is refused.
    - [ ] When the chairman opens a role on the roles page, then "Manage cooperative settings" is one of the permissions
      that can be ticked.

### 2.8 Demo data

- The demo cooperative has plots with single owners, co-owned plots (1/2 + 1/2 and 2/3 + 1/3), one plot sold last year
  with the previous owner in its history, and a few plots without an owner. The demo member co-owns plot 7 (1/2) and
  owns another plot alone.
  - **Acceptance Criteria:**
    - [ ] When the treasurer opens the demo plots list, then most plots are green with owners, a few are grey without an
      owner, plots with two owners show both names, and one plot's card shows a sale last year in its history.

---

## 3. Scope and Boundaries

### In-Scope

- Owners with shares and periods, change of owner, corrections, history, guards on archiving and deleting.
- Owners on the plot card, plots list, member card and My account; owner-based visibility for members.
- The cooperative settings page with the plot-colour visibility option and its new permission; audit of all changes.
- Demo data.

### Out-of-Scope

- Excel import (008), the debt certificate before a sale (phase 4), money, ledger and real debt colours (phases 2–3),
  notifications to new owners, the map (phase 6), mobile screens.
- Future-dated changes of owner.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking). Decisions already made in
  the feature document: exact fractional shares that add up to one whole; a change of owner ends the old owners the day
  before; ownership is corrected, never deleted; the plot-colour setting defaults to "Own plots only".
- Dates of a change of owner or a correction cannot lie in the future; an owner is "current" until their period has an
  end date.
- The date of a change must be later than every current owner's start and every past owner's end, so periods never
  overlap.
- A change of owner may leave the plot without owners (all rows removed); the plot is then grey "No owner".
- A plot with current owners cannot be archived; ownership cannot be changed on an archived plot or on common land, and
  archived members cannot become owners.
- A correction edits one owner record and may correct the shares of co-owners in the same period in the same step; a
  reason is required.
- Shares are shown in lowest terms ("2/4" becomes "1/2"); the bottom number is at most 1000.
- Until debts exist, a plot with an owner shows green "No debt".
- A member's own plots are the plots they currently own; on them a member sees everything except the board's notes.
  Former owners see the ordinary limited view.
- Under "Own plots only" the colour of every plot the member does not own is hidden, common land included, and the
  status filter only matches the member's own plots.
- Editing ownership needs permission to view plots and to edit ownership; choosing members in the dialog also needs
  permission to view members. The chairman has all of them by default.
- The new "Manage cooperative settings" permission is given to the chairman role by default.
- A cooperative that never saved the setting uses "Own plots only".

---

## Change Log

- (none yet)
