-- Spec 007 (slice 2): ownership of plots, with shares and validity periods.
-- Compatibility with the previous release (expand/contract): a new table and a new unique constraint on plot only. The
-- previous release neither reads nor writes ownership, and the extra constraint on plot (tenant_id, id) is implied by the
-- primary key, so no write of the previous release can violate it.

-- The target of the composite foreign key of ownership: a plot is referenced together with its cooperative, so a link
-- can never cross cooperatives (the same pattern as member_tenant_id_uq).
ALTER TABLE plot ADD CONSTRAINT plot_tenant_id_uq UNIQUE (tenant_id, id);

-- btree_gist lets the exclusion constraint below combine equality on uuid columns with range overlap. It is a trusted
-- extension (PostgreSQL 13+), so the migration owner may create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- One row per owner of a plot and period. The current owners of a plot have valid_to = NULL; the period is closed
-- (inclusive on both ends), so a change of owner on day D ends the old rows on D - 1. Rows are never deleted: a
-- correction rewrites the row and is audited. The shares of the current owners adding up to one whole is checked by the
-- application (it spans several rows); the reduced form of the fraction is also enforced by the application.
CREATE TABLE ownership (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    plot_id           uuid        NOT NULL,
    member_id         uuid        NOT NULL,
    share_numerator   smallint    NOT NULL,
    share_denominator smallint    NOT NULL,
    valid_from        date        NOT NULL,
    valid_to          date,
    basis             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ownership_plot_fk FOREIGN KEY (tenant_id, plot_id)
        REFERENCES plot (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ownership_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ownership_share_ck CHECK (share_numerator >= 1 AND share_numerator <= share_denominator
        AND share_denominator <= 1000),
    CONSTRAINT ownership_period_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ownership_basis_ck CHECK (basis IS NULL OR char_length(basis) <= 500),
    -- The same member cannot own the same plot twice in overlapping periods. SQLSTATE 23P01.
    CONSTRAINT ownership_no_overlap_ex EXCLUDE USING gist (
        tenant_id WITH =, plot_id WITH =, member_id WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
);

CREATE INDEX ownership_tenant_idx ON ownership (tenant_id);
-- Current owners of a plot (the hot read path of the card, the list and the status).
CREATE INDEX ownership_plot_current_idx ON ownership (tenant_id, plot_id) WHERE valid_to IS NULL;
-- History of a plot, newest first.
CREATE INDEX ownership_plot_from_idx ON ownership (tenant_id, plot_id, valid_from);
-- Plots of a member (member card, archive guard).
CREATE INDEX ownership_member_idx ON ownership (tenant_id, member_id);

ALTER TABLE ownership ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ownership
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Records of ownership are never deleted (V3 pattern for audit_entry); updates stay allowed for corrections and for
-- closing the period of a former owner.
REVOKE DELETE, TRUNCATE ON ownership FROM nadel_app;
