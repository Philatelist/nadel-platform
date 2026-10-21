-- Spec 010: meters, the dated parent history of the electricity meter tree, meter readings, and the four METERS_* and
-- READINGS_* permissions.
-- Compatibility with the previous release (expand/contract): three new tables and permission rows that the previous
-- release ignores (it does not know the names). seed_base_roles keeps its signature and behaviour for the rows it wrote
-- before; it only writes more. The previous release never reads the new tables.
--
-- Every table has tenant_id, RLS with the tenant_isolation policy (USING and WITH CHECK), an index that leads with
-- tenant_id and a UNIQUE (tenant_id, id) so that composite foreign keys (tenant_id, x_id) keep every link inside one
-- cooperative. Text columns with a fixed set of values use named CHECK constraints (no enum types), as in the earlier
-- tables. Readings are quantities, not money: numeric(14,3), never rounded (ADR 0014).

-- One row per physical meter. A meter is never moved between plots: a replacement is a new row that points back at the
-- meter it replaces (a counter that wraps around is a replacement of the meter by itself: the same serial, a new row).
-- 'plot_id' is set exactly for kind PLOT. Only plot meters may measure water; the tree is electricity only. 'name' is
-- the label of a non-plot meter ("Well pump No. 1"). removed_on and final_value are set together when the meter is
-- removed; the period and the readings between are checked by the service. A meter with readings is never deleted (the
-- reading foreign key below restricts it); a meter entered by mistake, without readings, may be deleted.
CREATE TABLE meter (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    kind              text          NOT NULL,
    resource          text          NOT NULL,
    serial            text          NOT NULL,
    name              text,
    plot_id           uuid,
    installed_on      date          NOT NULL,
    initial_value     numeric(14,3) NOT NULL,
    removed_on        date,
    final_value       numeric(14,3),
    note              text,
    replaces_meter_id uuid,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT meter_tenant_id_uq UNIQUE (tenant_id, id),
    -- A plot is referenced together with its cooperative, so a link can never cross cooperatives; a plot that has a
    -- meter cannot be deleted.
    CONSTRAINT meter_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_replaces_fk FOREIGN KEY (tenant_id, replaces_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_kind_ck CHECK (kind IN ('PLOT', 'SUBSTATION', 'QUARTER', 'COMMON_CONSUMER')),
    CONSTRAINT meter_resource_ck CHECK (resource IN ('ELECTRICITY', 'WATER')),
    -- Water has plot meters only (no tree, no common water consumers in this feature).
    CONSTRAINT meter_resource_kind_ck CHECK (kind = 'PLOT' OR resource = 'ELECTRICITY'),
    CONSTRAINT meter_serial_ck CHECK (btrim(serial) <> '' AND char_length(serial) <= 50),
    -- A non-plot meter is identified by its name; a plot meter by its plot.
    CONSTRAINT meter_name_ck CHECK (
        (kind = 'PLOT' OR (name IS NOT NULL AND btrim(name) <> ''))
        AND (name IS NULL OR char_length(name) <= 100)),
    CONSTRAINT meter_plot_ck CHECK ((kind = 'PLOT') = (plot_id IS NOT NULL)),
    CONSTRAINT meter_initial_value_ck CHECK (initial_value >= 0),
    -- Removal date and final reading come together; the meter works from installed_on to removed_on, both included.
    CONSTRAINT meter_removal_ck CHECK (
        (removed_on IS NULL) = (final_value IS NULL)
        AND (removed_on IS NULL OR removed_on >= installed_on)
        AND (final_value IS NULL OR final_value >= 0)),
    CONSTRAINT meter_note_ck CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT meter_replaces_ck CHECK (replaces_meter_id IS NULL OR replaces_meter_id <> id)
);

CREATE INDEX meter_tenant_idx ON meter (tenant_id);
-- The meters of a plot (plot card, plot guards, the member's own meters).
CREATE INDEX meter_plot_idx ON meter (tenant_id, plot_id) WHERE plot_id IS NOT NULL;
-- The predecessor of a replacement (history chain of a plot).
CREATE INDEX meter_replaces_idx ON meter (tenant_id, replaces_meter_id) WHERE replaces_meter_id IS NOT NULL;
-- A plot has at most one active meter per resource; a removed meter frees the slot. SQLSTATE 23505, mapped to
-- METER_ACTIVE_EXISTS. ELECTRICITY and WATER do not clash.
CREATE UNIQUE INDEX meter_active_plot_uq ON meter (tenant_id, plot_id, resource)
    WHERE kind = 'PLOT' AND removed_on IS NULL;

ALTER TABLE meter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meter
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- UPDATE and DELETE stay granted (the default privileges of V1): a meter is edited, removed and, without readings,
-- deleted. TRUNCATE would bypass the row filter.
REVOKE TRUNCATE ON meter FROM nadel_app;

-- The dated parent of a meter in the electricity tree: one row per period (valid_from and valid_to inclusive, valid_to
-- NULL = still valid). A move to another parent on day D closes the old row on D - 1 and appends a new one; a move on
-- the same date as the latest row corrects that row. A past date range is calculated with the rows that were valid
-- then. The tree rules (kinds, depth, electricity only, the parent being active) span tables and are checked by the
-- service. The meter's own history goes with the meter when a meter without readings is deleted (CASCADE); a meter
-- that still is somebody's parent cannot be deleted (RESTRICT).
CREATE TABLE meter_parent (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    meter_id        uuid        NOT NULL,
    parent_meter_id uuid        NOT NULL,
    valid_from      date        NOT NULL,
    valid_to        date,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT meter_parent_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT meter_parent_meter_fk FOREIGN KEY (tenant_id, meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT meter_parent_parent_fk FOREIGN KEY (tenant_id, parent_meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_parent_self_ck CHECK (meter_id <> parent_meter_id),
    CONSTRAINT meter_parent_period_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- A meter has one parent at a time: the periods of one meter never overlap (btree_gist from V8). SQLSTATE 23P01.
    CONSTRAINT meter_parent_no_overlap_ex EXCLUDE USING gist (
        tenant_id WITH =, meter_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
);

CREATE INDEX meter_parent_tenant_idx ON meter_parent (tenant_id);
-- The children of a node over time (the tree for a date range).
CREATE INDEX meter_parent_children_idx ON meter_parent (tenant_id, parent_meter_id, valid_from);
-- The current children of a node and the current parent of a meter.
CREATE INDEX meter_parent_open_idx ON meter_parent (tenant_id, parent_meter_id) WHERE valid_to IS NULL;

ALTER TABLE meter_parent ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meter_parent
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- A correction of the latest row and closing a period are updates; deleting is for a deleted meter. TRUNCATE revoked.
REVOKE TRUNCATE ON meter_parent FROM nadel_app;

-- A value of a meter on a date. 'source' says who produced it (ENTERED by the board or the electrician, IMPORTED from
-- Excel, MEMBER submitted by a member). 'status': APPROVED counts for consumption; PENDING waits for approval and is
-- never used; REJECTED carries the reason the member sees; SUPERSEDED was replaced by a later entry for the same date
-- or by a correction and keeps its place in the history (superseded_by_id points at the new row). A meter has at most
-- one APPROVED reading per date. The ordering of values and the dates against the meter's working period span rows
-- and are checked by the service.
CREATE TABLE meter_reading (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    meter_id             uuid          NOT NULL,
    taken_on             date          NOT NULL,
    value                numeric(14,3) NOT NULL,
    source               text          NOT NULL,
    status               text          NOT NULL,
    entered_by_member_id uuid,
    reject_reason        text,
    superseded_by_id     uuid,
    decided_by_member_id uuid,
    decided_at           timestamptz,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT meter_reading_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT meter_reading_meter_fk FOREIGN KEY (tenant_id, meter_id)
        REFERENCES meter (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_reading_entered_by_fk FOREIGN KEY (tenant_id, entered_by_member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT meter_reading_decided_by_fk FOREIGN KEY (tenant_id, decided_by_member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    -- Deleting the newer reading clears the link only (the column list keeps tenant_id, which is NOT NULL); the older
    -- row stays SUPERSEDED with no successor and the service decides what the history shows.
    CONSTRAINT meter_reading_superseded_by_fk FOREIGN KEY (tenant_id, superseded_by_id)
        REFERENCES meter_reading (tenant_id, id) ON DELETE SET NULL (superseded_by_id),
    CONSTRAINT meter_reading_value_ck CHECK (value >= 0),
    CONSTRAINT meter_reading_source_ck CHECK (source IN ('ENTERED', 'IMPORTED', 'MEMBER')),
    CONSTRAINT meter_reading_status_ck CHECK (status IN ('APPROVED', 'PENDING', 'REJECTED', 'SUPERSEDED')),
    -- A rejection always says why; no other status has a reason.
    CONSTRAINT meter_reading_reject_reason_ck CHECK (
        (status = 'REJECTED') = (reject_reason IS NOT NULL)
        AND (reject_reason IS NULL OR (reject_reason = btrim(reject_reason) AND char_length(reject_reason) BETWEEN 1 AND 500))),
    -- Only a superseded row has a successor. The converse is not enforced: the link is cleared when the successor row
    -- is deleted (ON DELETE SET NULL above), and the row must stay valid then.
    CONSTRAINT meter_reading_superseded_ck CHECK (superseded_by_id IS NULL OR status = 'SUPERSEDED'),
    CONSTRAINT meter_reading_not_self_superseded_ck CHECK (superseded_by_id IS NULL OR superseded_by_id <> id)
);

-- The readings of a meter in date order (history, ordering check, intervals). Leads with tenant_id.
CREATE INDEX meter_reading_meter_idx ON meter_reading (tenant_id, meter_id, taken_on);
-- Readings waiting for approval (the approvals screen).
CREATE INDEX meter_reading_pending_idx ON meter_reading (tenant_id) WHERE status = 'PENDING';
-- One counted reading per meter and date; a PENDING, REJECTED or SUPERSEDED row on the same date is fine. SQLSTATE 23505,
-- which the service avoids by superseding the old row (update and flush) before it inserts the new APPROVED one.
CREATE UNIQUE INDEX meter_reading_approved_uq ON meter_reading (tenant_id, meter_id, taken_on)
    WHERE status = 'APPROVED';

ALTER TABLE meter_reading ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meter_reading
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- UPDATE (status changes, superseding) and DELETE (a correction of a reading nothing used yet, audited) stay granted;
-- the use check of the meters module guards both. TRUNCATE revoked.
REVOKE TRUNCATE ON meter_reading FROM nadel_app;

-- Base roles of a cooperative (V10 body) with the four meter permissions for the Chairman and METERS_VIEW for the
-- Treasurer, followed by the charge types. Chairman has all fifteen permissions (rows kept for display), Treasurer
-- MEMBERS_VIEW, PLOTS_VIEW, AUDIT_VIEW, TARIFFS_VIEW, METERS_VIEW, Member none. The role descriptions are unchanged.
-- Not SECURITY DEFINER (see seed_charge_types in V10).
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
        ('TREASURER', 'MEMBERS_VIEW'), ('TREASURER', 'PLOTS_VIEW'), ('TREASURER', 'AUDIT_VIEW'),
        ('TREASURER', 'TARIFFS_VIEW'), ('TREASURER', 'METERS_VIEW')
    ) AS m(code, permission)
    ON CONFLICT DO NOTHING;

    SELECT seed_charge_types(p_tenant);
$$;

GRANT EXECUTE ON FUNCTION seed_base_roles(uuid) TO nadel_app;

-- Existing cooperatives get the new permission rows by a targeted insert, never by re-running seed_base_roles: that
-- would also restore any permission a chairman had removed from a Treasurer role. Chairman gets four rows, Treasurer one
-- (idempotent).
INSERT INTO role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.id, g.permission
FROM role r
JOIN (VALUES
    ('CHAIRMAN', 'METERS_VIEW'), ('CHAIRMAN', 'METERS_MANAGE'), ('CHAIRMAN', 'READINGS_ENTER'),
    ('CHAIRMAN', 'READINGS_APPROVE'), ('TREASURER', 'METERS_VIEW')
) AS g(code, permission) ON g.code = r.base_code
ON CONFLICT DO NOTHING;
