-- Spec 005: base role descriptions are shown in the user's language. description_customized marks a role whose
-- description was written by the cooperative; a base role whose description was never changed shows its standard
-- description from the client's i18n catalog.
-- Custom roles always count as customised. The application derives that (base_code IS NULL OR description_customized),
-- so no CHECK constraint is added: the previous release still inserts custom roles without this column
-- (expand/contract).
-- seed_base_roles() is unchanged: it inserts base roles with the default (false) and never overwrites.
-- RLS, the tenant_isolation policy and the tenant_id index of role are unchanged.

ALTER TABLE role ADD COLUMN description_customized boolean NOT NULL DEFAULT false;

UPDATE role SET description_customized = true WHERE base_code IS NULL;

-- Descriptions of base roles could be edited since spec 002. Keep an edit visible: a base role whose stored
-- description differs from the text seed_base_roles (V2) wrote (including a cleared description) counts as customised.
UPDATE role SET description_customized = true
WHERE base_code IS NOT NULL
  AND description IS DISTINCT FROM CASE base_code
        WHEN 'CHAIRMAN' THEN 'Full access'
        WHEN 'TREASURER' THEN 'Read access to members, plots and audit'
        WHEN 'MEMBER' THEN 'Own data only'
    END;
