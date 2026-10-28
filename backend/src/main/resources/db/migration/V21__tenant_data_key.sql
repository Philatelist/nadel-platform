-- Spec 014 (slice 1): per-cooperative wrapped data keys for personal data encryption.
-- Compatibility with the previous release (expand/contract): only a new table that the previous release ignores.

SET LOCAL lock_timeout = '10s';

-- One row per cooperative key version. The data key and the blind-index key are stored wrapped (encrypted) by the
-- master key identified by master_key_id (first 8 bytes of its fingerprint); raw keys never reach the database.
CREATE TABLE tenant_data_key (
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    version          smallint    NOT NULL DEFAULT 1,
    data_key_wrapped bytea       NOT NULL,
    index_key_wrapped bytea      NOT NULL,
    master_key_id    bytea       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- The leading column tenant_id also serves as the tenant_id index.
    PRIMARY KEY (tenant_id, version),
    CONSTRAINT tenant_data_key_version_ck CHECK (version >= 1),
    CONSTRAINT tenant_data_key_master_key_id_ck CHECK (octet_length(master_key_id) = 8)
);

ALTER TABLE tenant_data_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_data_key
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The application only reads and creates keys. Re-wrapping and crypto-shredding are owner operations. The default
-- privileges grant all four rights, so the extra ones are revoked explicitly. No operator role has any right here.
REVOKE UPDATE, DELETE, TRUNCATE ON tenant_data_key FROM nadel_app;
