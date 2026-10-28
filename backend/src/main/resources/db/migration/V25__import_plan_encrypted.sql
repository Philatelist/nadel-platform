-- Spec 014 (slice 4, expand): the plan of a pending import preview is stored sealed.
-- Compatibility with the previous release (expand/contract): only a nullable column is added; the previous release
-- neither reads nor writes it. The plaintext 'plan' and 'plan_digest' stay until V26, after the Java migration 25.1
-- has sealed them.

SET LOCAL lock_timeout = '10s';

-- The sealed envelope {"plan": <normalised rows>, "digest": <plan digest>} (AES-256-GCM, framed by the application,
-- bound to this row). Like the former 'plan', it is personal data and cleared as soon as it is no longer needed.
ALTER TABLE import_run ADD COLUMN plan_enc bytea;
