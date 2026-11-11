-- Spec 021 (slice 4): the photo of a meter reading and the cooperative setting that makes it mandatory for a member's
-- reading. One photo per reading (JPEG or PNG, at most 5 MiB, its metadata already stripped by the application), stored
-- in the database like the receipts of expense_attachment (V32).
-- Compatibility with the previous release (expand/contract): a new table and a new column with a default that the
-- previous release never reads or writes.
--
-- A photo is written once and never changed. nadel_app has no UPDATE, DELETE or TRUNCATE; the guard trigger refuses an
-- UPDATE to everyone and a DELETE while the reading still exists, so only the cascade of a deleted reading removes it
-- (a reading that nothing has used can be deleted by the correction flow, V12).

SET LOCAL lock_timeout = '10s';

CREATE TABLE reading_photo (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    reading_id   uuid        NOT NULL,
    content_type text        NOT NULL,
    size_bytes   integer     NOT NULL,
    sha256       bytea       NOT NULL,
    content      bytea       NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reading_photo_tenant_id_uq UNIQUE (tenant_id, id),
    CONSTRAINT reading_photo_reading_uq UNIQUE (tenant_id, reading_id),
    CONSTRAINT reading_photo_reading_fk FOREIGN KEY (tenant_id, reading_id)
        REFERENCES meter_reading (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT reading_photo_created_by_fk FOREIGN KEY (tenant_id, created_by)
        REFERENCES member (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT reading_photo_type_ck CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT reading_photo_size_ck CHECK (size_bytes BETWEEN 1 AND 5242880 AND octet_length(content) = size_bytes),
    CONSTRAINT reading_photo_sha256_ck CHECK (octet_length(sha256) = 32)
);

CREATE INDEX reading_photo_tenant_idx ON reading_photo (tenant_id);

-- The file is already compressed: store it out of line without compression.
ALTER TABLE reading_photo ALTER COLUMN content SET STORAGE EXTERNAL;

ALTER TABLE reading_photo ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reading_photo
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- No UPDATE ever; DELETE only once the parent reading is gone, that is from the foreign key cascade (the referential
-- action runs after the reading row is deleted and sees that). Not SECURITY DEFINER: the check runs as the caller.
CREATE FUNCTION reading_photo_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'reading_photo is write-once: UPDATE is not allowed'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' AND EXISTS (SELECT 1 FROM meter_reading WHERE tenant_id = OLD.tenant_id AND id = OLD.reading_id) THEN
        RAISE EXCEPTION 'reading_photo can only be removed together with its reading'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reading_photo_guard BEFORE UPDATE OR DELETE ON reading_photo
    FOR EACH ROW EXECUTE FUNCTION reading_photo_guard();
CREATE TRIGGER reading_photo_no_truncate BEFORE TRUNCATE ON reading_photo
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON reading_photo FROM nadel_app;

-- Whether a reading submitted by a member must come with a photo (default: no, as before).
ALTER TABLE cooperative_settings ADD COLUMN reading_photo_required boolean NOT NULL DEFAULT false;
