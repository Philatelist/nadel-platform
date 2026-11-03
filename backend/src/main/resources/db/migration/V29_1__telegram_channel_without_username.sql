-- Feature 017: the TELEGRAM delivery channel no longer needs a Telegram username. A member links Telegram through the
-- cooperative's bot (telegram_link), so the username stays only as an optional note. The channel rule keeps EMAIL and
-- VIBER as they were. The new rule is weaker than the old one, so every existing row satisfies it; the previous
-- release enforces the username in its own code and runs unchanged against this schema.

SET LOCAL lock_timeout = '10s';

ALTER TABLE member DROP CONSTRAINT member_channel_contact_ck;

ALTER TABLE member ADD CONSTRAINT member_channel_contact_ck CHECK (
    (delivery_channel <> 'EMAIL' OR email_enc IS NOT NULL)
    AND (delivery_channel <> 'VIBER' OR viber_phone_enc IS NOT NULL)) NOT VALID;

ALTER TABLE member VALIDATE CONSTRAINT member_channel_contact_ck;
