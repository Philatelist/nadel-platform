# 0002. Multi-tenancy: shared schema + tenant_id + Row Level Security

**Status:** accepted

## Context
Cooperatives must never see each other's data, whatever bug the code has. Options: a database per tenant, a schema per tenant, or a shared schema with `tenant_id`.

## Decision
Shared schema. Every table with cooperative data has `tenant_id` and an RLS policy:
`USING (tenant_id = current_tenant_id())`, where `current_tenant_id()` reads `app.tenant_id`.
The application runs as the `nadel_app` role without `BYPASSRLS`; migrations run as the owner `nadel_owner`.
`TenantAwareTransactionManager` runs `set_config('app.tenant_id', <from token>, true)` at the start of every transaction (scoped to the transaction, so it is safe with a connection pool).

## Consequences
- Isolation is enforced by the database. A query without a tenant context returns 0 rows, not someone else's data.
- One set of migrations for everyone; platform-wide operator reports go through a separate role.
- A large customer can be moved to its own database (same code, different datasource).
- Every new table needs a policy and an isolation test (rule in CLAUDE.md).
