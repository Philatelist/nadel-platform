-- Runs once when the Postgres volume is created (as superuser postgres).
-- nadel_owner is the schema owner; Flyway migrations run as this role.
-- nadel_app   is the application role, without BYPASSRLS, so RLS policies apply to it.

CREATE ROLE nadel_owner LOGIN PASSWORD 'nadel_owner';
CREATE ROLE nadel_app   LOGIN PASSWORD 'nadel_app' NOBYPASSRLS;
CREATE DATABASE nadel OWNER nadel_owner;

CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;

\connect nadel
-- PostGIS is not a trusted extension, so a superuser installs it.
CREATE EXTENSION IF NOT EXISTS postgis;
GRANT USAGE ON SCHEMA public TO nadel_app;
