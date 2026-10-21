# plots

Plots and common land of a cooperative as records, and the debt status (color) shown for them. Tables: `plot` and
`plot_status` and `ownership` (all under RLS `tenant_isolation`). A plot has a number, street, kind (`PLOT` or `COMMON`),
area in square metres, cadastral number, a building flag, notes, a nullable map polygon (`boundary`, SRID 4326) and a
soft archive (`archived_at`). Owners are described below; polygons are imported in the map phase.

## Public API

`PlotImport` (feature 008, the Excel import): `plan(tenantId, importId, rows)` checks the rows of the Plots sheet against the
cooperative's plots and returns, for each row, `NEW`, `UPDATE` or `UNCHANGED` with the plot id and the changed fields, or
the problems of the row (`PlotImportRow`, `PlotImportItem`, `PlotImportPlan`, `PlotImportAction`). It writes nothing.
Plots are matched by number among the active plots, ignoring case; a blank value leaves the existing value unchanged and a
matched plot keeps its stored spelling of the number; a new plot needs a kind and, unless it is common land (area 0), an
area. Besides the rules of `PlotData`: a number repeated in the file is `DUPLICATE_IN_FILE` on the later rows, a cadastral
number held by another plot (archived ones included) or claimed by an earlier row is `CADASTRAL_NUMBER_TAKEN`, and a plot
with ownership history cannot become common land (`PLOT_HAS_OWNERSHIP_HISTORY`). The lookups are bulk queries (500
values per `IN` list). The id of a new plot is derived from the import id and the number, so planning the same import again
gives the same ids. Applying a plan comes with a later slice.

