-- Spec 014 (slice 2, expand): encrypted member contacts, a blind index for e-mail and a phone index table.
-- Compatibility with the previous release (expand/contract): only nullable columns, a partial unique index on a new
-- nullable column and a new table are added; the plaintext columns stay until V23, after the Java migration 22.1
-- has encrypted them.

SET LOCAL lock_timeout = '10s';

-- Ciphertext (nonce, ciphertext and tag, framed by the application) of the personal contact fields.
ALTER TABLE member
    ADD COLUMN phones_enc            bytea,
    ADD COLUMN email_enc             bytea,
    ADD COLUMN postal_address_enc    bytea,
    ADD COLUMN telegram_username_enc bytea,
    ADD COLUMN viber_phone_enc       bytea,
    ADD COLUMN notes_enc             bytea,
    -- HMAC-SHA-256 of the normalized e-mail under the cooperative's index key: equality lookup and uniqueness.
    ADD COLUMN email_bidx            bytea,
    ADD CONSTRAINT member_email_bidx_ck CHECK (email_bidx IS NULL OR octet_length(email_bidx) = 32);

-- Same rule as the former member_tenant_email_uq, over the blind index (the e-mail is already normalized).
CREATE UNIQUE INDEX member_tenant_email_bidx_uq ON member (tenant_id, email_bidx)
    WHERE archived_at IS NULL AND email_bidx IS NOT NULL;

-- Blind indexes of member phones: the full normalized number and its last four digits, for equality lookup.
CREATE TABLE member_phone_index (
    tenant_id  uuid  NOT NULL,
    member_id  uuid  NOT NULL,
    phone_bidx bytea NOT NULL,
    last4_bidx bytea NOT NULL,
    PRIMARY KEY (member_id, phone_bidx),
    -- A composite key makes a link to a member of another cooperative impossible.
    CONSTRAINT member_phone_index_member_fk FOREIGN KEY (tenant_id, member_id)
        REFERENCES member (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT member_phone_index_phone_ck CHECK (octet_length(phone_bidx) = 32),
    CONSTRAINT member_phone_index_last4_ck CHECK (octet_length(last4_bidx) = 32)
);

-- The leading column tenant_id of both indexes also serves as the tenant_id index.
CREATE INDEX member_phone_index_phone_idx ON member_phone_index (tenant_id, phone_bidx);
CREATE INDEX member_phone_index_last4_idx ON member_phone_index (tenant_id, last4_bidx);

ALTER TABLE member_phone_index ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_phone_index
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The application rewrites the index rows of a member by delete and insert; a row is never updated. The default
-- privileges grant all four rights, so the extra ones are revoked explicitly.
REVOKE UPDATE, TRUNCATE ON member_phone_index FROM nadel_app;
