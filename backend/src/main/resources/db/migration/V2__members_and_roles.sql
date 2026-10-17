-- Spec 001: members, roles, role permissions, member roles. All tables are tenant data: tenant_id + RLS + index.
-- Composite foreign keys (tenant_id, x_id) make cross-cooperative links impossible.
-- Permission names are validated by the application (Java enum is the source of truth), not by a CHECK.

CREATE TABLE member (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    last_name         text        NOT NULL,
    first_name        text        NOT NULL,
    middle_name       text,
    phones            text[]      NOT NULL DEFAULT '{}',
    email             text,
    postal_address    text,
    delivery_channel  text        NOT NULL DEFAULT 'PAPER',
    telegram_username text,
    viber_phone       text,
    notes             text,
    keycloak_subject  text,
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT member_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT member_last_name_not_blank CHECK (btrim(last_name) <> ''),
    CONSTRAINT member_first_name_not_blank CHECK (btrim(first_name) <> ''),
    CONSTRAINT member_delivery_channel_ck CHECK (delivery_channel IN ('EMAIL', 'TELEGRAM', 'VIBER', 'PAPER')),
    CONSTRAINT member_email_normalized_ck CHECK (email IS NULL OR email = lower(btrim(email))),
    CONSTRAINT member_channel_contact_ck CHECK (
        (delivery_channel <> 'EMAIL' OR email IS NOT NULL)
        AND (delivery_channel <> 'TELEGRAM' OR telegram_username IS NOT NULL)
        AND (delivery_channel <> 'VIBER' OR viber_phone IS NOT NULL))
);

CREATE INDEX member_tenant_idx ON member (tenant_id);
CREATE UNIQUE INDEX member_tenant_email_uq ON member (tenant_id, lower(email))
    WHERE archived_at IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX member_tenant_subject_uq ON member (tenant_id, keycloak_subject)
    WHERE keycloak_subject IS NOT NULL;
CREATE INDEX member_tenant_name_idx ON member (tenant_id, lower(last_name), lower(first_name));
CREATE INDEX member_phones_gin ON member USING gin (phones);

ALTER TABLE member ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE role (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    base_code   text,
    name        text        NOT NULL,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT role_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT role_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT role_base_code_ck CHECK (base_code IS NULL OR base_code IN ('CHAIRMAN', 'TREASURER', 'MEMBER'))
);

CREATE INDEX role_tenant_idx ON role (tenant_id);
CREATE UNIQUE INDEX role_tenant_name_uq ON role (tenant_id, lower(name));
CREATE UNIQUE INDEX role_tenant_base_code_uq ON role (tenant_id, base_code) WHERE base_code IS NOT NULL;

ALTER TABLE role ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE role_permission (
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    role_id    uuid NOT NULL,
    permission text NOT NULL,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT role_permission_role_fk FOREIGN KEY (tenant_id, role_id)
        REFERENCES role (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX role_permission_tenant_idx ON role_permission (tenant_id);

ALTER TABLE role_permission ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permission
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE member_role (
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    member_id   uuid        NOT NULL,
    role_id     uuid        NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, role_id),
    CONSTRAINT member_role_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id),
    CONSTRAINT member_role_role_fk FOREIGN KEY (tenant_id, role_id)
        REFERENCES role (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX member_role_tenant_idx ON member_role (tenant_id);
CREATE INDEX member_role_role_idx ON member_role (role_id);

ALTER TABLE member_role ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_role
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Base roles of a cooperative with deterministic ids. Idempotent: never overwrites customisations.
-- Not SECURITY DEFINER: the caller's RLS applies, so the tenant must match app.tenant_id (or the caller is the owner).
-- Matrix: Chairman has all eight permissions (rows kept for display), Treasurer MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW,
-- Member none. Adding a permission later: a new migration with CREATE OR REPLACE of this function.
CREATE FUNCTION seed_base_roles(p_tenant uuid) RETURNS void
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
        ('CHAIRMAN', 'OWNERSHIP_EDIT'), ('CHAIRMAN', 'IMPORT_RUN'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives (no-op on a fresh database).
SELECT seed_base_roles(id) FROM tenant;
