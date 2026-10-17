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
