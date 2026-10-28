-- Spec 014 (slice 2, contract): remove the plaintext member contacts after the Java migration 22.1 encrypted them.
-- Compatibility with the previous release: the previous release still reads these columns, so this migration ships
-- only with the release that stops using them (the release of V22 and 22.1).

SET LOCAL lock_timeout = '10s';

-- Safety net: refuse to drop a plaintext value that has no ciphertext yet (the Java migration did not run).
-- The message carries counts only, never values.
DO $$
DECLARE
    unencrypted bigint;
BEGIN
    SELECT count(*) INTO unencrypted FROM member
    WHERE (phones <> '{}' AND phones_enc IS NULL)
       OR (coalesce(email, '') <> '' AND email_enc IS NULL)
       OR (coalesce(postal_address, '') <> '' AND postal_address_enc IS NULL)
       OR (coalesce(telegram_username, '') <> '' AND telegram_username_enc IS NULL)
       OR (coalesce(viber_phone, '') <> '' AND viber_phone_enc IS NULL)
       OR (coalesce(notes, '') <> '' AND notes_enc IS NULL);
    IF unencrypted > 0 THEN
        RAISE EXCEPTION 'V23 aborted: % member rows still hold plaintext contacts without ciphertext; '
            'the Java migration 22.1 did not run', unencrypted;
    END IF;
END
$$;

-- Dropping the columns also drops member_tenant_email_uq, member_phones_gin and member_email_normalized_ck
-- (they are listed explicitly so the intent is visible), and member_channel_contact_ck is recreated on ciphertext.
DROP INDEX member_tenant_email_uq;
DROP INDEX member_phones_gin;
ALTER TABLE member
    DROP CONSTRAINT member_email_normalized_ck,
    DROP CONSTRAINT member_channel_contact_ck,
    DROP COLUMN phones,
    DROP COLUMN email,
    DROP COLUMN postal_address,
    DROP COLUMN telegram_username,
    DROP COLUMN viber_phone,
    DROP COLUMN notes;

ALTER TABLE member ADD CONSTRAINT member_channel_contact_ck CHECK (
    (delivery_channel <> 'EMAIL' OR email_enc IS NOT NULL)
    AND (delivery_channel <> 'TELEGRAM' OR telegram_username_enc IS NOT NULL)
    AND (delivery_channel <> 'VIBER' OR viber_phone_enc IS NOT NULL));