`PlotImport.planOwnership(tenantId, plotRows, plotPlan, rows)` plans the rows of the Ownership sheet (`OwnershipImportRow`,
`OwnershipImportItem`, `OwnershipImportPlan`, `OwnershipImportOutcome`, `OwnershipImportProblem`) and writes nothing.
The rows of a plot (same number, ignoring case) are a group. The plot is a plot of the file whose plan has no problem
(new ones included, with the id they will get) or an active plot (an archived plot is never matched); otherwise
`UNKNOWN_PLOT`; common land is `PLOT_COMMON_LAND`; a plot of the file whose own row has a problem is not checked at all.
Per row: `REQUIRED` (plot, share, date), `SHARE_INVALID` (`n/d` as for a change of owner; a bare `1` is the whole plot)
and `OWNERSHIP_DATE_INVALID` for a date after today (Europe/Kyiv clock). Per group, when no row has a problem and every
member is resolved: `DUPLICATE_OWNER` (later row), `OWNERSHIP_DATE_MISMATCH` (rows that differ from the first row's date)
and `SHARES_NOT_ONE` (the file's own shares, `OwnershipRules.checkTimeline`, on the first row). Then against the recorded
ownership: no current owner is `FIRST_OWNERS` (the date must be later than the end of every past owner,
`OwnershipRules.checkChangeDate`, else `OWNERSHIP_DATE_INVALID`); current owners equal to the file's members and shares,
dates ignored, are `SKIP`; anything else is `OWNERSHIP_CONFLICT` (with the plot in the params) - the import never
changes existing ownership. A row has an outcome and a plot id only when its whole plot has no problem. The recorded
ownership of all matched plots is read with one bulk query per 500 plots.

`PlotImport.lock(plotPlan, ownershipPlan)` takes the existing plots a plan touches `FOR UPDATE` in id order (the lock a
change of owner takes first). `PlotImport.apply(tenantId, plotRows, plotPlan, ownershipRows, ownershipPlan, actor)`
saves the plans (`MANDATORY`, no transaction of its own): new plots are persisted with the ids of the plan, each with its
GREY `plot_status` row, `UPDATE` plots are updated with the plan's overlay, then every `FIRST_OWNERS` group is recorded
as current owners from its common date, the status follows the owners (GREEN for a plot with owners) and the audit
events `PLOT_CREATED`, `PLOT_UPDATED` and `OWNERSHIP_CHANGED` are built (the first owners' event has no previous
owners) and **returned**, not published: `imports` publishes them as `audit.AuditBatch`es. The endpoints still publish
single events (`PlotAuditPublisher.publish`, `OwnershipRecorder.publishChange`; the import uses `event` and
`changeEvent`, so both build identical events). `SKIP` groups are untouched. Plots, then ownership rows and status rows are flushed in stages so a row never
reaches the database before the plot it refers to; the inserts are batched. The core of a change of owner (start rows,
status refresh, `OWNERSHIP_CHANGED` event and its texts) is `OwnershipRecorder`, used by both `OwnershipCommands` and the
import. A violation of the plot number, cadastral number or ownership overlap constraint at a flush, or a plan that no
longer holds, is 409 `IMPORT_STALE`.

`plots` also implements the SPIs `members.MemberArchiveGuard` (see Guards) and `identity.OwnedPlotsSource` (`GET /me` ownedPlots), serves the public read facade `PlotCatalog` (`JpaPlotCatalog`: `find`, `findAll`, `inNaturalOrder` (ids sorted by the natural order of the number, as in the plots list), `countChargeable`; plain `PlotFacts` records with number, common land, archived and area, used by `tariffs`; runs in the caller's transaction under RLS), and uses `members.MemberDirectory` and `settings.CooperativeSettings`.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /plots/map` | `getPlotMap` | any member of a cooperative |
| `GET /plots` | `listPlots` | any member of a cooperative |
| `GET /plots/{id}` | `getPlot` | any member of a cooperative |
| `GET /plots/{id}/ownerships` | `listPlotOwnerships` | `PLOTS_VIEW`, or a member who owns the plot now (else 404) |
| `GET /members/{id}/ownerships` | `listMemberOwnerships` | `PLOTS_VIEW` or `MEMBERS_VIEW`, or the member themself (else 404) |
| `POST /plots/{id}/ownership-changes` | `createOwnershipChange` | `PLOTS_VIEW` and `OWNERSHIP_EDIT` |
| `POST /ownerships/{id}/corrections` | `createOwnershipCorrection` | `PLOTS_VIEW` and `OWNERSHIP_EDIT` |
| `POST /plots` | `createPlot` | `PLOTS_VIEW` and `PLOTS_EDIT` |
| `PUT /plots/{id}` | `updatePlot` | `PLOTS_VIEW` and `PLOTS_EDIT` |
| `POST /plots/{id}/archive` | `archivePlot` | `PLOTS_VIEW` and `PLOTS_EDIT` |
| `POST /plots/{id}/restore` | `restorePlot` | `PLOTS_VIEW` and `PLOTS_EDIT` |
| `DELETE /plots/{id}` | `deletePlot` | `PLOTS_VIEW` and `PLOTS_EDIT` |

The platform operator has no cooperative data: every endpoint answers 403 `PERMISSION_DENIED` for it. The cooperative
always comes from the token, never from the request; a plot of another cooperative is 404 (RLS).

## Visibility

`PlotDtoMapper` is the only place that decides who sees what, through the `ViewerScope` that `PlotQueries` builds once
per request (`full` = `PLOTS_VIEW`, the plots the viewer's member owns now, the cooperative setting; the setting and the
owned plots are not read for a full viewer).

| Rule | Who |
|---|---|
| details: area, cadastral number, building flag (map: area and debt) | `PLOTS_VIEW`, or the current owner of that plot |
| notes, `createdAt`, `updatedAt` | `PLOTS_VIEW` only, owners included |
| `status` | `PLOTS_VIEW`, the current owner, and everyone when `memberPlotStatusVisibility` is `ALL_PLOTS`; otherwise absent from the JSON (common land included) |
| owners column, owner and cadastral search, archived plots | `PLOTS_VIEW` only |
| status filter | for a member under `OWN_ONLY` it matches only their own plots (an empty set short-circuits to an empty page) |
| `GET /plots/{id}/ownerships` | `PLOTS_VIEW` or a current owner of the plot; a former owner gets 404 like any other member |

## Ownership (feature 007, read side)

Table `ownership`: one row per owner of a plot and period (`valid_from`, `valid_to` inclusive, empty while current), with
the share as `share_numerator` / `share_denominator` (CHECK `1 <= n <= d <= 1000`), a `basis` note and composite foreign
keys `(tenant_id, plot_id)` and `(tenant_id, member_id)` (`ON DELETE RESTRICT`), so a link never crosses cooperatives.
An exclusion constraint (`btree_gist`) forbids the same member owning the same plot in overlapping periods. Rows are never
deleted: `nadel_app` has no `DELETE`/`TRUNCATE` on the table. The shares of the current owners adding up to one whole is
enforced by the application (a change of owner and a correction, below).

- `Share` is the exact fraction value object (parse "n/d", reduce by gcd, format, `plus`, compare by cross-multiplication);
  an invalid share is an `InvalidShareException`. Shares are never decimals.
- `OwnershipQueries` reads owners. `GET /plots/{id}/ownerships` returns `current` (ordered by member name) and `history`
  (past owners, newest first by `validFrom`); member names are "Last First" from `members.MemberDirectory`, the
  member tables are never read. It needs `PLOTS_VIEW` (403 otherwise, including the platform operator); a plot that does
  not exist or belongs to another cooperative is 404.
- `GET /plots` adds `owners` (name and share of the current owners, ordered by name) to every summary for callers with
  `PLOTS_VIEW`: one query for the ownership rows of the page and one `MemberDirectory.find`, no per-plot query. The key
  is absent for common land, for a plot without owners and for everyone else. For callers with `PLOTS_VIEW`, `q` also
  matches plots whose current owner's last, first or middle name contains it (`MemberDirectory.idsMatchingName`, at most 200
  members, then an `exists` on `ownership` in the native search; an empty id list switches the branch off instead of
  producing an invalid `IN ()`).

## Change of owner (feature 007)

`POST /plots/{id}/ownership-changes` with `{effectiveFrom, owners[{memberId, share = "1/1"}] (0..20), basis?}` is one
transaction in `OwnershipCommands.change`: it ends every current row on `effectiveFrom - 1 day` (calendar arithmetic:
1 March 2024 gives 29 February 2024) and inserts the new rows from `effectiveFrom`. An empty list ends ownership. It answers
201 with the same `PlotOwnerships` as the read endpoint.

- Order of work: lock the plot row (`PlotRepository.findByIdForUpdate`, `FOR UPDATE`; 404 when missing or of another
  cooperative), refuse an archived plot (409 `PLOT_ARCHIVED`) and common land (409 `PLOT_COMMON_LAND`), parse the shares
  (400 `VALIDATION_FAILED` with `SHARE_INVALID` on each bad `owners[i].share`; a share that does not look like `n/d` is
  refused by the contract pattern with the same code), refuse a member listed twice (409 `DUPLICATE_OWNER` on
  `owners[i].memberId` of the second row), lock the new members `FOR SHARE` through `MemberDirectory.lockActive` (unknown
  or other cooperative's member: 400 `MEMBER_NOT_FOUND`; archived: 409 `MEMBER_ARCHIVED`), then the rules below.
- `OwnershipRules` is pure and shared with the correction. `checkChangeDate`: the date is not after
  today (the Europe/Kyiv date from the `Clock` bean) and later than the start of every current row and the end of every
  past row (409 `OWNERSHIP_DATE_INVALID`, field `effectiveFrom`). `checkTimeline` validates the whole timeline the change
  would produce: on every day with owners the shares add up to exactly one whole (409 `SHARES_NOT_ONE`, field `owners`;
  exact `Share` arithmetic, and a sum too large to represent is "not one"), the same member never overlaps (409
  `OWNERSHIP_OVERLAP`) and no period starts or ends after today. The exclusion constraint `ownership_no_overlap_ex` (SQLSTATE
  23P01) is the backstop and is mapped to the same `OWNERSHIP_OVERLAP`.
- The old rows are closed and flushed before the new ones are inserted: the exclusion constraint is checked per statement
  and Hibernate runs inserts before updates, so a member who stays an owner would otherwise meet their own open row.
- `plot_status` is refreshed in the same transaction (`PlotStatusPolicy.afterOwnershipChange`): GREY with owners becomes
  GREEN, no owners becomes GREY, anything else is kept.
- Audit `OWNERSHIP_CHANGED` (entity `OWNERSHIP`, entity id = the plot id, label = the plot number) with `effectiveFrom`,
  `owners` (old and new: structured parameters, a JSON array `[{"member": "Last First", "share": "1/2"}, ...]` sorted by
  member name, see `OwnersAudit`) and `basis` when given. Names are in the audit payload only, never in logs.

## Correction (feature 007)

`POST /ownerships/{id}/corrections` with `{memberId, share, validFrom, validTo?, basis?, reason, coOwnerShares[{ownershipId,
share}] (0..20)}` is one transaction in `OwnershipCommands.correct`. The body is a full replacement of the target record's
member, share, dates and basis; `coOwnerShares` replaces the shares of other records of the same plot in the same step
(so 1/2 + 1/2 becomes 2/3 + 1/3). It answers 200 with the `PlotOwnerships` of the record's plot. Records are never deleted.

- Order of work: read the plot id of the record as a scalar (404 when missing or of another cooperative), lock the plot
  (`FOR UPDATE`), refuse an archived plot (409 `PLOT_ARCHIVED`), re-read the plot's rows fresh, then validate the request
  at once as a 400: bad shares (`SHARE_INVALID` on `share` or `coOwnerShares[i].share`), a co-owner id that is not a row of
  the same plot, is the target itself (`OWNERSHIP_NOT_FOUND` on `coOwnerShares[i].ownershipId`) or is repeated (`INVALID`),
  and a blank `reason` (`REQUIRED`). The member is locked `FOR SHARE`: unknown is 400 `MEMBER_NOT_FOUND` on `memberId`;
  archived is 409 `MEMBER_ARCHIVED` on `memberId`, unless it is the record's own member, so a historical record of a member
  archived later can still be corrected, but never as a current record: an archived member with no `validTo` is refused
  (409 `MEMBER_ARCHIVED`, field `validTo`).
- A correction that changes nothing is a 200 without an audit entry. Otherwise `OwnershipRules.checkTimeline` runs on the
  whole plot timeline after the correction, and the first violation is mapped to a field: `SHARES_NOT_ONE` on `share`
  (unless the target's dates moved and caused the first failing day: other owners on a day the target newly covers is
  `OWNERSHIP_OVERLAP` on `validFrom` when the day is before the old start, else on `validTo`; a day the target no longer
  covers is `SHARES_NOT_ONE` on the moved date),
  `OWNERSHIP_OVERLAP` on `validFrom` (the record starts inside another period of its member) or `validTo`,
  `OWNERSHIP_DATE_INVALID` on `validFrom` or `validTo` for a future date, and for an end before the start (checked first
  in the command). So the end date of the only current record may be set (the plot then has no owner and becomes GREY),
  but not the end date of one co-owner of two, which breaks the sum.
- The changed rows are written and flushed together; only the target changes member or dates, so no intermediate state
  can meet the exclusion constraint, whose violation (SQLSTATE 23P01) is still mapped to 409 `OWNERSHIP_OVERLAP`.
  `plot_status` is refreshed in the same transaction from the current rows.
- Audit `OWNERSHIP_CORRECTED` (entity `OWNERSHIP`, entity id = the plot id, label = the plot number) with `owners` (old and
  new value of every changed record: a JSON array `[{"member": "Last First", "share": "1/2", "from": "2020-01-01",
  "to": "2026-02-28"}, ...]` sorted by member name, with `to` absent while the record is current), `basis` when it changed, and `reason`.

## Status

`PlotStatusPolicy`: common land is always `GREY`; every other plot shows its cached `plot_status` (a missing row is `GREY`).
Until debts exist, the demo data and the ownership changes keep the cache as `GREEN` for a plot with a current owner and
`GREY` for a plot without one, with no debt. Creating a plot inserts the initial `GREY` row; the ledger rebuilds it later.

## Writes

`PlotCommands` (one transaction each, `@PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")` on the controller). Each change
publishes an `audit.AuditEvent` built from `PlotSnapshot.diff`, with the plot number as the label: `PLOT_CREATED`,
`PLOT_UPDATED`, `PLOT_ARCHIVED`, `PLOT_RESTORED`, `PLOT_DELETED`; see the `audit` README.

- Number: trimmed, unique among active plots of the cooperative ignoring case (`plot_tenant_number_uq` on
  `(tenant_id, lower(number)) where archived_at is null`). A pre-check and a mapping of the index give 409
  `PLOT_NUMBER_TAKEN` with `errors[{field: "number"}]`. An archived plot frees its number.
- Cadastral number: unique per cooperative, archived plots included (`plot_tenant_cadastral_uq`), 409
  `CADASTRAL_NUMBER_TAKEN`.
- Every write needs both `PLOTS_VIEW` and `PLOTS_EDIT` (403 otherwise). `PUT` replaces all fields, and an editor who cannot
  see the cadastral number, building flag or notes must not erase them blindly, so there is no write-only edit permission.
- A `PUT` that changes nothing is accepted, writes no audit entry and keeps `updated_at`.
- Archive and restore are idempotent: archiving an archived plot and restoring an active one return the card and write
  no audit entry. Restore checks the number again (pre-check plus index mapping): when another active plot took it, the
  answer is 409 `PLOT_NUMBER_TAKEN`. An archived plot can still be edited; its number is then not checked.
- Delete is for a plot created by mistake. It is allowed only for an active plot: an archived plot is refused with 409
  `PLOT_ARCHIVED` and must be restored first. The `plot_status` row goes with the plot through the foreign key's
  `ON DELETE CASCADE`. The audit entry has the old values of every field and `NONE` as the new state.

## Guards (feature 007)

`PlotCommands.archive`, `delete` and `update` lock the plot row first (`PlotRepository.findByIdForUpdate`, the same
lock a change of owner takes), so a guard and an ownership change of one plot run one after another.

- Archive: 409 `PLOT_HAS_OWNERS` while the plot has a current owner; end the ownership first (a change of owner with an
  empty list). Archiving an archived plot stays a no-op.
- Delete: 409 `PLOT_HAS_OWNERSHIP_HISTORY` when the plot has any ownership row, current or past. The foreign key of
  `ownership` (`ON DELETE RESTRICT`) stays as the database backstop.
- Update: a plot that has any ownership row cannot become common land: 409 `PLOT_HAS_OWNERSHIP_HISTORY` with
  `errors[{field: kind}]`. Common land with no history can become a plot, and other edits are unaffected.
- `OwnershipArchiveGuard` implements `members.MemberArchiveGuard`: 409 `MEMBER_OWNS_PLOT` when the member has a current
  ownership. Lock order: a change of owner takes the plot lock, then the members `FOR SHARE`; the member archive takes
  only the member row lock (after the chairman lock) and the guard takes no locks, so no cycle is possible. If the
  change commits first the archive sees the owner; if the archive commits first the change finds the member archived.

Depends on `identity`, `problem`, `audit` and `members` (public `MemberDirectory` only). The `Clock` bean (Europe/Kyiv) is
declared in `app.nadel.ClockConfiguration`.
