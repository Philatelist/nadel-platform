-- Feature 020, slice 2: delete a tariff that no charge ever used, and archive (close) one that was used.
-- V10 revoked DELETE on tariff ("archived with its charge type, never deleted"). A tariff that never produced a charge
-- may now be removed together with its scope plots and versions (soft-deleted ones included); the use check (no charge
-- row with this tariff_id) runs in the tariffs module in the same transaction, and the charge.tariff_id foreign key
-- (ON DELETE RESTRICT) is the safety net that stops a used tariff. The audit log keeps the history. Row level security
-- (the tenant_isolation policy is FOR ALL) limits DELETE to the caller's cooperative. TRUNCATE stays revoked.
-- DELETE on tariff_version (V11) and on tariff_scope_plot (default privileges, V10) is already granted.
-- Archiving a used tariff sets valid_until (its last day) and archived_at together; both stay null while it is active.
-- Compatibility with the previous release (expand/contract): two nullable columns it never reads, one extra grant.

SET LOCAL lock_timeout = '10s';

GRANT DELETE ON tariff TO nadel_app;
REVOKE TRUNCATE ON tariff FROM nadel_app;

ALTER TABLE tariff
    ADD COLUMN valid_until date,
    ADD COLUMN archived_at timestamptz,
    ADD CONSTRAINT tariff_archive_ck CHECK ((valid_until IS NULL) = (archived_at IS NULL));
