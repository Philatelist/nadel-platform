-- Spec 015 (slice 1): payments, allocation of payments to charges, cash receipt numbers. payment and allocation are
-- append-only ledger tables like charge (V13): a correction is a reversal row (payment) or a release row (allocation,
-- negative amount) plus new rows. receipt_counter hands out gap-free receipt numbers per cooperative.
-- Also: cooperative_settings.auto_allocate_prepayment, plot_status.prepayment and the permissions PAYMENTS_VIEW,
-- PAYMENTS_ENTER, PAYMENTS_REVERSE (role_permission.permission is plain text, so no DDL for them).
-- Compatibility with the previous release (expand/contract): only new tables, new columns with defaults that the
-- previous release never reads or writes (its settings upsert names its own columns; plot_status keeps working with
-- the default prepayment 0), one more unique constraint on charge, and permission rows the previous release ignores.

SET LOCAL lock_timeout = '10s';

-- ---- charge: target of the same-plot composite key

-- Lets allocation (tenant_id, charge_id, plot_id) prove that an allocation and its charge belong to one plot.
ALTER TABLE charge ADD CONSTRAINT charge_tenant_id_plot_uq UNIQUE (tenant_id, id, plot_id);

-- ---- payment

-- Money received for one plot. kind PAYMENT is an incoming payment (amount > 0); PAYMENT_REVERSAL cancels one payment
-- of the same plot with the negated amount and a reason. source CASH carries a receipt number, BANK may carry a bank
-- reference (feature 016 widens the source CHECK). group_id ties the parts of one payment split over several plots of
-- one payer (a single payment is its own group); every part of a cash group carries the group's receipt number.
-- payer_note_enc is the encrypted payer note (personal data, ADR 0016), payments only. billing_month_id is the period
-- the row is booked in.
CREATE TABLE payment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    plot_id             uuid          NOT NULL,
    kind                text          NOT NULL,
    source              text          NOT NULL,
    amount              numeric(14,2) NOT NULL,
    received_on         date          NOT NULL,
    billing_month_id    uuid          NOT NULL,
    group_id            uuid          NOT NULL,
    receipt_number      bigint,
    bank_reference      text,
    payer_member_id     uuid,
    payer_note_enc      bytea,
    reverses_payment_id uuid,
    reason              text,
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT payment_tenant_id_uq UNIQUE (tenant_id, id),
    -- Target of the composite keys of allocation: an allocation and its payment belong to one plot.
    CONSTRAINT payment_tenant_id_plot_uq UNIQUE (tenant_id, id, plot_id),
    CONSTRAINT payment_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT payment_billing_month_fk FOREIGN KEY (tenant_id, billing_month_id)
        REFERENCES billing_month (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT payment_payer_member_fk FOREIGN KEY (tenant_id, payer_member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT payment_reverses_fk FOREIGN KEY (tenant_id, reverses_payment_id)
        REFERENCES payment (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT payment_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT payment_kind_ck CHECK (kind IN ('PAYMENT', 'PAYMENT_REVERSAL')),
    CONSTRAINT payment_source_ck CHECK (source IN ('CASH', 'BANK')),
    -- A payment is positive, a reversal negative and points at its original.
    CONSTRAINT payment_amount_ck CHECK (
        (kind = 'PAYMENT' AND amount > 0) OR (kind = 'PAYMENT_REVERSAL' AND amount < 0)),
    CONSTRAINT payment_reversal_ck CHECK ((kind = 'PAYMENT_REVERSAL') = (reverses_payment_id IS NOT NULL)),
    CONSTRAINT payment_not_self_reversal_ck CHECK (reverses_payment_id IS NULL OR reverses_payment_id <> id),
    -- The reason is required on a reversal (1-500 characters) and absent on a payment.
    CONSTRAINT payment_reason_ck CHECK (
        (kind = 'PAYMENT_REVERSAL' AND reason IS NOT NULL AND btrim(reason) <> '' AND char_length(reason) <= 500)
        OR (kind = 'PAYMENT' AND reason IS NULL)),
    -- Exactly the cash payments carry a receipt number.
    CONSTRAINT payment_receipt_ck CHECK (
        (kind = 'PAYMENT' AND source = 'CASH') = (receipt_number IS NOT NULL)
        AND (receipt_number IS NULL OR receipt_number > 0)),
    CONSTRAINT payment_bank_reference_ck CHECK (
        bank_reference IS NULL
        OR (kind = 'PAYMENT' AND source = 'BANK' AND btrim(bank_reference) <> '' AND char_length(bank_reference) <= 64)),
    -- Ciphertext of at most 200 characters plus the framing of the application (nonce, tag).
    CONSTRAINT payment_payer_note_ck CHECK (
        payer_note_enc IS NULL OR (kind = 'PAYMENT' AND octet_length(payer_note_enc) BETWEEN 1 AND 2048))
);

CREATE INDEX payment_tenant_idx ON payment (tenant_id);
-- Payments of a plot by date (account, debt calculation).
CREATE INDEX payment_plot_received_idx ON payment (tenant_id, plot_id, received_on) INCLUDE (amount, kind);
CREATE INDEX payment_month_idx ON payment (tenant_id, billing_month_id);
-- The list, newest first.
CREATE INDEX payment_received_idx ON payment (tenant_id, received_on DESC, id);
CREATE INDEX payment_group_idx ON payment (tenant_id, group_id);
-- A payment is reversed at most once. SQLSTATE 23505.
CREATE UNIQUE INDEX payment_reversed_once_uq ON payment (tenant_id, reverses_payment_id)
    WHERE reverses_payment_id IS NOT NULL;
-- A receipt number belongs to one group: the parts of a cash group share it, another group never does
-- (btree_gist from V8). SQLSTATE 23P01.
ALTER TABLE payment ADD CONSTRAINT payment_receipt_group_excl
    EXCLUDE USING gist (tenant_id WITH =, receipt_number WITH =, group_id WITH <>)
    WHERE (receipt_number IS NOT NULL);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- What a reversal may point at (a CHECK cannot read another row): a PAYMENT of the same plot with the negated amount.
-- SQLSTATE 23514. A target that is not visible (missing or of another cooperative) is left to payment_reverses_fk. Not
-- SECURITY DEFINER: the lookup runs as the caller, under RLS, and also filters by the tenant of the new row.
CREATE FUNCTION payment_target_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    target payment%ROWTYPE;
BEGIN
    SELECT * INTO target FROM payment WHERE tenant_id = NEW.tenant_id AND id = NEW.reverses_payment_id;
    IF FOUND AND (target.kind <> 'PAYMENT' OR target.plot_id <> NEW.plot_id OR target.amount <> -NEW.amount) THEN
        RAISE EXCEPTION 'payment_target_guard: a reversal targets a payment of the same plot with the negated amount'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'payment_target_guard';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_target_guard BEFORE INSERT ON payment
    FOR EACH ROW WHEN (NEW.kind = 'PAYMENT_REVERSAL')
    EXECUTE FUNCTION payment_target_guard();

CREATE TRIGGER payment_append_only BEFORE UPDATE OR DELETE ON payment
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER payment_no_truncate BEFORE TRUNCATE ON payment
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON payment FROM nadel_app;

-- ---- allocation

-- A part of a payment placed on a charge. A positive row is an allocation (origin says who placed it: ENTRY at payment
-- entry, REALLOCATION when a payment was re-placed, PREPAYMENT_MANUAL / PREPAYMENT_AUTO for prepayment); a negative row
-- is a release of (part of) one positive row (release_cause says why). created_by null means the system. The open
-- amount of a charge is its effective amount minus the net (positive + negative) allocations to it.
CREATE TABLE allocation (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid          NOT NULL REFERENCES tenant (id),
    payment_id             uuid          NOT NULL,
    charge_id              uuid          NOT NULL,
    plot_id                uuid          NOT NULL,
    amount                 numeric(14,2) NOT NULL,
    releases_allocation_id uuid,
    origin                 text,
    release_cause          text,
    created_by             uuid,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT allocation_tenant_id_uq UNIQUE (tenant_id, id),
    -- Target of the release key: a release keeps the payment and the charge of the row it releases.
    CONSTRAINT allocation_release_target_uq UNIQUE (tenant_id, id, payment_id, charge_id),
    -- "Same plot and cooperative" is structural: the payment and the charge both belong to allocation.plot_id.
    CONSTRAINT allocation_payment_fk FOREIGN KEY (tenant_id, payment_id, plot_id)
        REFERENCES payment (tenant_id, id, plot_id) ON DELETE RESTRICT,
    CONSTRAINT allocation_charge_fk FOREIGN KEY (tenant_id, charge_id, plot_id)
        REFERENCES charge (tenant_id, id, plot_id) ON DELETE RESTRICT,
    CONSTRAINT allocation_releases_fk FOREIGN KEY (tenant_id, releases_allocation_id, payment_id, charge_id)
        REFERENCES allocation (tenant_id, id, payment_id, charge_id) ON DELETE RESTRICT,
    CONSTRAINT allocation_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT allocation_amount_ck CHECK (amount <> 0),
    CONSTRAINT allocation_release_ck CHECK ((amount < 0) = (releases_allocation_id IS NOT NULL)),
    -- origin on positive rows only, release_cause on negative rows only.
    CONSTRAINT allocation_origin_ck CHECK (
        (amount > 0) = (origin IS NOT NULL)
        AND (origin IS NULL OR origin IN ('ENTRY', 'REALLOCATION', 'PREPAYMENT_MANUAL', 'PREPAYMENT_AUTO'))),
    CONSTRAINT allocation_release_cause_ck CHECK (
        (amount < 0) = (release_cause IS NOT NULL)
        AND (release_cause IS NULL
             OR release_cause IN ('CHARGE_REVERSED', 'CHARGE_ADJUSTED', 'PAYMENT_REVERSED', 'REALLOCATED')))
);

CREATE INDEX allocation_tenant_idx ON allocation (tenant_id);
-- Net allocations of a charge (open amount) and of a payment (what is left of it).
CREATE INDEX allocation_charge_idx ON allocation (tenant_id, charge_id) INCLUDE (amount);
CREATE INDEX allocation_payment_idx ON allocation (tenant_id, payment_id) INCLUDE (amount);
CREATE INDEX allocation_plot_idx ON allocation (tenant_id, plot_id) INCLUDE (amount);
-- What is left of a positive row.
CREATE INDEX allocation_releases_idx ON allocation (tenant_id, releases_allocation_id)
    WHERE releases_allocation_id IS NOT NULL;

ALTER TABLE allocation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON allocation
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- What a row may point at (a CHECK cannot read another row). SQLSTATE 23514. A positive row targets a REGULAR or
-- OPENING charge that is not reversed and a PAYMENT that is not reversed. A release targets a positive allocation and
-- never exceeds what is left of it (the row plus its earlier releases). A target that is not visible (missing or of
-- another cooperative) is left to the foreign keys. Not SECURITY DEFINER: lookups run as the caller, under RLS, and
-- filter by the tenant of the new row.
CREATE FUNCTION allocation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    target_kind text;
    target_amount numeric(14,2);
    left_amount numeric(14,2);
BEGIN
    IF NEW.amount > 0 THEN
        SELECT kind INTO target_kind FROM charge WHERE tenant_id = NEW.tenant_id AND id = NEW.charge_id;
        IF FOUND THEN
            IF target_kind NOT IN ('REGULAR', 'OPENING') THEN
                RAISE EXCEPTION 'allocation_guard: an allocation targets a regular or opening charge'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
            IF EXISTS (SELECT 1 FROM charge WHERE tenant_id = NEW.tenant_id AND reverses_charge_id = NEW.charge_id) THEN
                RAISE EXCEPTION 'allocation_guard: an allocation does not target a reversed charge'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
        END IF;
        SELECT kind INTO target_kind FROM payment WHERE tenant_id = NEW.tenant_id AND id = NEW.payment_id;
        IF FOUND THEN
            IF target_kind <> 'PAYMENT' THEN
                RAISE EXCEPTION 'allocation_guard: an allocation targets a payment, not a payment reversal'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
            IF EXISTS (SELECT 1 FROM payment WHERE tenant_id = NEW.tenant_id AND reverses_payment_id = NEW.payment_id) THEN
                RAISE EXCEPTION 'allocation_guard: an allocation does not target a reversed payment'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
        END IF;
    ELSIF NEW.releases_allocation_id IS NOT NULL THEN
        SELECT amount INTO target_amount FROM allocation
        WHERE tenant_id = NEW.tenant_id AND id = NEW.releases_allocation_id;
        IF FOUND THEN
            IF target_amount <= 0 THEN
                RAISE EXCEPTION 'allocation_guard: a release targets a positive allocation'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
            SELECT target_amount + COALESCE(SUM(amount), 0) INTO left_amount FROM allocation
            WHERE tenant_id = NEW.tenant_id AND releases_allocation_id = NEW.releases_allocation_id;
            IF left_amount + NEW.amount < 0 THEN
                RAISE EXCEPTION 'allocation_guard: a release does not exceed what is left of its allocation'
                    USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_guard';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER allocation_guard BEFORE INSERT ON allocation
    FOR EACH ROW EXECUTE FUNCTION allocation_guard();

CREATE TRIGGER allocation_append_only BEFORE UPDATE OR DELETE ON allocation
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER allocation_no_truncate BEFORE TRUNCATE ON allocation
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON allocation FROM nadel_app;

-- ---- deferred backstops

-- Checked at commit, so a booking path may write its rows in any order inside one transaction. A path that reverses or
-- lowers a charge, or reverses a payment, and forgets to release the allocations fails here instead of leaving a
-- negative open amount. Not SECURITY DEFINER: the sums run as the caller, under RLS, and filter by tenant. They are
-- not a replacement for the service checks, which run first under the plot lock.

-- The net allocations to a charge do not exceed its effective amount (amount plus adjustments, 0 when reversed).
CREATE FUNCTION ledger_check_charge_cap(p_tenant uuid, p_charge uuid) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    effective numeric(14,2);
    net numeric(14,2);
BEGIN
    SELECT CASE
               WHEN EXISTS (SELECT 1 FROM charge r WHERE r.tenant_id = c.tenant_id AND r.reverses_charge_id = c.id) THEN 0
               ELSE c.amount + COALESCE((SELECT SUM(a.amount) FROM charge a
                                         WHERE a.tenant_id = c.tenant_id AND a.adjusts_charge_id = c.id), 0)
           END
    INTO effective
    FROM charge c WHERE c.tenant_id = p_tenant AND c.id = p_charge;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(amount), 0) INTO net FROM allocation WHERE tenant_id = p_tenant AND charge_id = p_charge;
    IF net > effective THEN
        RAISE EXCEPTION 'allocation_charge_cap: allocations to a charge exceed its effective amount'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_charge_cap';
    END IF;
END;
$$;

-- The net allocations of a payment do not exceed its amount (0 when reversed).
CREATE FUNCTION ledger_check_payment_cap(p_tenant uuid, p_payment uuid) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    effective numeric(14,2);
    net numeric(14,2);
BEGIN
    SELECT CASE
               WHEN p.kind <> 'PAYMENT' OR EXISTS (SELECT 1 FROM payment r
                                                   WHERE r.tenant_id = p.tenant_id AND r.reverses_payment_id = p.id) THEN 0
               ELSE p.amount
           END
    INTO effective
    FROM payment p WHERE p.tenant_id = p_tenant AND p.id = p_payment;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(amount), 0) INTO net FROM allocation WHERE tenant_id = p_tenant AND payment_id = p_payment;
    IF net > effective THEN
        RAISE EXCEPTION 'allocation_payment_cap: allocations of a payment exceed its amount'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'allocation_payment_cap';
    END IF;
