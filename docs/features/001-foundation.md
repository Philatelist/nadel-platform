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
Phase 1, in order: [`002-members-access.md`](002-members-access.md), [`003-ui-design-system.md`](003-ui-design-system.md), [`004-role-names.md`](004-role-names.md), [`005-ui-preferences.md`](005-ui-preferences.md), [`006-plots-registry.md`](006-plots-registry.md),
[`007-ownership.md`](007-ownership.md), [`008-excel-import.md`](008-excel-import.md).
