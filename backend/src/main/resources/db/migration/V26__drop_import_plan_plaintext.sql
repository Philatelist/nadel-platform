-- Spec 014 (slice 4, contract): remove the plaintext import plan and its digest after the Java migration 25.1 sealed
-- them into plan_enc. Compatibility with the previous release: the previous release still reads these columns, so this
-- migration ships only with the release that stops using them (the release of V25 and 25.1).

SET LOCAL lock_timeout = '10s';

-- Safety net: refuse to drop a plaintext plan that has no sealed copy yet (the Java migration did not run).
-- The message carries counts only, never values.
DO $$
DECLARE
    unsealed bigint;
BEGIN
    SELECT count(*) INTO unsealed FROM import_run WHERE plan IS NOT NULL AND plan_enc IS NULL;
    IF unsealed > 0 THEN
        RAISE EXCEPTION 'V26 aborted: % import runs still hold a plaintext plan without ciphertext; '
            'the Java migration 25.1 did not run', unsealed;
    END IF;
END
$$;

-- The digest now lives inside the sealed envelope. The rule of V9 (only a preview keeps its plan) moves to plan_enc.
ALTER TABLE import_run
    DROP CONSTRAINT import_run_plan_ck,
    DROP COLUMN plan,
    DROP COLUMN plan_digest;

ALTER TABLE import_run ADD CONSTRAINT import_run_plan_ck CHECK (plan_enc IS NULL OR status = 'PREVIEW');
