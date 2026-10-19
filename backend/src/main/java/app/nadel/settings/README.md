# settings

Settings of a cooperative that the chairman, or any role granted `SETTINGS_MANAGE`, can change. Table
`cooperative_settings` (one row per cooperative, primary key `tenant_id`, RLS `tenant_isolation`; the primary key is the
tenant index). A cooperative with no row has the defaults, so there is no backfill and a new cooperative needs no seeding.

Today there is one setting, `memberPlotStatusVisibility`: who sees the debt status (colour) of a plot.

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
| `CooperativeSettings` | `memberPlotStatusVisibility()` for the cooperative of the current transaction or request (`TenantContext`); `OWN_ONLY` when never saved. Call it inside a transaction |
| `PlotStatusVisibility` | `OWN_ONLY`, `ALL_PLOTS` |

## Endpoints

| Endpoint | Operation | Who |
|---|---|---|
| `GET /settings` | `getSettings` | any member of a cooperative (the platform operator gets 403 `PERMISSION_DENIED`) |
| `PUT /settings` | `updateSettings` | `SETTINGS_MANAGE` |

The cooperative always comes from the token, never from the request. An unknown value is 400.

## Writes

`SettingsService.update` is one transaction: it reads the current value, and when the requested value differs it upserts
the row (`insert ... on conflict (tenant_id) do update`, so two first saves cannot collide) and publishes an
`audit.AuditEvent` `SETTINGS_UPDATED` (entity `SETTINGS`, entity id = the cooperative id, label "Cooperative settings",
field `memberPlotStatusVisibility` old and new; a never-saved cooperative reports `OWN_ONLY` as the old value). Saving the
current value changes nothing and writes no audit entry.

Depends on `identity`, `audit`, `problem` and `tenancy`.
