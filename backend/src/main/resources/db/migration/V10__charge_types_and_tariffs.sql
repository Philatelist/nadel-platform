-- Spec 009: charge types, tariffs with dated versions, the plots a tariff applies to, and the TARIFFS_* permissions.
-- Compatibility with the previous release (expand/contract): new tables, two new functions, and permission rows that
-- the previous release ignores (it does not know the names). seed_base_roles keeps its signature and behaviour for the
-- rows it wrote before; it only writes more. The previous release never reads the new tables.
--
-- Every table has tenant_id, RLS with the tenant_isolation policy, an index on tenant_id and a UNIQUE (tenant_id, id)
-- so that composite foreign keys (tenant_id, x_id) keep every link inside one cooperative. Text columns with a fixed
-- set of values use named CHECK constraints (no enum types), as in the earlier tables.

-- What the cooperative charges for. Four rows are seeded for every cooperative (seed_charge_types); the chairman can add
-- more, rename, describe, deactivate, reorder and archive them. A row is never deleted.
-- 'code' is set only on the four seeded types. 'name' of a seeded type is the English default until the chairman renames
-- it; name_customized tells the client whether to show its own localised default name (the pattern of role.name).
-- The order of the list is (position, id); position has no unique constraint so a reorder never meets a transient clash.
CREATE TABLE charge_type (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    code            text,
    name            text        NOT NULL,
    name_customized boolean     NOT NULL DEFAULT false,
    description     text,
    active          boolean     NOT NULL DEFAULT true,
    position        integer     NOT NULL,
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT charge_type_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT charge_type_code_ck CHECK (code IS NULL OR code IN ('MEMBERSHIP', 'ELECTRICITY', 'LAND_TAX', 'WATER')),
    CONSTRAINT charge_type_name_ck CHECK (btrim(name) <> '' AND char_length(name) <= 100),
    CONSTRAINT charge_type_description_ck CHECK (description IS NULL OR char_length(description) <= 500)
);

CREATE INDEX charge_type_tenant_idx ON charge_type (tenant_id);
-- A seeded code appears once per cooperative, archived rows included (seeding is idempotent on the deterministic id).
CREATE UNIQUE INDEX charge_type_tenant_code_uq ON charge_type (tenant_id, code) WHERE code IS NOT NULL;
-- Names are unique among the non-archived types, ignoring letter case; an archived type frees its name.
CREATE UNIQUE INDEX charge_type_tenant_name_uq ON charge_type (tenant_id, lower(name)) WHERE archived_at IS NULL;

ALTER TABLE charge_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charge_type
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A charge type is archived, never deleted (the tariffs under it stay in history).
REVOKE DELETE, TRUNCATE ON charge_type FROM nadel_app;

