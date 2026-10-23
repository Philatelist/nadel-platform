-- Spec 011 (slice 6): proportional losses runs. losses_run is one run of "Розрахунок втрат" over a date range (its
-- charges are rows of charge with run_id and source LOSSES); losses_run_node holds the numbers of every node of the
-- meter tree the run looked at (consumption, children, losses, the outcome, a warning and the plot shares, codes and
-- numbers only). A run is never deleted: a mistake is the reversal of the whole run, which reverses its charges in the
-- ledger and marks the run and its nodes REVERSED (status BOOKED -> REVERSED is the only change a row ever gets).
-- Compatibility with the previous release (expand/contract): new tables the previous release never reads, and one more
-- index on meter_value_use.
--
-- tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with tenant_id, a
-- UNIQUE (tenant_id, id) and composite foreign keys (tenant_id, x_id) as in V13. The quantities are kWh rounded to three
-- decimals for display (the amounts were calculated from exact quantities and rounded once, in the application).

CREATE TABLE losses_run (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    from_date        date          NOT NULL,
    to_date          date          NOT NULL,
    status           text          NOT NULL DEFAULT 'BOOKED',
    billing_month_id uuid          NOT NULL,
    total            numeric(14,2) NOT NULL,
    created_by       uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    reversed_at      timestamptz,
    reversed_by      uuid,
    reverse_reason   text,
    CONSTRAINT losses_run_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT losses_run_billing_month_fk FOREIGN KEY (tenant_id, billing_month_id)
        REFERENCES billing_month (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT losses_run_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT losses_run_reversed_by_fk FOREIGN KEY (tenant_id, reversed_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT losses_run_range_ck CHECK (to_date >= from_date),
    CONSTRAINT losses_run_status_ck CHECK (status IN ('BOOKED', 'REVERSED')),
    -- a reversed run says when and why; a booked one has neither
    CONSTRAINT losses_run_reversal_ck CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL)
        AND (status = 'REVERSED') = (reverse_reason IS NOT NULL)),
    CONSTRAINT losses_run_reason_ck CHECK (
        reverse_reason IS NULL OR (btrim(reverse_reason) <> '' AND char_length(reverse_reason) <= 500)),
    CONSTRAINT losses_run_total_ck CHECK (total >= 0)
);

CREATE INDEX losses_run_tenant_idx ON losses_run (tenant_id);
-- The history, newest first.
CREATE INDEX losses_run_created_idx ON losses_run (tenant_id, created_at);

