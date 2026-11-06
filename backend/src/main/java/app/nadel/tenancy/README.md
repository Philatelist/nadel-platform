# tenancy

Cooperatives (tenants) and the isolation of their data (ADR 0002). Table `tenant` (RLS `tenant_isolation`:
`id = current_tenant_id()` for reading and writing).

## Public API

- `TenantContext`: the current cooperative, from the `tenant_id` claim of the token or bound with `runAs(id, action)`
  for work without a token (listeners, jobs, commands). Never from a request body or parameter.
- `TenantDirectory` / `TenantSummary`: the current cooperative's name and ЄДРПОУ.
- `TenantProvisioning.ensure(id, name)` (feature 020, `SqlTenantProvisioning`): makes the cooperative exist. With the
  cooperative bound (`runAs`) and in one transaction as the application role: `insert into tenant ... on conflict (id)
  do nothing` and, only when the row is new, `select seed_base_roles(id)` (base roles, charge types, treasury accounts and
  categories). Answers whether it created the cooperative. An existing cooperative is left exactly as it is: its name is
  not changed and it is not seeded again, because a second `seed_base_roles` would give back a permission the board
  removed from the Treasurer role (the reason V13 forbids re-running it). Used only by the first-chairman command of
  `members` (no operator console yet). Logs the id only.

## What is here

- `TenantAwareTransactionManager` sets `app.tenant_id` (`set_config(..., true)`) at the start of every transaction;
  without a cooperative it sets an empty string and RLS lets no rows through.
- `JpaTenantDirectory` reads the cooperative's own `tenant` row.