-- A tariff belongs to one charge type; its rates live in tariff_version. scope_kind says which plots it applies to:
-- all active plots that are not common land (minus exclusions) or the selected plots (rows of tariff_scope_plot).
CREATE TABLE tariff (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    charge_type_id uuid        NOT NULL,
    name           text        NOT NULL,
    scope_kind     text        NOT NULL DEFAULT 'ALL_PLOTS',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tariff_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT tariff_charge_type_fk FOREIGN KEY (tenant_id, charge_type_id)
        REFERENCES charge_type (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT tariff_name_ck CHECK (btrim(name) <> '' AND char_length(name) <= 100),
    CONSTRAINT tariff_scope_kind_ck CHECK (scope_kind IN ('ALL_PLOTS', 'SELECTED_PLOTS'))
);

CREATE INDEX tariff_tenant_idx ON tariff (tenant_id);
CREATE INDEX tariff_charge_type_idx ON tariff (tenant_id, charge_type_id);

ALTER TABLE tariff ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tariff
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A tariff is archived with its charge type, never deleted.
REVOKE DELETE, TRUNCATE ON tariff FROM nadel_app;

-- One row per rate that applies from a date. A version applies until the day before the next version's valid_from, or
-- without end; the end date is derived, never stored. The rate keeps up to four decimals and is never rounded here.
-- ONE_OFF belongs to FIXED_ONE_TIME and only to it, and a one-off version has the due date of its payment.
-- Versions are append-only (the V3 pattern of audit_entry): a change of rate is a new version.
CREATE TABLE tariff_version (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid          NOT NULL REFERENCES tenant (id),
    tariff_id  uuid          NOT NULL,
    valid_from date          NOT NULL,
    basis      text          NOT NULL,
    frequency  text          NOT NULL,
    rate       numeric(14,4) NOT NULL,
    due_date   date,
    note       text,
    created_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT tariff_version_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT tariff_version_tariff_fk FOREIGN KEY (tenant_id, tariff_id)
        REFERENCES tariff (tenant_id, id) ON DELETE RESTRICT,
    -- Two versions of one tariff never start on the same date (SQLSTATE 23505, mapped to TARIFF_VERSION_DATE_TAKEN).
    CONSTRAINT tariff_version_valid_from_uq UNIQUE (tenant_id, tariff_id, valid_from),
    CONSTRAINT tariff_version_basis_ck CHECK (basis IN ('PER_PLOT', 'PER_AREA_SOTKA', 'PER_KWH', 'PER_M3',
        'FIXED_ONE_TIME')),
    CONSTRAINT tariff_version_frequency_ck CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'ONE_OFF')),
    CONSTRAINT tariff_version_rate_ck CHECK (rate >= 0),
    CONSTRAINT tariff_version_note_ck CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT tariff_version_one_off_ck CHECK (
        (basis = 'FIXED_ONE_TIME') = (frequency = 'ONE_OFF')
        AND (frequency = 'ONE_OFF') = (due_date IS NOT NULL))
);

CREATE INDEX tariff_version_tenant_idx ON tariff_version (tenant_id);

ALTER TABLE tariff_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tariff_version
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- V1 default privileges grant UPDATE and DELETE; versions must not allow them (TRUNCATE revoked defensively).
REVOKE UPDATE, DELETE, TRUNCATE ON tariff_version FROM nadel_app;

-- The plots a tariff names: the selected plots of a SELECTED_PLOTS tariff, or the exclusions (with a reason) of an
-- ALL_PLOTS tariff. Whether the kind matches tariff.scope_kind, and that common land is never named, span tables and are
-- checked by the service. A plot can be deleted only while it has no history (feature 006), so a scope row must not
-- block that: the plot foreign key cascades. Rows are replaced when the scope changes (DELETE stays granted; the audit
-- log keeps the history).
CREATE TABLE tariff_scope_plot (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    tariff_id  uuid        NOT NULL,
    plot_id    uuid        NOT NULL,
    kind       text        NOT NULL,
    reason     text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tariff_id, plot_id),
    CONSTRAINT tariff_scope_plot_tariff_fk FOREIGN KEY (tenant_id, tariff_id)
        REFERENCES tariff (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT tariff_scope_plot_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT tariff_scope_plot_kind_ck CHECK (kind IN ('SELECTED', 'EXCLUDED')),
    -- An exclusion always says why; a selected plot has no reason.
    CONSTRAINT tariff_scope_plot_reason_ck CHECK (
        (kind = 'EXCLUDED') = (reason IS NOT NULL)
        AND (reason IS NULL OR (reason = btrim(reason) AND char_length(reason) BETWEEN 1 AND 500)))
);

-- The plots of a tariff are found by the primary key. This index leads with tenant_id (it also serves as the tenant_id
-- index) and answers "which tariffs name this plot", the hot path of the expected amount of a plot.
CREATE INDEX tariff_scope_plot_tenant_plot_idx ON tariff_scope_plot (tenant_id, plot_id);

