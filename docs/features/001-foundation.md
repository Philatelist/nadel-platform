# 001. Foundation

## Why
The base every feature builds on: monorepo, sign-in, roles, cooperative isolation, CI.

## Done in the starter project
- Keycloak realm `nadel` with roles and test users from two cooperatives; `tenant_id` claim in the token.
- Backend: Spring Boot 4.1 + Modulith, modules `tenancy`, `identity`, `plots` (stub), JWT resource server.
- RLS: `nadel_app` role without BYPASSRLS, `app.tenant_id` set at the start of every transaction.
- Migration V1: `tenant`, `plot` (with a PostGIS polygon), `plot_status`, RLS policies; demo data for the dev profile.
- API: `GET /api/v1/me`, `GET /api/v1/plots` (GeoJSON FeatureCollection).
- Tests: cooperative isolation, module boundaries.
- Web: Keycloak sign-in (PKCE), "My account" page with `/me` data, plot map on MapLibre.
- Mobile: Expo app with a start screen using shared tokens and i18n.
- CI: backend build and tests, client typecheck, lint and build.

## Next
Phase 1, in order: [`002-members-access.md`](002-members-access.md), [`003-plots-registry.md`](003-plots-registry.md),
[`004-ownership.md`](004-ownership.md), [`005-excel-import.md`](005-excel-import.md).
