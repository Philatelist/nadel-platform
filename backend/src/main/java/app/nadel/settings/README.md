# settings

Settings of a cooperative that the chairman, or any role granted `SETTINGS_MANAGE`, can change. Table
`cooperative_settings` (one row per cooperative, primary key `tenant_id`, RLS `tenant_isolation`; the primary key is the
tenant index). A cooperative with no row has the defaults, so there is no backfill and a new cooperative needs no seeding.

Settings (V7, and V14 for the ledger, feature 011):

| Setting | Column | Default | Meaning |
|---|---|---|---|
| `memberPlotStatusVisibility` | `member_plot_status_visibility` | `OWN_ONLY` | Who sees the debt status (colour) of a plot, below |
| `redAmount` | `red_amount numeric(14,2)`, CHECK `>= 0` | `1000.00` | A plot whose debt is above it is red (money-rules section 9) |
| `redDays` | `red_days integer`, CHECK `0..36500` | `90` | A plot whose oldest unpaid charge is more than this many days old is red |
| `lossesMode` | `losses_mode`, CHECK | `FIXED_PERCENT` | `FIXED_PERCENT` or `PROPORTIONAL` losses; with `FIXED_PERCENT` every electricity interval also gets a losses charge (ledger, spec 011 slice 4) |
| `lossesPercent` | `losses_percent numeric(5,2)`, CHECK `0..100` | `0.00` | The fixed losses percentage (`0.00` charges no losses) |
| `commonConsumerDefaultSplit` | `common_consumer_default_split`, CHECK | `CONNECTED_PLOTS` | `CONNECTED_PLOTS`, `EQUAL_PER_PLOT`, `SERVICE_USERS` or `NOT_CHARGED` (used from slice 7) |

A change of the red threshold applies to every status read from then on: `plots` derives the colour at read time.

The visibility setting:

| Value | Meaning |
|---|---|
| `OWN_ONLY` (default) | A member sees the status of the plots they own only; other plots are neutral |
| `ALL_PLOTS` | A member sees the status of every plot, but never amounts, owners or personal data of other plots |

People allowed to view plots (`PLOTS_VIEW`) always see every status. The plots module reads the setting to decide
whether a member sees the status of plots they do not own (hidden statuses are absent from the JSON, and the status
filter then matches only the member's own plots).

## Public API

| Type | Use |
|---|---|
| `CooperativeSettings` | `memberPlotStatusVisibility()`, `lossesMode()`, `lossesPercent()` (`BigDecimal`, two decimals), `commonConsumerDefaultSplit()`, `redAmount()` (`money.Money`), `redDays()` for the cooperative of the current transaction or request (`TenantContext`); the defaults when never saved, and also when no cooperative is bound (RLS would show no row). Call it inside a transaction |
| `PlotStatusVisibility` | `OWN_ONLY`, `ALL_PLOTS` |
| `LossesMode` | `FIXED_PERCENT`, `PROPORTIONAL` |
| `CommonConsumerSplit` | `CONNECTED_PLOTS`, `EQUAL_PER_PLOT`, `SERVICE_USERS`, `NOT_CHARGED` |

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /settings` | `getSettings` | any member of a cooperative (the platform operator gets 403 `PERMISSION_DENIED`) |
| `PUT /settings` | `updateSettings` | `SETTINGS_MANAGE` |

The cooperative always comes from the token, never from the request. `PUT` takes `CooperativeSettingsUpdate`: every
field is optional and a field left out keeps its value (each section of the settings page saves its own fields; `{}`
changes nothing). An unknown value, a malformed number, `redDays` outside 0..36500, a negative `redAmount` or one with
more than 12 integer digits (`INVALID`) and `lossesPercent` above 100 (`INVALID`) are 400.

## Writes

`SettingsService.update` is one transaction: it reads the current values, merges the fields of the request, and when
anything differs it upserts the whole row (`insert ... on conflict (tenant_id) do update`, so two first saves cannot
collide) and publishes one `audit.AuditEvent` `SETTINGS_UPDATED` (entity `SETTINGS`, entity id = the cooperative id,
label = the code `settings` (the client shows the name in the reader's language)) with one field change per changed
setting, old and new as codes and plain numbers (`memberPlotStatusVisibility` `OWN_ONLY`, `redAmount` `1000.00`,
`redDays` `90`, `lossesPercent` `0.00`, ...; a never-saved cooperative reports the defaults as the old values). Saving the
current values changes nothing and writes no audit entry.

Depends on `identity`, `audit`, `problem`, `tenancy` and `money` (`redAmount()` is `Money`).
