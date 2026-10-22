-- Spec 011 (slice 3): the "not charged" queue. One row per case the app could not charge (two tariffs of a charge type
-- for one plot in a month now; reading intervals, losses nodes and common consumers in later slices), with the reason
-- and codes or ids only in params (never display text). Not a ledger table: the application changes the status of an
-- item (OPEN -> RESOLVED when a retry or a later run charged it, OPEN -> OBSOLETE when nothing is left to charge) and
-- its params while it is open. At most one OPEN item per key; a closed item stays as history.
-- Compatibility with the previous release (expand/contract): a new table the previous release never reads.
--
-- tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with tenant_id, a
-- UNIQUE (tenant_id, id) and composite foreign keys (tenant_id, x_id) as in V13.
CREATE TABLE not_charged_item (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    item_key       text        NOT NULL,
    kind           text        NOT NULL,
    plot_id        uuid,
    charge_type_id uuid,
    meter_id       uuid,
    month          date,
    from_date      date,
    to_date        date,
    reason         text        NOT NULL,
    params         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status         text        NOT NULL DEFAULT 'OPEN',
    resolved_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT not_charged_item_tenant_id_uq UNIQUE (tenant_id, id),
    -- an item about a plot or a meter that is deleted means nothing any more
    CONSTRAINT not_charged_item_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT not_charged_item_charge_type_fk FOREIGN KEY (tenant_id, charge_type_id)
        REFERENCES charge_type (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT not_charged_item_meter_fk FOREIGN KEY (tenant_id, meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT not_charged_item_kind_ck CHECK (kind IN ('TARIFF_MONTH', 'INTERVAL', 'LOSSES_NODE', 'COMMON_CONSUMER')),
    CONSTRAINT not_charged_item_reason_ck CHECK (reason IN ('NO_TARIFF_FOR_ALL_DAYS', 'CONFLICTING_TARIFFS',
        'OUTSIDE_SCOPE', 'EXCLUDED', 'UNKNOWN_NODE_VALUE', 'UNKNOWN_CONSUMER_VALUE')),
    CONSTRAINT not_charged_item_status_ck CHECK (status IN ('OPEN', 'RESOLVED', 'OBSOLETE')),
    CONSTRAINT not_charged_item_resolved_ck CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CONSTRAINT not_charged_item_month_ck CHECK (month IS NULL OR month = date_trunc('month', month)::date),
    CONSTRAINT not_charged_item_range_ck CHECK (from_date IS NULL OR to_date IS NULL OR to_date >= from_date),
    -- a month item names its plot, charge type and month
    CONSTRAINT not_charged_item_tariff_month_ck CHECK (kind <> 'TARIFF_MONTH'
        OR (plot_id IS NOT NULL AND charge_type_id IS NOT NULL AND month IS NOT NULL)),
    CONSTRAINT not_charged_item_key_ck CHECK (btrim(item_key) <> '')
);

CREATE INDEX not_charged_item_tenant_idx ON not_charged_item (tenant_id);
-- The open items (the queue, the closing check list).
CREATE INDEX not_charged_item_status_idx ON not_charged_item (tenant_id, status);
-- One open item per case: a rerun refreshes it instead of adding another. SQLSTATE 23505.
CREATE UNIQUE INDEX not_charged_item_open_key_uq ON not_charged_item (tenant_id, item_key) WHERE status = 'OPEN';

ALTER TABLE not_charged_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON not_charged_item
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Items are closed, never deleted by the application; TRUNCATE would bypass the row filter.
REVOKE DELETE, TRUNCATE ON not_charged_item FROM nadel_app;
