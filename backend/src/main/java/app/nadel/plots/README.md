# plots

Plots and common land of a cooperative as records, and the debt status (color) shown for them. Tables: `plot` and
`plot_status` (both under RLS `tenant_isolation`). A plot has a number, street, kind (`PLOT` or `COMMON`), area in square
metres, cadastral number, a building flag, notes, a nullable map polygon (`boundary`, SRID 4326) and a soft archive
(`archived_at`). Owners come with feature 007; polygons are imported in the map phase.

## Public API

None yet. Other modules will get a lookup API in the module root when 007 (ownership) needs one.

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /plots/map` | `getPlotMap` | any member of a cooperative |
| `GET /plots` | `listPlots` | any member of a cooperative |
| `GET /plots/{id}` | `getPlot` | any member of a cooperative |
| `POST /plots` | `createPlot` | `PLOTS_EDIT` |
| `PUT /plots/{id}` | `updatePlot` | `PLOTS_EDIT` |
| `POST /plots/{id}/archive` | `archivePlot` | `PLOTS_EDIT` |
| `POST /plots/{id}/restore` | `restorePlot` | `PLOTS_EDIT` |
| `DELETE /plots/{id}` | `deletePlot` | `PLOTS_EDIT` |

The platform operator has no cooperative data: every endpoint answers 403 `PERMISSION_DENIED` for it. The cooperative
always comes from the token, never from the request; a plot of another cooperative is 404 (RLS).

## Visibility

`PlotDtoMapper` is the only place that decides who sees what. A caller with `PLOTS_VIEW` sees area, cadastral number,
building flag, notes and timestamps; any other member sees only number, street, kind, status and the archived flag. A
caller without `PLOTS_VIEW` never sees archived plots (404 on the card, ignored `includeArchived` on the list) and cannot
search by cadastral number. Owners will join this rule with 007 (a member sees the details of their own plots).

## Status until 007

A plot shows `GREY` while it has no owner and no ledger: common land is always `GREY` (`PlotStatusPolicy`), and a plot
without a `plot_status` row is `GREY` too. Creating a plot inserts the initial `GREY` row with no debt; the ledger
rebuilds it later.

## Writes

`PlotCommands` (one transaction each, `@PreAuthorize("hasAuthority('PERM_PLOTS_EDIT')")` on the controller). Each change
publishes an `audit.AuditEvent` built from `PlotSnapshot.diff`, with the plot number as the label: `PLOT_CREATED`,
`PLOT_UPDATED`, `PLOT_ARCHIVED`, `PLOT_RESTORED`, `PLOT_DELETED`; see the `audit` README.

- Number: trimmed, unique among active plots of the cooperative ignoring case (`plot_tenant_number_uq` on
  `(tenant_id, lower(number)) where archived_at is null`). A pre-check and a mapping of the index give 409
  `PLOT_NUMBER_TAKEN` with `errors[{field: "number"}]`. An archived plot frees its number.
- Cadastral number: unique per cooperative, archived plots included (`plot_tenant_cadastral_uq`), 409
  `CADASTRAL_NUMBER_TAKEN`.
- `notes` need `PLOTS_VIEW`: an editor without it cannot write them (create stores none, update keeps the stored value).
- A `PUT` that changes nothing is accepted, writes no audit entry and keeps `updated_at`.
- Archive and restore are idempotent: archiving an archived plot and restoring an active one return the card and write
  no audit entry. Restore checks the number again (pre-check plus index mapping): when another active plot took it, the
  answer is 409 `PLOT_NUMBER_TAKEN`. An archived plot can still be edited; its number is then not checked.
- Delete is for a plot created by mistake. It is allowed only for an active plot: an archived plot is refused with 409
  `PLOT_ARCHIVED` and must be restored first. The `plot_status` row goes with the plot through the foreign key's
  `ON DELETE CASCADE`. The audit entry has the old values of every field and `NONE` as the new state. Feature 007 will
  also refuse a plot with ownership history; the seam is marked with a comment in `PlotCommands.delete`.

Depends on `identity`, `problem` and `audit`.
