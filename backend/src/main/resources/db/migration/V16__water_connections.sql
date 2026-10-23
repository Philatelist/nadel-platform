-- Spec 011 (slice 5): water connections of plots (module meters). A plot is connected to a water source from a date
-- and, once disconnected, until a date (both days of the connection; valid_to NULL while it lasts). The source is a
-- common consumer meter (a well pump) or, with source_meter_id NULL, the cooperative's water supply. The history is
-- kept: a disconnection sets valid_to, a later connection is a new row. Water tariffs of any basis charge a plot only
-- for its connected days (the ledger asks meters.WaterConnections).
-- Compatibility with the previous release (expand/contract): a new table the previous release never reads.
--
-- tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with tenant_id, a
-- UNIQUE (tenant_id, id) and composite foreign keys (tenant_id, x_id) as in V12. That the source is a common consumer
-- meter that is not removed spans tables and is checked by the service.
CREATE TABLE plot_water_connection (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    plot_id         uuid        NOT NULL,
    source_meter_id uuid,
    valid_from      date        NOT NULL,
    valid_to        date,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT plot_water_connection_tenant_id_uq UNIQUE (tenant_id, id),
    -- the connections of a plot go with it (a plot is deleted only when nothing else refers to it)
    CONSTRAINT plot_water_connection_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE CASCADE,
    -- a well that plots were ever connected to is not deleted (the service answers 409 first)
    CONSTRAINT plot_water_connection_source_fk FOREIGN KEY (tenant_id, source_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT plot_water_connection_period_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- A plot is connected once on any day: its connections never overlap (btree_gist from V8). SQLSTATE 23P01,
    -- mapped to WATER_ALREADY_CONNECTED.
    CONSTRAINT plot_water_connection_no_overlap_ex EXCLUDE USING gist (
        tenant_id WITH =, plot_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
);

CREATE INDEX plot_water_connection_tenant_idx ON plot_water_connection (tenant_id);
-- The history of a plot and the connected days of many plots over a range (the month run, reading charges).
CREATE INDEX plot_water_connection_plot_idx ON plot_water_connection (tenant_id, plot_id, valid_from);
-- The plots of a well (the water sources section, the common consumer split of a well pump).
CREATE INDEX plot_water_connection_source_idx ON plot_water_connection (tenant_id, source_meter_id)
    WHERE source_meter_id IS NOT NULL;
-- A plot has at most one active connection. SQLSTATE 23505, mapped to WATER_ALREADY_CONNECTED.
CREATE UNIQUE INDEX plot_water_connection_active_uq ON plot_water_connection (tenant_id, plot_id)
    WHERE valid_to IS NULL;

ALTER TABLE plot_water_connection ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plot_water_connection
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The history is kept: a disconnection is an update of valid_to, never a delete. TRUNCATE would bypass the row filter.
REVOKE DELETE, TRUNCATE ON plot_water_connection FROM nadel_app;
