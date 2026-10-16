-- Phase 0: cooperatives, plots, status cache, isolation through RLS.
-- PostGIS is installed beforehand by a superuser (infra/postgres/init, the postgis image in tests).
-- Rule for all later migrations: a table with cooperative data = tenant_id + RLS + index + isolation test.

-- Application privileges on everything this role (the migration owner) creates in schema public.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nadel_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO nadel_app;

-- Cooperative of the current transaction; empty means no rows.
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE TABLE tenant (
    id          uuid PRIMARY KEY,
    name        text        NOT NULL,
    edrpou      text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = current_tenant_id())
    WITH CHECK (id = current_tenant_id());

CREATE TABLE plot (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    number            text          NOT NULL,
    street            text,
    kind              text          NOT NULL CHECK (kind IN ('PLOT', 'COMMON')),
    area_m2           numeric(10,2) NOT NULL CHECK (area_m2 >= 0),
    cadastral_number  text,
    boundary          geometry(Polygon, 4326),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, number)
);

CREATE INDEX plot_tenant_idx ON plot (tenant_id);
CREATE INDEX plot_boundary_gix ON plot USING gist (boundary);

ALTER TABLE plot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plot
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Status cache for the map. Rebuilt from the ledger (arrives in phase 2).
CREATE TABLE plot_status (
    plot_id           uuid          PRIMARY KEY REFERENCES plot (id) ON DELETE CASCADE,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    status            text          NOT NULL CHECK (status IN ('RED', 'YELLOW', 'GREEN', 'GREY')),
    debt              numeric(14,2) NOT NULL DEFAULT 0,
    oldest_debt_date  date,
    updated_at        timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX plot_status_tenant_idx ON plot_status (tenant_id);

ALTER TABLE plot_status ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plot_status
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
