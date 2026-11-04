-- Feature 018, slice 5: the reason the chairman gives when booking a bank difference («Банківська різниця») of a
-- monthly bank reconciliation. The booked line (account_movement, kind BANK_DIFFERENCE, source_type
-- BANK_RECONCILIATION, source_id = the month's bank_reconciliation row) cannot carry it: account_movement.reason is
-- reserved for reversals (account_movement_reason_ck), and a month can have several booked differences, so each booked
-- line gets one row here (the V33 pattern of cash_count.booking_reason, as a table).
-- Compatibility with the previous release (expand/contract): one new table the previous release never reads.
--
-- Append-only like the journal it explains: ledger_append_only() triggers and no UPDATE, DELETE or TRUNCATE for
-- nadel_app. A booked line is corrected by its reversal (a new movement with its own reason), never here.

SET LOCAL lock_timeout = '10s';

CREATE TABLE bank_difference (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenant (id),
    reconciliation_id  uuid        NOT NULL,
    movement_id        uuid        NOT NULL,
    reason             text        NOT NULL,
    created_by         uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT bank_difference_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT bank_difference_reconciliation_fk FOREIGN KEY (tenant_id, reconciliation_id)
        REFERENCES bank_reconciliation (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_difference_movement_fk FOREIGN KEY (tenant_id, movement_id)
        REFERENCES account_movement (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_difference_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT bank_difference_reason_ck CHECK (btrim(reason) <> '' AND char_length(reason) <= 500)
);

CREATE INDEX bank_difference_tenant_idx ON bank_difference (tenant_id);
-- One reason per booked line. SQLSTATE 23505.
CREATE UNIQUE INDEX bank_difference_movement_uq ON bank_difference (tenant_id, movement_id);
CREATE INDEX bank_difference_reconciliation_idx ON bank_difference (tenant_id, reconciliation_id);

ALTER TABLE bank_difference ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_difference
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER bank_difference_append_only BEFORE UPDATE OR DELETE ON bank_difference
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER bank_difference_no_truncate BEFORE TRUNCATE ON bank_difference
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON bank_difference FROM nadel_app;