ALTER TABLE losses_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON losses_run
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A node of a run. node_meter_id is the node's chain (its first meter; a replacement keeps the node), node_kind and
-- node_name the kind and the name of its meter as the run showed it (history, user content). from_date and
-- to_date repeat the run's range and run_status its status (kept in step by the application), so that the exclusion
-- constraint can see them: two booked runs never charge the same node for the same day. A node that was not charged
-- (NEGATIVE, UNKNOWN, SKIPPED_ABOVE) blocks nothing, so it can be charged later by a run for its range.
CREATE TABLE losses_run_node (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    run_id        uuid          NOT NULL,
    node_meter_id uuid          NOT NULL,
    node_kind     text          NOT NULL,
    node_name     text,
    from_date     date          NOT NULL,
    to_date       date          NOT NULL,
    run_status    text          NOT NULL DEFAULT 'BOOKED',
    position      integer       NOT NULL,
    outcome       text          NOT NULL,
    consumption   numeric(14,3),
    children      numeric(14,3),
    losses        numeric(15,3),
    pct           numeric,
    calculated    boolean       NOT NULL DEFAULT false,
    amount        numeric(14,2) NOT NULL DEFAULT 0,
    warning       jsonb,
    plots         jsonb         NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT losses_run_node_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT losses_run_node_run_node_uq UNIQUE (tenant_id, run_id, node_meter_id),
    CONSTRAINT losses_run_node_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES losses_run (tenant_id, id) ON DELETE RESTRICT,
    -- a node always had meters under it, so the meter is never deleted (meters refuses to delete a former parent)
    CONSTRAINT losses_run_node_meter_fk FOREIGN KEY (tenant_id, node_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT losses_run_node_kind_ck CHECK (node_kind IN ('SUBSTATION', 'QUARTER')),
    CONSTRAINT losses_run_node_range_ck CHECK (to_date >= from_date),
    CONSTRAINT losses_run_node_run_status_ck CHECK (run_status IN ('BOOKED', 'REVERSED')),
    CONSTRAINT losses_run_node_outcome_ck CHECK (outcome IN ('CHARGED', 'NEGATIVE', 'UNKNOWN', 'SKIPPED_ABOVE')),
    -- a charged node has known numbers and losses of at least zero; negative losses are never charged
    CONSTRAINT losses_run_node_charged_ck CHECK (outcome <> 'CHARGED'
        OR (consumption IS NOT NULL AND children IS NOT NULL AND losses >= 0)),
    CONSTRAINT losses_run_node_negative_ck CHECK (outcome <> 'NEGATIVE' OR losses < 0),
    CONSTRAINT losses_run_node_amount_ck CHECK (amount >= 0 AND (outcome = 'CHARGED' OR amount = 0)),
    -- Two booked runs never charge the same node on the same day (btree_gist from V8). SQLSTATE 23P01, mapped to
    -- LOSSES_RANGE_OVERLAP. A reversed run frees its days.
    CONSTRAINT losses_run_node_no_overlap_ex EXCLUDE USING gist (
        tenant_id WITH =, node_meter_id WITH =, daterange(from_date, to_date, '[]') WITH &&)
        WHERE (run_status = 'BOOKED' AND outcome = 'CHARGED')
);

CREATE INDEX losses_run_node_tenant_idx ON losses_run_node (tenant_id);
-- The nodes of a run (details, reversal).
CREATE INDEX losses_run_node_run_idx ON losses_run_node (tenant_id, run_id);
-- The booked runs of a node over a range (the overlap check before a booking, the open items of a node).
CREATE INDEX losses_run_node_meter_idx ON losses_run_node (tenant_id, node_meter_id, from_date);

ALTER TABLE losses_run_node ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON losses_run_node
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The history of a run never changes except by its reversal: the status column named by the first trigger argument
-- goes from BOOKED to REVERSED, and only the columns named by the other arguments change with it. DELETE and TRUNCATE
-- are refused (and revoked from nadel_app below). The only way past the trigger is nadel_owner with SET LOCAL
-- app.ledger_maintenance = 'on' (demo data and test helpers), as for the ledger tables; nadel_app is refused even with
-- the setting. Generic, so the common consumer runs (slice 7) use it too. Not SECURITY DEFINER: current_user is the
-- caller.
CREATE FUNCTION run_reversal_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    status_column text := TG_ARGV[0];
    changing      text[] := TG_ARGV::text[];
BEGIN
    IF current_user = 'nadel_owner' AND current_setting('app.ledger_maintenance', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSIF TG_OP = 'UPDATE' THEN
            RETURN NEW;
        END IF;
        RETURN NULL;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF to_jsonb(OLD) ->> status_column = 'BOOKED' AND to_jsonb(NEW) ->> status_column = 'REVERSED'
                AND (to_jsonb(NEW) - changing) = (to_jsonb(OLD) - changing) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a row of % changes only by the reversal of its run', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RAISE EXCEPTION 'the runs in % are history: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER losses_run_guard BEFORE UPDATE OR DELETE ON losses_run
    FOR EACH ROW EXECUTE FUNCTION run_reversal_guard('status', 'reversed_at', 'reversed_by', 'reverse_reason');
CREATE TRIGGER losses_run_no_truncate BEFORE TRUNCATE ON losses_run
    FOR EACH STATEMENT EXECUTE FUNCTION run_reversal_guard('status');
CREATE TRIGGER losses_run_node_guard BEFORE UPDATE OR DELETE ON losses_run_node
    FOR EACH ROW EXECUTE FUNCTION run_reversal_guard('run_status');
CREATE TRIGGER losses_run_node_no_truncate BEFORE TRUNCATE ON losses_run_node
    FOR EACH STATEMENT EXECUTE FUNCTION run_reversal_guard('run_status');

REVOKE DELETE, TRUNCATE ON losses_run, losses_run_node FROM nadel_app;

-- The meter values a run read (rows with run_id, V13): the reading usage of a meter asks for the booked runs.
CREATE INDEX meter_value_use_run_idx ON meter_value_use (tenant_id, run_id) WHERE run_id IS NOT NULL;
