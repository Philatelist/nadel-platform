-- Application role in the test container (test counterpart of infra/postgres/init).
CREATE ROLE nadel_app LOGIN PASSWORD 'nadel_app' NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO nadel_app;
