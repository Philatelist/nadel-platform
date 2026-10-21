# 0011. Cooperative settings module; ownership inside plots; synchronous veto and read SPIs instead of events

**Status:** accepted; implemented in phase 1 by spec 007 (`context/spec/007-ownership/`)

## Context
Feature 007 records who owns each plot, with shares and history, and adds the first cooperative-wide setting
(`memberPlotStatusVisibility`: whether members see the colour of other people's plots). Three design questions came up:

1. Where do the settings live? They are read by `plots` now and by billing, notifications and others later.
2. Where does ownership live: its own module, or `members`, or `plots`?
3. Two cross-module interactions are not notifications: `members` must be able to **refuse** archiving a member who owns a
   plot, and `identity` must **read** the plots the signed-in member owns (`GET /me`). ADR 0007 says cross-module side
   effects go through domain events. But an event cannot veto a transaction, and a read needs an answer in the same request.

## Decision
- **A `settings` module** owns the table `cooperative_settings` (one row per cooperative, RLS `tenant_isolation`; no row
  means the defaults) and exposes `CooperativeSettings` in its root package. Other modules read settings only through it.
  Changing a setting needs the new permission `SETTINGS_MANAGE` and is audited.
- **Ownership lives inside `plots`.** Ownership is a fact about a plot (its history, its status, its guards share one lock
  on the plot row), and every rule (shares add up to one whole, no overlap, dates) is checked on the whole plot timeline in
  one transaction. A separate module would force events between two halves of one invariant. Member names come from
  `members.MemberDirectory`; the member tables are never read from `plots`.
- **Synchronous SPIs for vetoes and reads, events for everything else.** `members.MemberArchiveGuard` (implemented by
  `plots.OwnershipArchiveGuard`) is called inside the archive transaction and throws a 409 to refuse;
  `identity.OwnedPlotsSource` (implemented by `plots`) answers "which plots does this member own now" for `GET /me`. The
  interface sits in the module that needs the answer, the implementation in the module that has the data, so the
  dependency stays one-way (`plots` -> `members`, `plots` -> `identity`). Notifications, recalculations and audit stay
  events through the outbox (ADR 0007).

## Consequences
- A member cannot be archived while owning a plot, and a change of owner cannot slip a member in during their archive:
  the guard reads, takes no lock ordered before a member lock, and the change locks the plot, then the members `FOR SHARE`.
- The guard runs in the caller's transaction, so a slow or failing guard slows or fails the archive. Guards must stay
  cheap, read-only queries; an SPI that needs to write must be an event instead.
- `ModularityTests` still passes: no cycle, `internal` packages stay closed. A new SPI of this kind is a design decision
  and gets a line in the module READMEs and here.
- Spec 010 adds `plots.PlotChangeGuard` (implemented by `meters.internal.MeterPlotGuard`): a veto on deleting, archiving
  or turning a plot into common land, asked by `PlotCommands` after the plot is locked `FOR UPDATE` and by the Excel
  import's plan of the Plots sheet. Unlike `MemberArchiveGuard` it returns the refused plots with a code instead of
  throwing, so the import can ask about many plots at once and report each as a row problem; `plots` turns a refusal of
  a single change into the 409. The writers of the guard's data take the plot `FOR SHARE` first
  (`PlotCatalog.findAndLockShared`), so a guard and such a write never interleave.
- New settings are added as fields of `CooperativeSettings`; the table gets a column through a new Flyway migration.
- The `ownership` table is append-only for the application (`nadel_app` has no `DELETE`), like the ledger (ADR 0006), and
  tests that need cleanup use the owner connection.