END;
$$;

CREATE FUNCTION allocation_backstop() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM ledger_check_payment_cap(NEW.tenant_id, NEW.payment_id);
    PERFORM ledger_check_charge_cap(NEW.tenant_id, NEW.charge_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER allocation_backstop AFTER INSERT ON allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION allocation_backstop();

-- A reversal or an adjustment lowers the effective amount of its target: the allocations must already fit.
CREATE FUNCTION charge_allocation_backstop() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM ledger_check_charge_cap(NEW.tenant_id, COALESCE(NEW.reverses_charge_id, NEW.adjusts_charge_id));
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER charge_allocation_backstop AFTER INSERT ON charge
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kind IN ('REVERSAL', 'ADJUSTMENT'))
    EXECUTE FUNCTION charge_allocation_backstop();

-- A reversed payment is fully released: its net allocations are 0 (they never go below 0, a release is capped by its
-- allocation), which is the same as "not above the effective amount 0".
CREATE FUNCTION payment_allocation_backstop() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM ledger_check_payment_cap(NEW.tenant_id, NEW.reverses_payment_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER payment_allocation_backstop AFTER INSERT ON payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kind = 'PAYMENT_REVERSAL')
    EXECUTE FUNCTION payment_allocation_backstop();

-- ---- receipt_counter

