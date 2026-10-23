-- Spec 012 (slice 1): retroactive recalculation. A recalculation is one booking of corrections over past months
-- (recalculation + recalculation_line, both append-only), a correction of a closed month is a charge of kind
-- ADJUSTMENT that points at the REGULAR charge it adjusts (adjusts_charge_id), and the rows a recalculation books carry
-- its recalculation_id. A tariff version that a charge used cannot be deleted physically (charge_part is append-only
-- and its foreign key restricts), so it gets a soft delete (deleted_at) that frees its start date.
-- Compatibility with the previous release (expand/contract): only new tables and nullable columns, constraints that
-- accept every row the previous release writes, and one more permission row for the chairman.

SET LOCAL lock_timeout = '10s';

-- Both new tables follow V13: tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that
-- leads with tenant_id, UNIQUE (tenant_id, id) and composite foreign keys (tenant_id, x_id).

-- One recalculation: who booked it, why, and the totals. cause_ref_id has no foreign key (the version or reading that
-- caused it may be removed later). scope holds ids and codes only (months, charge type ids, plot ids, version and
-- reading ids), never display text or personal data.
CREATE TABLE recalculation (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    cause        text          NOT NULL,
    cause_ref_id uuid,
    scope        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    reason       text          NOT NULL,
    total        numeric(14,2) NOT NULL,
    line_count   integer       NOT NULL,
    plot_count   integer       NOT NULL,
    digest       text          NOT NULL,
    created_by   uuid,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT recalculation_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT recalculation_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_cause_ck CHECK (cause IN ('MANUAL', 'TARIFF_VERSION', 'READING')),
    CONSTRAINT recalculation_reason_ck CHECK (btrim(reason) <> '' AND char_length(reason) <= 500),
    CONSTRAINT recalculation_counts_ck CHECK (line_count >= 0 AND plot_count >= 0)
);

CREATE INDEX recalculation_tenant_idx ON recalculation (tenant_id);
-- The list, newest first.
CREATE INDEX recalculation_created_idx ON recalculation (tenant_id, created_at DESC);

