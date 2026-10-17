# 007. Ownership

Roadmap phase 1, feature 3 of 4. Input for `/feature-pipeline`. Requires 002 (members, permissions, audit) and 006
(plots).

## 1. Goal

Record who owns each plot, in what share and since when, keep the full history, and make a change of owner (sale,
inheritance) one safe operation. Debt belongs to the plot (money-rules section 8); ownership decides who is shown as
responsible and what a member may see.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md`,
`docs/domain/glossary.md`, `docs/domain/money-rules.md` and ADRs 0002, 0003, 0004, 0007, 0008, 0009.

Feature sequence in roadmap phase 1 (each one builds on the previous and leaves `main` working):
`002 Members and access control` → `003 UI design system` → `004 Role names` → `005 UI preferences` → `006 Plots registry` → `007 Ownership` → `008 Excel import`.
The map is not part of phase 1 (roadmap phase 6): plots are records without polygons.

General rules for every feature in this sequence:
- All new tables are tenant-scoped: `tenant_id uuid not null`, RLS with a `tenant_isolation` policy, index on
  `tenant_id`, and an isolation test. Schema changes only through a new Flyway migration (never edit existing ones).
- Contract-first: change `api/openapi.yaml`, run `make gen`, implement the generated interfaces.
- Authorization through permissions (`PERM_*`), never role names (ADR 0009). The server is the authority; the UI only
  hides actions.
- Errors use Problem Details (RFC 9457) with a machine-readable `code` and field errors for validation.
- Web UI: Ukrainian by default, English available; all text through `@nadel/shared` i18n; empty, loading and error
  states on every screen.
- Admin changes are audited through domain events and the Modulith outbox (ADR 0007).
- Tests: unit tests for domain rules, Testcontainers integration tests, isolation test per new table, allowed and denied
  permission tests per endpoint, `ModularityTests` green, raise `coverage.minimum` to the new measured level.
- No mobile changes in phase 1.
- Do not store tax numbers (РНОКПП), passport data or dates of birth.

## 2. Data: ownership

| Field | Rules |
|---|---|
| `plot_id`, `member_id` | required; only plots of kind `PLOT` |
| `share` | exact fraction stored as numerator and denominator (`1/2`, `1/3`); default `1/1` |
| `valid_from` | required date |
| `valid_to` | optional; null means current |
| `basis` | optional text (purchase contract, inheritance, court decision) |

## 3. Rules

- On any date the shares of a plot's owners sum to exactly 1, or the plot has no owner. Use exact fraction arithmetic,
  never floating point.
- A member may own several plots at the same time, each in its own share. Every plot keeps its own balance; the
  member sees all their plots (account page, `GET /me`) and each plot's debt separately.
- Periods of the same member on the same plot do not overlap.
- **Change of owner** is one operation: given date D and the new owners with shares, end all current ownerships with
  `valid_to = D - 1 day` and create the new ones from D. Recorded as one audited action.
- **Correction**: fixes a mistake in an existing record (wrong share, date or member). The previous values stay in the
  audit log; the change must still satisfy all rules.
- Ownership records are never deleted.
- A plot without a current owner has status `GREY`; a plot with an owner gets its status from the ledger later
  (phase 2–3). Until then a `PLOT` with an owner shows `GREEN`. Update `plot_status` in the same transaction as the
  ownership change (through an event if the status cache lives in another module).
- Archiving a member who currently owns a plot is refused until ownership is changed.
- Hard deleting a plot that has ever had an owner is refused.

## 4. Visibility (completes the rules from 006)

- With `PLOTS_VIEW`: owners and history of every plot.
- A member sees owners (names and shares) and history only for plots they currently own; for other plots, number, street
  and status only.
- `GET /me` adds the list of plots the user currently owns.

## 5. API

- `GET /plots/{id}/ownerships` (current and history).
- `POST /plots/{id}/ownership-changes` (date, new owners with shares, basis).
- `POST /ownerships/{id}/corrections` (new values and a reason).
- `GET /members/{id}/ownerships` (plots owned now and in the past).
- `GET /plots` gains an owners summary and a filter by owner name for users with `PLOTS_VIEW`.

## 6. Web UI

1. **Plot card → Owners**: current owners with shares, history timeline, actions "Change owner" and "Correct" (with
   `OWNERSHIP_EDIT`).
2. **Change owner dialog**: date, member picker (search, with a link to create a member), shares with a live check that
   they sum to 1, basis.
3. **Member card → Plots**: owned now and in the past.
4. **Plots list**: owners column for users with `PLOTS_VIEW`.
5. **My account**: the member's own plots with co-owners and history.

## 7. Tests (in addition to the general rules)

- Unit: fraction addition and comparison, sum-to-one validation, overlap detection, change-of-owner date arithmetic
  (including a change on the first day of the month and on 1 January).
- Integration: change of owner end to end with history; correction keeps old values in audit; member visibility of
  owners; archive refusal for a current owner; `plot_status` updated.
- Playwright: chairman changes the owner of a plot from one member to two co-owners at 1/2; a member sees co-owners only
  on their own plot.
- Demo data: some co-owned plots (1/2 + 1/2, 2/3 + 1/3), one plot sold last year with history, a few plots without owner.

## 8. Out of scope

Excel import (008), debt certificate before sale (phase 4), money and ledger (phases 2–3), notifications to new owners.

## 9. Decisions already made (do not ask)

- Shares are exact fractions; current shares of a plot sum to 1.
- Change of owner ends the old records the day before the new ones start.
- Ownership is corrected, never deleted.
