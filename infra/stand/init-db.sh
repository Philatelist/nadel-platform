#!/bin/bash
# Runs once when the Postgres volume is created (as superuser postgres). Same roles as infra/postgres/init/01-roles.sql,
# with passwords from the environment instead of dev values.
set -euo pipefail

psql -v ON_ERROR_STOP=1 -U postgres \
  -v owner_pw="$NADEL_OWNER_PASSWORD" \
  -v app_pw="$NADEL_APP_PASSWORD" \
  -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE nadel_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE nadel_app   LOGIN PASSWORD :'app_pw' NOBYPASSRLS;
CREATE DATABASE nadel OWNER nadel_owner;

CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';
CREATE DATABASE keycloak OWNER keycloak;

\connect nadel
CREATE EXTENSION IF NOT EXISTS postgis;
GRANT USAGE ON SCHEMA public TO nadel_app;
SQL
