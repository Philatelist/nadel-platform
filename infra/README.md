# Local infrastructure

`make infra` starts PostgreSQL + PostGIS and Keycloak and waits until both are healthy
(Keycloak health is probed on its management port, `/health/ready`).

## Keycloak realm changes

`keycloak/nadel-realm.json` is imported only when the realm does not exist yet. After editing it,
recreate the stack with fresh volumes so the import runs again:

```bash
docker compose -f infra/docker-compose.yml down -v
make infra
```

`down -v` deletes all local data (Postgres volume included). Cooperative roles are not Keycloak roles
(ADR 0009); the realm only keeps `PLATFORM_OPERATOR`.

## Database for `make e2e`

`make e2e` never touches the demo database `nadel` that `make up` uses. It drops any stale `nadel_e2e`
database, creates a fresh one in the same Postgres (owner `nadel_owner`, PostGIS installed, `USAGE` on schema
`public` granted to `nadel_app`, as in `postgres/init/01-roles.sql`), starts the backend with `NADEL_DB_URL`
pointing at it (Flyway migrates it and loads the demo data of the `dev` profile), and drops it in the cleanup
trap after the backend stops, also when the run fails or is interrupted. Keycloak keeps its own database and users.
Override the name with `make e2e E2E_DB=other_name`.