-- The last cash receipt number of a cooperative. The service inserts (tenant, 0) if missing and then adds 1 in the
-- saving transaction (a rollback leaves no gap; a concurrent save waits on the row lock).
CREATE TABLE receipt_counter (
    tenant_id   uuid   PRIMARY KEY REFERENCES tenant (id),
    last_number bigint NOT NULL DEFAULT 0,
    CONSTRAINT receipt_counter_last_number_ck CHECK (last_number >= 0)
);

ALTER TABLE receipt_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_counter
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The only change allowed is +1: no skipped numbers, no going back. SQLSTATE 23514.
CREATE FUNCTION receipt_counter_step() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.last_number <> OLD.last_number + 1 THEN
        RAISE EXCEPTION 'receipt_counter_step: the receipt counter only moves forward by one'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'receipt_counter_step';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER receipt_counter_step BEFORE UPDATE ON receipt_counter
    FOR EACH ROW EXECUTE FUNCTION receipt_counter_step();

-- SELECT, INSERT and UPDATE stay granted; the counter is never deleted.
REVOKE DELETE, TRUNCATE ON receipt_counter FROM nadel_app;

-- ---- settings and status cache

-- Whether prepayment is placed on new charges automatically. Default on.
ALTER TABLE cooperative_settings ADD COLUMN auto_allocate_prepayment boolean NOT NULL DEFAULT true;

