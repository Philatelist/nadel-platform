# Backend

Spring Boot 4.1 modular monolith (Spring Modulith), Java 25, Maven wrapper. Rules: root [CLAUDE.md](../CLAUDE.md); architecture: [context/product/architecture.md](../context/product/architecture.md).

## Run and test

```bash
make infra      # from the repo root: Postgres + PostGIS and Keycloak
make backend    # ./mvnw spring-boot:run on http://localhost:8080 (dev profile loads demo data)
make test       # ./mvnw verify: tests, Testcontainers, module boundaries, JaCoCo floor
```

Docker must be running for tests (Testcontainers starts PostGIS).

## Layout

| Path | What it is |
|---|---|
| `src/main/java/app/nadel/<module>/` | Spring Modulith modules; root package is the public API, `internal/` is closed |
| `src/main/resources/db/migration/` | Flyway migrations, `V{N}__name.sql`, never edited after merge |
| `src/main/resources/db/demo/` | Repeatable demo data, dev profile only |
| `target/generated-sources/openapi/` | Interfaces and DTOs generated from `../api/openapi.yaml`, never edited |
| `src/test/java/app/nadel/` | Unit, integration (`IntegrationTest`) and module tests (`ModularityTests`) |

## Configuration

Environment variables (see [.env.example](../.env.example)): `NADEL_DB_URL`, `NADEL_DB_APP_USER`, `NADEL_DB_APP_PASSWORD`, `NADEL_DB_OWNER_USER`, `NADEL_DB_OWNER_PASSWORD`, `NADEL_OIDC_ISSUER`, `SPRING_PROFILES_ACTIVE`.
