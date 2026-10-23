# Local infrastructure

`make infra` starts PostgreSQL + PostGIS and Keycloak and waits until both are healthy
(Keycloak health is probed on its management port, `/health/ready`).

## PostGIS image (Apple Silicon)

The default image `postgis/postgis:17-3.5` is published for linux/amd64 only, so Docker on an Apple Silicon Mac
runs PostgreSQL under emulation: the local database and the backend tests are several times slower. Both
`docker-compose.yml` and the Testcontainers base class (`backend/src/test/java/app/nadel/IntegrationTest.java`)
read the image from `NADEL_POSTGIS_IMAGE`. On a Mac set it to the multi-arch build of the same PostgreSQL 17 +
PostGIS 3.5, made by a maintainer of the official images from the same sources:

```bash
# ~/.zshrc
export NADEL_POSTGIS_IMAGE=imresamu/postgis:17-3.5
```

A data volume is not portable between architectures: after switching, recreate it once with
`docker compose -f infra/docker-compose.yml down -v && make infra`. CI and servers keep the default image.

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
