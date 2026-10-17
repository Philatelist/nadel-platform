# 0008. Platform operator sees aggregates only; support access is granted and logged

**Status:** accepted

## Context
ADR 0002 mentions platform-wide operator reports through a separate role, while CLAUDE.md says the platform operator cannot see cooperative data. Both are needed: the operator must bill subscriptions and monitor health, but cooperatives must trust that their members' data is private.

## Decision
- The `PLATFORM_OPERATOR` role has no `tenant_id` and therefore sees no cooperative rows through RLS.
- Platform reports (number of cooperatives, plots, active users, storage used, subscription status) come from a separate read-only database role over aggregate views that contain no personal or financial detail.
- Support access to a cooperative's data is granted by that cooperative's chairman for a limited time, scoped to that cooperative, and every action during it is written to the audit log visible to the chairman.

## Consequences
- The privacy promise is enforced by the database, which is a selling point.
- Support needs an explicit, time-boxed grant flow (to be built with the SaaS phase).
