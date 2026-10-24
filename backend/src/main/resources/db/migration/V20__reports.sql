-- Spec 013 (slice 1): reports. Uploaded report templates (report_template + append-only report_template_version),
-- cooperative requisites in cooperative_settings, and two more permissions (REPORTS_VIEW, REPORT_TEMPLATES_MANAGE).
-- role_permission.permission is plain text without a CHECK or foreign key, so a new permission name needs no DDL.
-- Compatibility with the previous release (expand/contract): only new tables, nullable columns, and permission rows
-- that the previous release ignores.

SET LOCAL lock_timeout = '10s';

-- ---- report_template

-- One template of a cooperative. slot is null for a free template or 'TAX' for the fixed tax statement slot. The file
-- lives in report_template_version; an archived template keeps its versions and frees its name and slot.
CREATE TABLE report_template (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    slot        text,
    name        text        NOT NULL,
    description text,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    archived_by uuid,
    archived_at timestamptz,
    CONSTRAINT report_template_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT report_template_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT report_template_archived_by_fk FOREIGN KEY (tenant_id, archived_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT report_template_slot_ck CHECK (slot IN ('TAX')),
    CONSTRAINT report_template_name_ck CHECK (btrim(name) <> '' AND char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT report_template_description_ck CHECK (char_length(description) <= 500),
    CONSTRAINT report_template_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT report_template_archived_ck CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);

-- An active template name is unique per cooperative, ignoring case. SQLSTATE 23505.
CREATE UNIQUE INDEX report_template_name_uq ON report_template (tenant_id, lower(name)) WHERE status = 'ACTIVE';
-- One active template per slot.
CREATE UNIQUE INDEX report_template_slot_uq ON report_template (tenant_id, slot)
    WHERE slot IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX report_template_tenant_idx ON report_template (tenant_id, status);

ALTER TABLE report_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_template
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- UPDATE stays for the archive; a template is never deleted.
REVOKE DELETE, TRUNCATE ON report_template FROM nadel_app;

-- ---- report_template_version

-- One uploaded file of a template. Append-only: a new upload is a new version, the content never changes.
CREATE TABLE report_template_version (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    template_id uuid        NOT NULL,
    version_no  integer     NOT NULL,
    file_name   text        NOT NULL,
    content     bytea       NOT NULL,
    size_bytes  integer     NOT NULL,
    sha256      char(64)    NOT NULL,
    uploaded_by uuid,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT report_template_version_tenant_id_uq UNIQUE (tenant_id, id),
    -- Backstop for the next version number (max + 1 under a row lock on the template). SQLSTATE 23505.
    CONSTRAINT report_template_version_no_uq UNIQUE (tenant_id, template_id, version_no),
    CONSTRAINT report_template_version_template_fk FOREIGN KEY (tenant_id, template_id)
        REFERENCES report_template (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT report_template_version_uploaded_by_fk FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT report_template_version_no_ck CHECK (version_no >= 1),
    CONSTRAINT report_template_version_file_name_ck CHECK (char_length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT report_template_version_size_ck CHECK (size_bytes BETWEEN 1 AND 2097152
        AND size_bytes = octet_length(content)),
    CONSTRAINT report_template_version_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- The file is already compressed (xlsx is a zip): store it out of line without compression.
ALTER TABLE report_template_version ALTER COLUMN content SET STORAGE EXTERNAL;

CREATE INDEX report_template_version_tenant_idx ON report_template_version (tenant_id, template_id, version_no DESC);

ALTER TABLE report_template_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_template_version
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Append-only like the ledger: the same guard function as V13 (stops nadel_app and the owner, except nadel_owner with
-- app.ledger_maintenance = 'on' for demo data and test helpers).
CREATE TRIGGER report_template_version_append_only BEFORE UPDATE OR DELETE ON report_template_version
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER report_template_version_no_truncate BEFORE TRUNCATE ON report_template_version
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON report_template_version FROM nadel_app;

-- ---- cooperative_settings: requisites

-- Printed on reports and filled into templates. All optional; the IBAN checksum (mod 97) is checked in the application.
ALTER TABLE cooperative_settings ADD COLUMN official_name text;
ALTER TABLE cooperative_settings ADD COLUMN legal_address text;
ALTER TABLE cooperative_settings ADD COLUMN bank_name text;
ALTER TABLE cooperative_settings ADD COLUMN iban text;

ALTER TABLE cooperative_settings ADD CONSTRAINT cooperative_settings_official_name_ck
    CHECK (char_length(official_name) BETWEEN 1 AND 300);
ALTER TABLE cooperative_settings ADD CONSTRAINT cooperative_settings_legal_address_ck
    CHECK (char_length(legal_address) BETWEEN 1 AND 500);
ALTER TABLE cooperative_settings ADD CONSTRAINT cooperative_settings_bank_name_ck
    CHECK (char_length(bank_name) BETWEEN 1 AND 200);
ALTER TABLE cooperative_settings ADD CONSTRAINT cooperative_settings_iban_ck
    CHECK (iban ~ '^UA[0-9]{27}$');

-- ---- REPORTS_VIEW, REPORT_TEMPLATES_MANAGE

-- V19 body plus (CHAIRMAN, REPORTS_VIEW), (CHAIRMAN, REPORT_TEMPLATES_MANAGE), (TREASURER, REPORTS_VIEW).
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: targeted inserts (idempotent), never a re-run of seed_base_roles (V13 explains why).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, p.permission
FROM role r
JOIN (VALUES ('CHAIRMAN', 'REPORTS_VIEW'), ('CHAIRMAN', 'REPORT_TEMPLATES_MANAGE'), ('TREASURER', 'REPORTS_VIEW'))
    AS p(base_code, permission) ON p.base_code = r.base_code
ON CONFLICT DO NOTHING;
