-- Feature 017, owner review of PR #61: a delivery whose mailbox names a mail server the server may not connect to
-- (a loopback, private, link-local or metadata address, or a port other than 25, 465 and 587) fails at once with its
-- own code, SMTP_HOST_NOT_ALLOWED. The check constraint of V29 gains the code; nothing else changes.

ALTER TABLE delivery DROP CONSTRAINT delivery_failure_code_ck;
ALTER TABLE delivery ADD CONSTRAINT delivery_failure_code_ck CHECK (failure_code IS NULL OR failure_code IN (
    'SMTP_REJECTED', 'SMTP_AUTH_FAILED', 'CONNECTION_FAILED', 'TELEGRAM_BLOCKED', 'CHANNEL_NOT_CONFIGURED',
    'NO_CONTACT', 'SMTP_HOST_NOT_ALLOWED'));
