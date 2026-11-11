-- Invitations of members (spec 020): who may sign in and the one-time links that give them a password.
--
-- member_access: one row per member with the state of the member's sign-in access (NONE, INVITED, ACTIVE, DISABLED).
-- member_invitation: the links; only the SHA-256 hash of the token is stored, never the token. email_bidx is the
-- member's e-mail blind index at the time of the invitation, so a link dies when the e-mail changes.
-- members_invitation_tenant(bytea): the third cross-cooperative SECURITY DEFINER function (ADR 0020, after the two of
-- ADR 0018): the public accept endpoint has no token yet, so it finds the cooperative of a link by the token hash. It
-- returns the identifier only; the rest is read bound to that cooperative under RLS.
-- RLS is enabled but not forced (as in V29 and V32): the owner reads past the policies, which the function relies on.

SET LOCAL lock_timeout = '10s';

-- ---- member_access

CREATE TABLE member_access (
    member_id        uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    state            text        NOT NULL DEFAULT 'NONE',
    invited_at       timestamptz,
    activated_at     timestamptz,
    disabled_at      timestamptz,
    last_sign_in_at  timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT member_access_state_ck CHECK (state IN ('NONE', 'INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT member_access_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX member_access_tenant_idx ON member_access (tenant_id);

ALTER TABLE member_access ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_access
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

REVOKE DELETE, TRUNCATE ON member_access FROM nadel_app;

-- ---- member_invitation

CREATE TABLE member_invitation (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    member_id   uuid        NOT NULL,
    token_hash  bytea       NOT NULL,
    email_bidx  bytea       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    sent_at     timestamptz,
    used_at     timestamptz,
    revoked_at  timestamptz,
    created_by  uuid,
    CONSTRAINT member_invitation_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT member_invitation_token_hash_ck CHECK (octet_length(token_hash) = 32),
    CONSTRAINT member_invitation_email_bidx_ck CHECK (octet_length(email_bidx) = 32),
    CONSTRAINT member_invitation_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX member_invitation_tenant_idx ON member_invitation (tenant_id);

-- One open (sent, unused, not revoked) link per member. A pending row (sent_at null) is not counted, so a send in
-- progress does not block the previous link until the new one is delivered.
CREATE UNIQUE INDEX member_invitation_open_uq ON member_invitation (tenant_id, member_id)
    WHERE sent_at IS NOT NULL AND used_at IS NULL AND revoked_at IS NULL;

ALTER TABLE member_invitation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_invitation
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

REVOKE UPDATE, DELETE, TRUNCATE ON member_invitation FROM nadel_app;
GRANT UPDATE (sent_at, used_at, revoked_at) ON member_invitation TO nadel_app;

-- ---- members_invitation_tenant

-- The cooperative of an invitation by its token hash, or null. Identifier only, never a row.
CREATE FUNCTION members_invitation_tenant(p_token_hash bytea) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT i.tenant_id FROM public.member_invitation i WHERE i.token_hash = p_token_hash
$$;

REVOKE ALL ON FUNCTION members_invitation_tenant(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION members_invitation_tenant(bytea) TO nadel_app;

-- ---- MEMBERS_INVITE

-- V32 body plus (CHAIRMAN, MEMBERS_INVITE). role_permission.permission is plain text without a CHECK, so no DDL.
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
        ('CHAIRMAN', 'METERS_VIEW'), ('CHAIRMAN', 'METERS_MANAGE'), ('CHAIRMAN', 'READINGS_ENTER'),
        ('CHAIRMAN', 'READINGS_APPROVE'),
        ('CHAIRMAN', 'CHARGES_VIEW'), ('CHAIRMAN', 'CHARGES_RUN'), ('CHAIRMAN', 'CHARGES_REVERSE'),
        ('CHAIRMAN', 'PERIODS_CLOSE'), ('CHAIRMAN', 'RECALCULATION_RUN'),
        ('CHAIRMAN', 'REPORTS_VIEW'), ('CHAIRMAN', 'REPORT_TEMPLATES_MANAGE'),
        ('CHAIRMAN', 'PAYMENTS_VIEW'), ('CHAIRMAN', 'PAYMENTS_ENTER'), ('CHAIRMAN', 'PAYMENTS_REVERSE'),
        ('CHAIRMAN', 'INVOICES_ISSUE'), ('CHAIRMAN', 'CERTIFICATES_ISSUE'),
        ('CHAIRMAN', 'ANNOUNCEMENTS_SEND'),
        ('CHAIRMAN', 'CASHBOOK_VIEW'), ('CHAIRMAN', 'CASHBOOK_ENTER'), ('CHAIRMAN', 'CASHBOOK_APPROVE'),
        ('CHAIRMAN', 'MEMBERS_INVITE'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW'),
        ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER'),
        ('TREASURER', 'INVOICES_ISSUE'),
        ('TREASURER', 'CASHBOOK_VIEW'), ('TREASURER', 'CASHBOOK_ENTER')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
    SELECT seed_treasury(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: a targeted insert (idempotent), never a re-run of seed_base_roles (V13 explains why).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, 'MEMBERS_INVITE'
FROM role r
WHERE r.base_code = 'CHAIRMAN'
ON CONFLICT DO NOTHING;

-- ---- backfill

-- A member already linked to a sign-in account has access. Everyone else starts without a row (read as NONE).
INSERT INTO member_access (member_id, tenant_id, state, activated_at)
SELECT m.id, m.tenant_id, 'ACTIVE', now()
FROM member m
WHERE m.keycloak_subject IS NOT NULL
ON CONFLICT DO NOTHING;