-- Money paid in advance (payments minus allocations); a cache rebuilt from the ledger like the rest of plot_status.
ALTER TABLE plot_status ADD COLUMN prepayment numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE plot_status ADD CONSTRAINT plot_status_prepayment_ck CHECK (prepayment >= 0);

-- ---- PAYMENTS_VIEW, PAYMENTS_ENTER, PAYMENTS_REVERSE

-- V20 body plus (CHAIRMAN, PAYMENTS_VIEW), (CHAIRMAN, PAYMENTS_ENTER), (CHAIRMAN, PAYMENTS_REVERSE),
-- (TREASURER, PAYMENTS_VIEW), (TREASURER, PAYMENTS_ENTER). Member and custom roles get nothing.
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW'), ('TREASURER', 'CHARGES_VIEW'),
        ('TREASURER', 'REPORTS_VIEW'),
        ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER')
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
    ('CHAIRMAN', 'PAYMENTS_VIEW'), ('CHAIRMAN', 'PAYMENTS_ENTER'), ('CHAIRMAN', 'PAYMENTS_REVERSE'),
    ('TREASURER', 'PAYMENTS_VIEW'), ('TREASURER', 'PAYMENTS_ENTER')
) AS p(base_code, permission) ON p.base_code = r.base_code
ON CONFLICT DO NOTHING;
