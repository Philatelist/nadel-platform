-- Spec 011 (slice 7): common consumer runs and how each common consumer is shared among plots.
-- common_consumer_run is one run of "Спільні споживачі" over a date range (its charges are rows of charge with run_id
-- and source COMMON_CONSUMER); common_consumer_run_line holds the numbers of every common consumer the run looked at
-- (consumption, split, outcome, a warning and the plot shares, codes and numbers only). Like the losses runs (V17) a run
-- is never deleted: a mistake is the reversal of the whole run, which reverses its charges in the ledger and marks the
-- run and its lines REVERSED (status BOOKED -> REVERSED is the only change a row ever gets).
-- common_consumer_billing (the split of one consumer; no row: the cooperative's common_consumer_default_split) and
-- common_consumer_user (the plots marked as using a consumer, for the split SERVICE_USERS) are settings: the
-- application changes and deletes their rows.
-- Compatibility with the previous release (expand/contract): new tables the previous release never reads.
--
-- tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with tenant_id, a
-- UNIQUE (tenant_id, id) and composite foreign keys (tenant_id, x_id) as in V13. The quantities are kWh rounded to three
-- decimals for display (the amounts were calculated from exact quantities and rounded once, in the application).

CREATE TABLE common_consumer_run (
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
    CONSTRAINT common_consumer_run_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT common_consumer_run_billing_month_fk FOREIGN KEY (tenant_id, billing_month_id)
        REFERENCES billing_month (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT common_consumer_run_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT common_consumer_run_reversed_by_fk FOREIGN KEY (tenant_id, reversed_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT common_consumer_run_range_ck CHECK (to_date >= from_date),
    CONSTRAINT common_consumer_run_status_ck CHECK (status IN ('BOOKED', 'REVERSED')),
    -- a reversed run says when and why; a booked one has neither
    CONSTRAINT common_consumer_run_reversal_ck CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL)
        AND (status = 'REVERSED') = (reverse_reason IS NOT NULL)),
    CONSTRAINT common_consumer_run_reason_ck CHECK (
        reverse_reason IS NULL OR (btrim(reverse_reason) <> '' AND char_length(reverse_reason) <= 500)),
    CONSTRAINT common_consumer_run_total_ck CHECK (total >= 0)
);

CREATE INDEX common_consumer_run_tenant_idx ON common_consumer_run (tenant_id);
-- The history, newest first.
CREATE INDEX common_consumer_run_created_idx ON common_consumer_run (tenant_id, created_at);

ALTER TABLE common_consumer_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common_consumer_run
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A common consumer of a run. consumer_meter_id is the consumer's chain (its first meter; a replacement keeps the
-- consumer) and consumer_name the name of its meter as the run showed it (history, user content). It has no foreign key
-- to meter: a common consumer meter entered by mistake (no readings) may still be deleted (meters allows that) after a
-- run listed it as UNKNOWN, and the line keeps its name; a consumer a run charged always has readings and is never
-- deleted. from_date and to_date repeat the run's range and run_status its status (kept in step by the application), so
-- that the exclusion constraint can see them: two booked runs never charge the same consumer for the same day. A
-- consumer that was not charged (NOT_CHARGED, NO_PLOTS, UNKNOWN) blocks nothing, so it can be charged later by a run for
-- its range. plot_days is the sum of the days of the plots that share it.
CREATE TABLE common_consumer_run_line (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    run_id            uuid          NOT NULL,
    consumer_meter_id uuid          NOT NULL,
    consumer_name     text,
    from_date         date          NOT NULL,
    to_date           date          NOT NULL,
    run_status        text          NOT NULL DEFAULT 'BOOKED',
    position          integer       NOT NULL,
    split             text          NOT NULL,
    outcome           text          NOT NULL,
    consumption       numeric(14,3),
    calculated        boolean       NOT NULL DEFAULT false,
    plot_days         bigint        NOT NULL DEFAULT 0,
    amount            numeric(14,2) NOT NULL DEFAULT 0,
    warning           jsonb,
    plots             jsonb         NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT common_consumer_run_line_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT common_consumer_run_line_run_consumer_uq UNIQUE (tenant_id, run_id, consumer_meter_id),
    CONSTRAINT common_consumer_run_line_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES common_consumer_run (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT common_consumer_run_line_range_ck CHECK (to_date >= from_date),
    CONSTRAINT common_consumer_run_line_run_status_ck CHECK (run_status IN ('BOOKED', 'REVERSED')),
    CONSTRAINT common_consumer_run_line_split_ck CHECK (split IN
        ('CONNECTED_PLOTS', 'EQUAL_PER_PLOT', 'SERVICE_USERS', 'NOT_CHARGED')),
    CONSTRAINT common_consumer_run_line_outcome_ck CHECK (outcome IN ('CHARGED', 'NOT_CHARGED', 'NO_PLOTS', 'UNKNOWN')),
    -- a charged consumer has a known consumption and a split that charges; an unknown one has no consumption
    CONSTRAINT common_consumer_run_line_charged_ck CHECK (outcome <> 'CHARGED'
        OR (consumption IS NOT NULL AND split <> 'NOT_CHARGED')),
    CONSTRAINT common_consumer_run_line_unknown_ck CHECK (outcome <> 'UNKNOWN' OR consumption IS NULL),
    CONSTRAINT common_consumer_run_line_consumption_ck CHECK (consumption IS NULL OR consumption >= 0),
    CONSTRAINT common_consumer_run_line_plot_days_ck CHECK (plot_days >= 0),
    CONSTRAINT common_consumer_run_line_amount_ck CHECK (amount >= 0 AND (outcome = 'CHARGED' OR amount = 0)),
    -- Two booked runs never charge the same consumer on the same day (btree_gist from V8). SQLSTATE 23P01, mapped to
    -- COMMON_CONSUMER_RANGE_OVERLAP. A reversed run frees its days.
    CONSTRAINT common_consumer_run_line_no_overlap_ex EXCLUDE USING gist (
        tenant_id WITH =, consumer_meter_id WITH =, daterange(from_date, to_date, '[]') WITH &&)
        WHERE (run_status = 'BOOKED' AND outcome = 'CHARGED')
);

CREATE INDEX common_consumer_run_line_tenant_idx ON common_consumer_run_line (tenant_id);
-- The lines of a run (details, reversal).
CREATE INDEX common_consumer_run_line_run_idx ON common_consumer_run_line (tenant_id, run_id);
-- The booked runs of a consumer over a range (the overlap check before a booking).
CREATE INDEX common_consumer_run_line_consumer_idx
    ON common_consumer_run_line (tenant_id, consumer_meter_id, from_date);

ALTER TABLE common_consumer_run_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common_consumer_run_line
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The history of a run never changes except by its reversal (run_reversal_guard, V17): the run's status goes from
-- BOOKED to REVERSED with the reversal columns, a line's run_status with it; nothing else changes. DELETE and TRUNCATE
-- are refused (and revoked from nadel_app below); only nadel_owner with SET LOCAL app.ledger_maintenance = 'on' (demo
-- data and test helpers) gets past the trigger.
CREATE TRIGGER common_consumer_run_guard BEFORE UPDATE OR DELETE ON common_consumer_run
    FOR EACH ROW EXECUTE FUNCTION run_reversal_guard('status', 'reversed_at', 'reversed_by', 'reverse_reason');
CREATE TRIGGER common_consumer_run_no_truncate BEFORE TRUNCATE ON common_consumer_run
    FOR EACH STATEMENT EXECUTE FUNCTION run_reversal_guard('status');
CREATE TRIGGER common_consumer_run_line_guard BEFORE UPDATE OR DELETE ON common_consumer_run_line
    FOR EACH ROW EXECUTE FUNCTION run_reversal_guard('run_status');
CREATE TRIGGER common_consumer_run_line_no_truncate BEFORE TRUNCATE ON common_consumer_run_line
    FOR EACH STATEMENT EXECUTE FUNCTION run_reversal_guard('run_status');

REVOKE DELETE, TRUNCATE ON common_consumer_run, common_consumer_run_line FROM nadel_app;

-- The split of one common consumer meter (settings, changed by the board). No row: the cooperative's
-- common_consumer_default_split. The settings go with their meter (a common consumer meter is deleted only when
-- entered by mistake).
CREATE TABLE common_consumer_billing (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    consumer_meter_id uuid        NOT NULL,
    split             text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT common_consumer_billing_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT common_consumer_billing_consumer_uq UNIQUE (tenant_id, consumer_meter_id),
    CONSTRAINT common_consumer_billing_meter_fk FOREIGN KEY (tenant_id, consumer_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT common_consumer_billing_split_ck CHECK (split IN
        ('CONNECTED_PLOTS', 'EQUAL_PER_PLOT', 'SERVICE_USERS', 'NOT_CHARGED'))
);

CREATE INDEX common_consumer_billing_tenant_idx ON common_consumer_billing (tenant_id);

ALTER TABLE common_consumer_billing ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common_consumer_billing
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The plots marked as using a common consumer (the split SERVICE_USERS). A row goes with its meter and its plot.
CREATE TABLE common_consumer_user (
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    consumer_meter_id uuid        NOT NULL,
    plot_id           uuid        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT common_consumer_user_pk PRIMARY KEY (tenant_id, consumer_meter_id, plot_id),
    CONSTRAINT common_consumer_user_meter_fk FOREIGN KEY (tenant_id, consumer_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT common_consumer_user_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX common_consumer_user_tenant_idx ON common_consumer_user (tenant_id);
-- The consumers a plot uses (a plot that is deleted takes its rows with it).
CREATE INDEX common_consumer_user_plot_idx ON common_consumer_user (tenant_id, plot_id);

ALTER TABLE common_consumer_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON common_consumer_user
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- TRUNCATE would bypass the row filter.
REVOKE TRUNCATE ON common_consumer_billing, common_consumer_user FROM nadel_app;
