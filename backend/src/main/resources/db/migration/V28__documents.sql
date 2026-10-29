-- Feature 016: invoices and documents. invoice is an append-only register of issued invoice versions (the PDF is
-- rebuilt from the stored snapshot, never stored). certificate is the register of debt certificates; the only change a
-- row ever gets is its annulment, once. document_counter hands out gap-free certificate numbers per cooperative and
-- year. No foreign key to the ledger tables: the month is a plain date.

-- ---- invoice

-- One row per issued version of the invoice of a plot for a month. snapshot is the frozen content (personal data
-- sealed by the application, ADR 0016), content_hash a keyed hash of its canonical form for change detection.
CREATE TABLE invoice (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    plot_id      uuid          NOT NULL,
    month        date          NOT NULL,
    number       text          NOT NULL,
    version      int           NOT NULL,
    snapshot     jsonb         NOT NULL,
    content_hash bytea         NOT NULL,
    due          numeric(14,2) NOT NULL,
    as_of        timestamptz   NOT NULL,
    issued_by    uuid,
    issued_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT invoice_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT invoice_version_uq UNIQUE (tenant_id, plot_id, month, version),
    CONSTRAINT invoice_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT invoice_issued_by_fk FOREIGN KEY (tenant_id, issued_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT invoice_month_ck CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT invoice_version_ck CHECK (version >= 1),
    CONSTRAINT invoice_due_ck CHECK (due >= 0)
);

CREATE INDEX invoice_tenant_idx ON invoice (tenant_id);
CREATE INDEX invoice_month_plot_idx ON invoice (tenant_id, month, plot_id, version DESC);

ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Append-only; the owner's maintenance path (demo wipe) is allowed by ledger_append_only() (V13).
CREATE TRIGGER invoice_append_only BEFORE UPDATE OR DELETE ON invoice
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER invoice_no_truncate BEFORE TRUNCATE ON invoice
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON invoice FROM nadel_app;

-- ---- certificate

-- A debt certificate. number is Д-{year}-{seq}. The annul trio is set once, together.
CREATE TABLE certificate (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    year         smallint      NOT NULL,
    seq          int           NOT NULL,
    number       text          NOT NULL,
    plot_id      uuid          NOT NULL,
    as_of        date          NOT NULL,
    snapshot     jsonb         NOT NULL,
    total_debt   numeric(14,2) NOT NULL,
    issued_by    uuid,
    issued_at    timestamptz   NOT NULL DEFAULT now(),
    annulled_at  timestamptz,
    annulled_by  uuid,
    annul_reason text,
    CONSTRAINT certificate_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT certificate_seq_uq UNIQUE (tenant_id, year, seq),
    CONSTRAINT certificate_number_uq UNIQUE (tenant_id, number),
    CONSTRAINT certificate_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT certificate_issued_by_fk FOREIGN KEY (tenant_id, issued_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT certificate_annulled_by_fk FOREIGN KEY (tenant_id, annulled_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT certificate_seq_ck CHECK (seq >= 1),
    CONSTRAINT certificate_total_debt_ck CHECK (total_debt >= 0),
    -- The annulment is all or nothing; its reason has 1-500 characters.
    CONSTRAINT certificate_annul_ck CHECK (
        (annulled_at IS NULL AND annulled_by IS NULL AND annul_reason IS NULL)
        OR (annulled_at IS NOT NULL AND annulled_by IS NOT NULL AND annul_reason IS NOT NULL
            AND char_length(btrim(annul_reason)) BETWEEN 1 AND 500))
);

CREATE INDEX certificate_tenant_idx ON certificate (tenant_id);
CREATE INDEX certificate_plot_idx ON certificate (tenant_id, plot_id, issued_at DESC);

ALTER TABLE certificate ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON certificate
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The only change a row may get: the annul columns go from all null to all set, everything else stays. SQLSTATE 23514.
-- The owner's maintenance path (demo wipe) is allowed as for the ledger tables.
CREATE FUNCTION certificate_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.annulled_at IS NOT NULL
       OR (NEW.id, NEW.tenant_id, NEW.year, NEW.seq, NEW.number, NEW.plot_id, NEW.as_of, NEW.snapshot,
           NEW.total_debt, NEW.issued_by, NEW.issued_at)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.year, OLD.seq, OLD.number, OLD.plot_id, OLD.as_of, OLD.snapshot,
           OLD.total_debt, OLD.issued_by, OLD.issued_at) THEN
        RAISE EXCEPTION 'certificate_guard: a certificate can only be annulled, once'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'certificate_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER certificate_guard BEFORE UPDATE ON certificate
    FOR EACH ROW EXECUTE FUNCTION certificate_guard();
-- DELETE and TRUNCATE are refused (maintenance path excepted).
CREATE TRIGGER certificate_no_delete BEFORE DELETE ON certificate
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER certificate_no_truncate BEFORE TRUNCATE ON certificate
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE DELETE, TRUNCATE ON certificate FROM nadel_app;

-- ---- document_counter

-- The last number of a kind of document per cooperative and year (saved in the issuing transaction: no gaps).
CREATE TABLE document_counter (
    tenant_id  uuid   NOT NULL REFERENCES tenant (id),
    kind       text   NOT NULL,
    year       smallint NOT NULL,
    last_value bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, kind, year),
    CONSTRAINT document_counter_kind_ck CHECK (kind IN ('CERTIFICATE')),
    CONSTRAINT document_counter_last_value_ck CHECK (last_value >= 0)
);

CREATE INDEX document_counter_tenant_idx ON document_counter (tenant_id);

ALTER TABLE document_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_counter
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The only change allowed is +1: no skipped numbers, no going back. SQLSTATE 23514.
CREATE FUNCTION document_counter_step() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.kind <> OLD.kind OR NEW.year <> OLD.year
       OR NEW.last_value <> OLD.last_value + 1 THEN
        RAISE EXCEPTION 'document_counter_step: the document counter only moves forward by one'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'document_counter_step';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER document_counter_step BEFORE UPDATE ON document_counter
    FOR EACH ROW EXECUTE FUNCTION document_counter_step();

-- SELECT, INSERT and UPDATE stay granted; the counter is never deleted.
REVOKE DELETE, TRUNCATE ON document_counter FROM nadel_app;

-- ---- INVOICES_ISSUE, CERTIFICATES_ISSUE

-- V27 body plus (CHAIRMAN, INVOICES_ISSUE), (TREASURER, INVOICES_ISSUE), (CHAIRMAN, CERTIFICATES_ISSUE).
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW'),
        ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER'),
        ('TREASURER', 'INVOICES_ISSUE')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: targeted inserts (idempotent), never a re-run of seed_base_roles (V13 explains why).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, p.permission
FROM role r
JOIN (VALUES
    ('CHAIRMAN', 'INVOICES_ISSUE'), ('CHAIRMAN', 'CERTIFICATES_ISSUE'), ('TREASURER', 'INVOICES_ISSUE')
) AS p(base_code, permission) ON p.base_code = r.base_code
ON CONFLICT DO NOTHING;
