-- Spec 004: base roles can be renamed. name_customized marks a base role whose name was changed by the cooperative;
-- a base role that was never renamed shows its standard name from the client's i18n catalog.
-- Custom roles always count as customised. The application derives that (base_code IS NULL OR name_customized), so no
-- CHECK constraint is added: the previous release still inserts custom roles without this column (expand/contract).
-- seed_base_roles() is unchanged: it inserts base roles with the default (false) and never overwrites.
-- RLS, the tenant_isolation policy and the tenant_id index of role are unchanged.

ALTER TABLE role ADD COLUMN name_customized boolean NOT NULL DEFAULT false;

UPDATE role SET name_customized = true WHERE base_code IS NULL;
