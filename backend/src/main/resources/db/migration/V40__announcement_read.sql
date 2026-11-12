-- Members read the board's announcements in the account (feature 021, follow-up): announcement_read keeps when a
-- member first opened an announcement they received, so the account shows the others as new.
--
-- One row per cooperative, announcement and member, written once (INSERT ... ON CONFLICT DO NOTHING): the first opening
-- is kept and nothing ever changes it. Not ledger data. The links are ON DELETE CASCADE, as for push_subscription
-- (V39): a read mark is derived data that carries nothing worth keeping, so the demo wipe of the announcements (and a
-- member's removal) takes the marks with them. Members are archived, not deleted. Expand-only: the previous release
-- ignores the table.

SET LOCAL lock_timeout = '10s';

CREATE TABLE announcement_read (
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    announcement_id uuid        NOT NULL,
    member_id       uuid        NOT NULL,
    read_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, announcement_id, member_id),
    CONSTRAINT announcement_read_announcement_fk FOREIGN KEY (tenant_id, announcement_id)
        REFERENCES announcement (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT announcement_read_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX announcement_read_tenant_idx ON announcement_read (tenant_id);
CREATE INDEX announcement_read_member_idx ON announcement_read (tenant_id, member_id);

ALTER TABLE announcement_read ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON announcement_read
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The default privileges (V1) give nadel_app SELECT, INSERT, UPDATE and DELETE. A mark is only ever inserted (the
-- conflict clause needs no UPDATE right), so the application keeps SELECT and INSERT.
REVOKE UPDATE, DELETE, TRUNCATE ON announcement_read FROM nadel_app;