ALTER TABLE recalculation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recalculation
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- One line of a recalculation: the original charge, what was booked, what is expected, and the rows the line booked.
-- run_id points at a losses or common consumer run and has no foreign key (two tables).
CREATE TABLE recalculation_line (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    recalculation_id     uuid          NOT NULL,
    plot_id              uuid          NOT NULL,
    charge_type_id       uuid          NOT NULL,
    service_from         date          NOT NULL,
    service_to           date          NOT NULL,
    original_charge_id   uuid,
    booked_amount        numeric(14,2) NOT NULL,
    adjusted_so_far      numeric(14,2) NOT NULL,
    expected_amount      numeric(14,2) NOT NULL,
    difference           numeric(14,2) NOT NULL,
    action               text          NOT NULL,
    reasons              text[]        NOT NULL DEFAULT '{}',
    run_id               uuid,
    reversal_charge_id   uuid,
    new_charge_id        uuid,
    adjustment_charge_id uuid,
    position             integer       NOT NULL,
    CONSTRAINT recalculation_line_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT recalculation_line_recalculation_fk FOREIGN KEY (tenant_id, recalculation_id)
        REFERENCES recalculation (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_charge_type_fk FOREIGN KEY (tenant_id, charge_type_id)
        REFERENCES charge_type (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_original_fk FOREIGN KEY (tenant_id, original_charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_reversal_fk FOREIGN KEY (tenant_id, reversal_charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_new_fk FOREIGN KEY (tenant_id, new_charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_adjustment_fk FOREIGN KEY (tenant_id, adjustment_charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT recalculation_line_action_ck CHECK (action IN ('REVERSE_AND_RECHARGE', 'ADJUST', 'NEW', 'REVERSE')),
    CONSTRAINT recalculation_line_period_ck CHECK (service_to >= service_from)
);

CREATE INDEX recalculation_line_tenant_idx ON recalculation_line (tenant_id);
CREATE INDEX recalculation_line_recalculation_idx ON recalculation_line (tenant_id, recalculation_id);
-- "Was this charge recalculated" (the history of one charge).
CREATE INDEX recalculation_line_original_idx ON recalculation_line (tenant_id, original_charge_id);

ALTER TABLE recalculation_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recalculation_line
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Append-only like the ledger (ledger_append_only, V13): nadel_app has no UPDATE, DELETE or TRUNCATE, and the trigger
-- also stops the owner except with app.ledger_maintenance (demo data and test helpers).
CREATE TRIGGER recalculation_append_only BEFORE UPDATE OR DELETE ON recalculation
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER recalculation_no_truncate BEFORE TRUNCATE ON recalculation
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER recalculation_line_append_only BEFORE UPDATE OR DELETE ON recalculation_line
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER recalculation_line_no_truncate BEFORE TRUNCATE ON recalculation_line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON recalculation, recalculation_line FROM nadel_app;

-- ---- charge: adjustments

-- adjusts_charge_id: the REGULAR charge an ADJUSTMENT corrects. recalculation_id: the recalculation that booked the row
-- (adjustments, and reversals made by a recalculation).
ALTER TABLE charge ADD COLUMN adjusts_charge_id uuid;
ALTER TABLE charge ADD COLUMN recalculation_id uuid;

ALTER TABLE charge ADD CONSTRAINT charge_adjusts_fk FOREIGN KEY (tenant_id, adjusts_charge_id)
    REFERENCES charge (tenant_id, id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE charge ADD CONSTRAINT charge_recalculation_fk FOREIGN KEY (tenant_id, recalculation_id)
    REFERENCES recalculation (tenant_id, id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE charge VALIDATE CONSTRAINT charge_adjusts_fk;
ALTER TABLE charge VALIDATE CONSTRAINT charge_recalculation_fk;

-- The adjustments of a charge (the effective amount) and the rows of a recalculation.
CREATE INDEX charge_adjusts_idx ON charge (tenant_id, adjusts_charge_id) WHERE adjusts_charge_id IS NOT NULL;
CREATE INDEX charge_recalculation_idx ON charge (tenant_id, recalculation_id) WHERE recalculation_id IS NOT NULL;

ALTER TABLE charge DROP CONSTRAINT charge_kind_ck;
ALTER TABLE charge ADD CONSTRAINT charge_kind_ck
    CHECK (kind IN ('REGULAR', 'REVERSAL', 'OPENING', 'ADJUSTMENT')) NOT VALID;
ALTER TABLE charge DROP CONSTRAINT charge_source_ck;
ALTER TABLE charge ADD CONSTRAINT charge_source_ck CHECK (source IN
    ('TARIFF', 'CONSUMPTION', 'LOSSES', 'COMMON_CONSUMER', 'OPENING', 'MANUAL_REVERSAL', 'RECALCULATION')) NOT VALID;

-- A reversal is negative, has a reason and points at its original; an adjustment is not zero (either sign); every other
-- row is positive and may carry an optional reason.
ALTER TABLE charge DROP CONSTRAINT charge_reversal_ck;
ALTER TABLE charge ADD CONSTRAINT charge_reversal_ck CHECK (
    (kind = 'REVERSAL') = (reverses_charge_id IS NOT NULL)
    AND (kind <> 'REVERSAL' OR reason IS NOT NULL)
    AND (CASE kind
             WHEN 'REVERSAL' THEN amount < 0
             WHEN 'ADJUSTMENT' THEN amount <> 0
             ELSE amount > 0
         END)) NOT VALID;

ALTER TABLE charge ADD CONSTRAINT charge_adjustment_ck CHECK (
    (kind = 'ADJUSTMENT') = (adjusts_charge_id IS NOT NULL)
    AND (kind <> 'ADJUSTMENT' OR (source = 'RECALCULATION' AND reason IS NOT NULL AND recalculation_id IS NOT NULL))
    AND (source <> 'RECALCULATION' OR (kind IN ('ADJUSTMENT', 'REVERSAL') AND recalculation_id IS NOT NULL))) NOT VALID;
ALTER TABLE charge ADD CONSTRAINT charge_not_self_adjustment_ck
    CHECK (adjusts_charge_id IS NULL OR adjusts_charge_id <> id) NOT VALID;

ALTER TABLE charge VALIDATE CONSTRAINT charge_kind_ck;
ALTER TABLE charge VALIDATE CONSTRAINT charge_source_ck;
ALTER TABLE charge VALIDATE CONSTRAINT charge_reversal_ck;
ALTER TABLE charge VALIDATE CONSTRAINT charge_adjustment_ck;
ALTER TABLE charge VALIDATE CONSTRAINT charge_not_self_adjustment_ck;

-- One adjustment of a charge per recalculation. SQLSTATE 23505.
CREATE UNIQUE INDEX charge_adjustment_once_uq ON charge (tenant_id, recalculation_id, adjusts_charge_id)
    WHERE kind = 'ADJUSTMENT';

-- What a row may point at (a CHECK cannot read another row). An ADJUSTMENT targets a REGULAR charge of its own
-- cooperative that is not reversed; a REVERSAL never targets an ADJUSTMENT or a REVERSAL. SQLSTATE 23514. Not SECURITY
-- DEFINER: the lookups run as the caller, under RLS, and also filter by the tenant of the new row.
CREATE FUNCTION charge_target_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    target_kind text;
BEGIN
    -- an ADJUSTMENT without a target is left to charge_adjustment_ck
    IF NEW.kind = 'ADJUSTMENT' AND NEW.adjusts_charge_id IS NOT NULL THEN
        SELECT kind INTO target_kind FROM charge WHERE tenant_id = NEW.tenant_id AND id = NEW.adjusts_charge_id;
        IF target_kind IS DISTINCT FROM 'REGULAR' THEN
            RAISE EXCEPTION 'charge_target_guard: an adjustment targets a regular charge of its cooperative'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'charge_target_guard';
        END IF;
        IF EXISTS (SELECT 1 FROM charge WHERE tenant_id = NEW.tenant_id AND reverses_charge_id = NEW.adjusts_charge_id) THEN
            RAISE EXCEPTION 'charge_target_guard: an adjustment does not target a reversed charge'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'charge_target_guard';
        END IF;
    ELSIF NEW.kind = 'REVERSAL' THEN
        SELECT kind INTO target_kind FROM charge WHERE tenant_id = NEW.tenant_id AND id = NEW.reverses_charge_id;
        IF target_kind IN ('ADJUSTMENT', 'REVERSAL') THEN
            RAISE EXCEPTION 'charge_target_guard: a reversal does not target an adjustment or a reversal'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'charge_target_guard';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER charge_target_guard BEFORE INSERT ON charge
    FOR EACH ROW EXECUTE FUNCTION charge_target_guard();

-- ---- tariff_version: soft delete

ALTER TABLE tariff_version ADD COLUMN deleted_at timestamptz;

-- A deleted version frees its start date. The name stays, the application maps it to TARIFF_VERSION_DATE_TAKEN.
ALTER TABLE tariff_version DROP CONSTRAINT tariff_version_valid_from_uq;
CREATE UNIQUE INDEX tariff_version_valid_from_uq ON tariff_version (tenant_id, tariff_id, valid_from)
    WHERE deleted_at IS NULL;

-- ---- RECALCULATION_RUN

-- V13 body plus ('CHAIRMAN', 'RECALCULATION_RUN'): Chairman gets all twenty, Treasurer and Member as in V13.
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives: the chairman role gets the permission by a targeted insert (idempotent), never by re-running
-- seed_base_roles (V13 explains why).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, 'RECALCULATION_RUN'
FROM role r
WHERE r.base_code = 'CHAIRMAN'
ON CONFLICT DO NOTHING;
