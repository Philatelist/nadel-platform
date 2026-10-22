-- Spec 011 (slice 1): the ledger core. billing_month (the accounting periods), charge (the append-only ledger of
-- accruals and their reversals), charge_part (the tariff versions behind one charge), meter_value_use (which meter
-- values a charge or run has read), the four CHARGES_* / PERIODS_CLOSE permissions and the COMMON_CONSUMERS charge type.
-- Compatibility with the previous release (expand/contract): new tables and rows the previous release never reads;
-- charge_type_code_ck only gains a value; seed_base_roles and seed_charge_types keep their signatures and only write more.
--
-- Every table has tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with
-- tenant_id and a UNIQUE (tenant_id, id) so that composite foreign keys (tenant_id, x_id) keep every link inside one
-- cooperative. Text columns with a fixed set of values use named CHECK constraints (no enum types). Amounts are
-- numeric(14,2); nothing is rounded in the database.

-- An accounting period. 'month' is its first day. OPEN accepts new charges; CLOSED does not (the closing rules and the
-- trigger that refuses CLOSED -> OPEN and DELETE arrive with period closing). closed_at is set exactly when CLOSED.
CREATE TABLE billing_month (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    month      date        NOT NULL,
    status     text        NOT NULL DEFAULT 'OPEN',
    run_at     timestamptz,
    closed_at  timestamptz,
    closed_by  uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT billing_month_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT billing_month_tenant_month_uq UNIQUE (tenant_id, month),
    CONSTRAINT billing_month_closed_by_fk FOREIGN KEY (tenant_id, closed_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT billing_month_first_day_ck CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT billing_month_status_ck CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT billing_month_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE INDEX billing_month_tenant_idx ON billing_month (tenant_id);

ALTER TABLE billing_month ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_month
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A month is updated (run_at, closing) but never deleted; TRUNCATE would bypass the row filter.
REVOKE DELETE, TRUNCATE ON billing_month FROM nadel_app;

-- The ledger of accruals. Append-only: a correction is a REVERSAL row (negative amount, reason, points at the charge it
-- reverses) plus a new charge. kind REGULAR is a normal accrual, OPENING an opening balance. 'source' says what produced
-- the row. service_from..service_to is the service period (inclusive), accrued_on the date the debt arises (the age of
-- the debt), billing_month_id the period the row is booked in. 'calculation' holds codes and numbers only, no display
-- text. source_key makes a run idempotent (see the partial unique index). run_id points at a later run table and has no
-- foreign key yet.
CREATE TABLE charge (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    plot_id            uuid          NOT NULL,
    charge_type_id     uuid          NOT NULL,
    billing_month_id   uuid          NOT NULL,
    kind               text          NOT NULL,
    source             text          NOT NULL,
    amount             numeric(14,2) NOT NULL,
    service_from       date          NOT NULL,
    service_to         date          NOT NULL,
    accrued_on         date          NOT NULL,
    calculation        jsonb,
    source_key         text,
    tariff_id          uuid,
    meter_id           uuid,
    run_id             uuid,
    reverses_charge_id uuid,
    reason             text,
    created_by         uuid,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT charge_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT charge_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_charge_type_fk FOREIGN KEY (tenant_id, charge_type_id)
        REFERENCES charge_type (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_billing_month_fk FOREIGN KEY (tenant_id, billing_month_id)
        REFERENCES billing_month (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_tariff_fk FOREIGN KEY (tenant_id, tariff_id)
        REFERENCES tariff (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_meter_fk FOREIGN KEY (tenant_id, meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_reverses_fk FOREIGN KEY (tenant_id, reverses_charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_kind_ck CHECK (kind IN ('REGULAR', 'REVERSAL', 'OPENING')),
    CONSTRAINT charge_source_ck CHECK (source IN
        ('TARIFF', 'CONSUMPTION', 'LOSSES', 'COMMON_CONSUMER', 'OPENING', 'MANUAL_REVERSAL')),
    -- A reversal is negative, has a reason and points at its original; every other row is positive and may carry an
    -- optional reason (an opening balance's reason or document reference).
    CONSTRAINT charge_reversal_ck CHECK (
        (kind = 'REVERSAL') = (reverses_charge_id IS NOT NULL)
        AND (kind <> 'REVERSAL' OR reason IS NOT NULL)
        AND ((kind = 'REVERSAL' AND amount < 0) OR (kind <> 'REVERSAL' AND amount > 0))),
    CONSTRAINT charge_opening_ck CHECK ((kind = 'OPENING') = (source = 'OPENING')),
    CONSTRAINT charge_service_period_ck CHECK (service_to >= service_from),
    CONSTRAINT charge_reason_ck CHECK (reason IS NULL OR (btrim(reason) <> '' AND char_length(reason) <= 500)),
    CONSTRAINT charge_not_self_reversal_ck CHECK (reverses_charge_id IS NULL OR reverses_charge_id <> id)
);

CREATE INDEX charge_tenant_idx ON charge (tenant_id);
-- Debt of a plot per charge type and its oldest accrual (debt calculation, plot status).
CREATE INDEX charge_plot_type_idx ON charge (tenant_id, plot_id, charge_type_id, accrued_on) INCLUDE (amount);
-- The month register and the closing checks.
CREATE INDEX charge_month_type_idx ON charge (tenant_id, billing_month_id, charge_type_id);
-- Which intervals of a meter are already charged (reading usage).
CREATE INDEX charge_meter_service_idx ON charge (tenant_id, meter_id, service_from, service_to);
-- The charges of a run (reversing a run).
CREATE INDEX charge_run_idx ON charge (tenant_id, run_id);
-- A charge is reversed at most once. SQLSTATE 23505.
CREATE UNIQUE INDEX charge_reversed_once_uq ON charge (tenant_id, reverses_charge_id)
    WHERE reverses_charge_id IS NOT NULL;
-- Idempotency of runs and listeners: one row per source key; a reversal has no key. SQLSTATE 23505.
CREATE UNIQUE INDEX charge_source_key_uq ON charge (tenant_id, source_key) WHERE kind <> 'REVERSAL';

ALTER TABLE charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charge
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The tariff versions behind a charge: for each version the days it applied and the quantity (units_num / units_den,
-- an exact fraction) priced at 'rate'; node_meter_id names the tree node of a losses share. Append-only like charge.
CREATE TABLE charge_part (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    charge_id         uuid          NOT NULL,
    tariff_version_id uuid          NOT NULL,
    days              integer       NOT NULL,
    units_num         numeric       NOT NULL,
    units_den         numeric       NOT NULL DEFAULT 1,
    rate              numeric(14,4) NOT NULL,
    node_meter_id     uuid,
    CONSTRAINT charge_part_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT charge_part_charge_fk FOREIGN KEY (tenant_id, charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_part_tariff_version_fk FOREIGN KEY (tenant_id, tariff_version_id)
        REFERENCES tariff_version (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_part_node_meter_fk FOREIGN KEY (tenant_id, node_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT charge_part_days_ck CHECK (days >= 0),
    CONSTRAINT charge_part_units_ck CHECK (units_num >= 0 AND units_den > 0),
    CONSTRAINT charge_part_rate_ck CHECK (rate >= 0)
);

CREATE INDEX charge_part_tenant_idx ON charge_part (tenant_id);
CREATE INDEX charge_part_charge_idx ON charge_part (tenant_id, charge_id);
-- "Is this tariff version used by a charge" (TariffVersionUsage).
CREATE INDEX charge_part_version_idx ON charge_part (tenant_id, tariff_version_id);

ALTER TABLE charge_part ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charge_part
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Append-only guard for the ledger tables. The privilege is revoked from nadel_app below; this trigger also stops the
-- owner. A change is allowed only to nadel_owner with SET LOCAL app.ledger_maintenance = 'on' (demo data and test
-- helpers); nadel_app is refused even with the setting. Not SECURITY DEFINER: current_user is the caller.
CREATE FUNCTION ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSIF TG_OP = 'UPDATE' THEN
            RETURN NEW;
        END IF;
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'ledger table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER charge_append_only BEFORE UPDATE OR DELETE ON charge
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER charge_no_truncate BEFORE TRUNCATE ON charge
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER charge_part_append_only BEFORE UPDATE OR DELETE ON charge_part
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER charge_part_no_truncate BEFORE TRUNCATE ON charge_part
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON charge, charge_part FROM nadel_app;

-- The meter values a charge or a run has read: one row per point date. Not a ledger table: the rows of a reversed
-- charge or run are deleted, which frees the readings again. Exactly one of charge_id and run_id is set; run_id points
-- at a later run table and has no foreign key yet.
CREATE TABLE meter_value_use (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    meter_id  uuid NOT NULL,
    used_on   date NOT NULL,
    charge_id uuid,
    run_id    uuid,
    CONSTRAINT meter_value_use_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT meter_value_use_meter_fk FOREIGN KEY (tenant_id, meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_value_use_charge_fk FOREIGN KEY (tenant_id, charge_id)
        REFERENCES charge (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_value_use_owner_ck CHECK ((charge_id IS NULL) <> (run_id IS NULL))
);

CREATE INDEX meter_value_use_tenant_idx ON meter_value_use (tenant_id);
CREATE INDEX meter_value_use_meter_idx ON meter_value_use (tenant_id, meter_id, used_on);

ALTER TABLE meter_value_use ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meter_value_use
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- DELETE stays granted (reversal); TRUNCATE revoked.
REVOKE TRUNCATE ON meter_value_use FROM nadel_app;

-- The COMMON_CONSUMERS charge type: a fifth seeded code.
ALTER TABLE charge_type DROP CONSTRAINT charge_type_code_ck;
ALTER TABLE charge_type ADD CONSTRAINT charge_type_code_ck CHECK (
    code IS NULL OR code IN ('MEMBERSHIP', 'ELECTRICITY', 'LAND_TAX', 'WATER', 'COMMON_CONSUMERS'));

-- V10 body plus COMMON_CONSUMERS (position 5, active). Deterministic ids, idempotent. Not SECURITY DEFINER.
CREATE OR REPLACE FUNCTION seed_charge_types(p_tenant uuid) RETURNS void
    LANGUAGE sql
AS $$
    INSERT INTO charge_type (id, tenant_id, code, name, position, active)
    SELECT md5(p_tenant::text || ':CHARGE_TYPE:' || t.code)::uuid, p_tenant, t.code, t.name, t.pos, t.is_active
    FROM (VALUES
        ('MEMBERSHIP', 'Membership fee', 1, true),
        ('ELECTRICITY', 'Electricity', 2, true),
        ('LAND_TAX', 'Land tax', 3, true),
        ('WATER', 'Water', 4, false),
        ('COMMON_CONSUMERS', 'Common consumers', 5, true)
    ) AS t(code, name, pos, is_active)
    ON CONFLICT DO NOTHING;
$$;

-- Base roles of a cooperative (V12 body) with the four new permissions: Chairman gets all nineteen (rows kept for
-- display), Treasurer MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW, TARIFFS_VIEW, METERS_VIEW, CHARGES_VIEW; Member none.
-- The role descriptions are unchanged. Not SECURITY DEFINER (see seed_charge_types in V10).
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
        ('CHAIRMAN', 'PERIODS_CLOSE'),
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives get the new permission rows by a targeted insert, never by re-running seed_base_roles (that
-- would restore a permission a chairman removed from a Treasurer role). Chairman four rows, Treasurer one (idempotent).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, g.permission
FROM role r
JOIN (VALUES
    ('CHAIRMAN', 'CHARGES_VIEW'), ('CHAIRMAN', 'CHARGES_RUN'), ('CHAIRMAN', 'CHARGES_REVERSE'),
    ('CHAIRMAN', 'PERIODS_CLOSE'), ('TREASURER', 'CHARGES_VIEW')
) AS g(code, permission) ON g.code = r.base_code
ON CONFLICT DO NOTHING;

-- Existing cooperatives get the fifth charge type (idempotent).
SELECT seed_charge_types(id) FROM tenant;