ALTER TABLE tariff_scope_plot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tariff_scope_plot
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The four charge types every cooperative starts with, with deterministic ids so that seeding is idempotent and never
-- overwrites what a chairman changed: water is inactive until the cooperative has water meters. English names; clients
-- show their localised default name while name_customized is false.
-- Not SECURITY DEFINER: the caller's RLS applies, so the tenant must match app.tenant_id (or the caller is the owner).
CREATE FUNCTION seed_charge_types(p_tenant uuid) RETURNS void
    LANGUAGE sql
AS $$
    INSERT INTO charge_type (id, tenant_id, code, name, position, active)
    SELECT md5(p_tenant::text || ':CHARGE_TYPE:' || t.code)::uuid, p_tenant, t.code, t.name, t.pos, t.is_active
    FROM (VALUES
        ('MEMBERSHIP', 'Membership fee', 1, true),
        ('ELECTRICITY', 'Electricity', 2, true),
        ('LAND_TAX', 'Land tax', 3, true),
        ('WATER', 'Water', 4, false)
    ) AS t(code, name, pos, is_active)
    ON CONFLICT DO NOTHING;
$$;

GRANT EXECUTE ON FUNCTION seed_charge_types(uuid) TO nadel_app;

-- Base roles of a cooperative (V7 body) with TARIFFS_VIEW and TARIFFS_MANAGE for the Chairman and TARIFFS_VIEW for the
-- Treasurer, followed by the charge types, so a new cooperative gets both from this one call. Chairman has all eleven
-- permissions (rows kept for display), Treasurer MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW, TARIFFS_VIEW, Member none.
-- The role descriptions are unchanged. Not SECURITY DEFINER (see seed_charge_types).
CREATE OR REPLACE FUNCTION seed_base_roles(p_tenant uuid) RETURNS void
    LANGUAGE sql
AS $$
    INSERT INTO role (id, tenant_id, base_code, name, description)
    VALUES (md5(p_tenant::text || ':CHAIRMAN')::uuid, p_tenant, 'CHAIRMAN', 'Chairman', 'Full access'),
           (md5(p_tenant::text || ':TREASURER')::uuid, p_tenant, 'TREASURER', 'Treasurer', 'Read access to members, plots and audit'),
           (md5(p_tenant::text || ':MEMBER')::uuid, p_tenant, 'MEMBER', 'Member', 'Own data only')
    ON CONFLICT DO NOTHING;

    INSERT INTO role_permission (tenant_id, role_id, permission)
    SELECT p_tenant, md5(p_tenant::text || ':' || m.code)::uuid, m.permission
    FROM (VALUES
        ('CHAIRMAN', 'MEMBERS_VIEW'), ('CHAIRMAN', 'MEMBERS_EDIT'), ('CHAIRMAN', 'ROLES_MANAGE'),
        ('CHAIRMAN', 'AUDIT_VIEW'), ('CHAIRMAN', 'PLOTS_VIEW'), ('CHAIRMAN', 'PLOTS_EDIT'),
        ('CHAIRMAN', 'OWNERSHIP_EDIT'), ('CHAIRMAN', 'IMPORT_RUN'), ('CHAIRMAN', 'SETTINGS_MANAGE'),
        ('CHAIRMAN', 'TARIFFS_VIEW'), ('CHAIRMAN', 'TARIFFS_MANAGE'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives get the new permission rows by a targeted insert, never by re-running seed_base_roles: that
-- would also restore any permission a chairman had removed from a Treasurer role. Chairman gets two rows, Treasurer one
-- (idempotent).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, g.permission
FROM role r
JOIN (VALUES
    ('CHAIRMAN', 'TARIFFS_VIEW'), ('CHAIRMAN', 'TARIFFS_MANAGE'), ('TREASURER', 'TARIFFS_VIEW')
) AS g(code, permission) ON g.code = r.base_code
ON CONFLICT DO NOTHING;

-- Existing cooperatives get their four charge types (idempotent; no-op on a fresh database).
SELECT seed_charge_types(id) FROM tenant;
