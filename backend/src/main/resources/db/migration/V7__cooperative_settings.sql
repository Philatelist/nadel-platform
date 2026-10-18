-- Spec 007 (slice 1): cooperative settings and the SETTINGS_MANAGE permission.
-- Compatibility with the previous release (expand/contract): a new table and a new permission row only. The previous
-- release neither reads nor writes cooperative_settings, and it ignores a permission name it does not know.

-- One row per cooperative. A missing row means the defaults (member_plot_status_visibility = 'OWN_ONLY'), so no
-- backfill is needed and a new cooperative needs no seeding.
CREATE TABLE cooperative_settings (
    tenant_id                     uuid PRIMARY KEY REFERENCES tenant (id),
    member_plot_status_visibility text        NOT NULL DEFAULT 'OWN_ONLY',
    updated_at                    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT cooperative_settings_visibility_ck CHECK (member_plot_status_visibility IN ('OWN_ONLY', 'ALL_PLOTS'))
);

-- No separate tenant_id index: the primary key is on tenant_id and serves every lookup by cooperative.

ALTER TABLE cooperative_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cooperative_settings
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Base roles of a cooperative (V2 body) with one more Chairman permission, SETTINGS_MANAGE. Chairman has all nine
-- permissions (rows kept for display), Treasurer MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW, Member none.
-- Not SECURITY DEFINER: the caller's RLS applies, so the tenant must match app.tenant_id (or the caller is the owner).
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives get the new Chairman permission row (idempotent).
SELECT seed_base_roles(id) FROM tenant;
