-- Spec 008 (slice 2): one row per uploaded import file (preview, refused, committed or stale).
-- Compatibility with the previous release (expand/contract): a new table only. The previous release neither reads nor
-- writes import_run, so rolling back the application leaves the table unused and harmless.

-- The author is a snapshot (member id, Keycloak subject and display name at the time of the upload), not a foreign
-- key: the history must survive the author being renamed or removed, and it stays readable without joining members.
-- 'counts' holds the numbers shown in the preview; 'plan' holds the normalised rows of a preview that can still be
-- committed (it contains personal data, so it is cleared when the import is committed, refused or expired, and it is
-- never read by the history list). 'plan_digest' is the digest of the full plan, compared at commit time.
-- EXPIRED is not a status: a PREVIEW past expires_at is shown as expired.
CREATE TABLE import_run (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant (id),
    file_name            text        NOT NULL,
    created_by_member_id uuid,
    created_by_subject   text        NOT NULL,
    created_by_name      text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL,
    committed_at         timestamptz,
    status               text        NOT NULL,
    counts               jsonb       NOT NULL DEFAULT '{}',
    problem_count        integer     NOT NULL DEFAULT 0,
    plan                 jsonb,
    plan_digest          text,
    CONSTRAINT import_run_file_name_ck CHECK (char_length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT import_run_status_ck CHECK (status IN ('PREVIEW', 'HAS_ERRORS', 'COMMITTED', 'STALE')),
    CONSTRAINT import_run_problem_count_ck CHECK (problem_count >= 0),
    CONSTRAINT import_run_expiry_ck CHECK (expires_at > created_at),
    -- only a preview that can still be committed keeps its plan
    CONSTRAINT import_run_plan_ck CHECK (plan IS NULL OR status = 'PREVIEW'),
    CONSTRAINT import_run_committed_ck CHECK ((status = 'COMMITTED') = (committed_at IS NOT NULL))
);

CREATE INDEX import_run_tenant_idx ON import_run (tenant_id);
-- The history list, newest first.
CREATE INDEX import_run_tenant_created_idx ON import_run (tenant_id, created_at DESC);

ALTER TABLE import_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_run
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The import history is kept (V3 pattern for audit_entry); updates stay allowed to record the outcome and clear the plan.
REVOKE DELETE, TRUNCATE ON import_run FROM nadel_app;
