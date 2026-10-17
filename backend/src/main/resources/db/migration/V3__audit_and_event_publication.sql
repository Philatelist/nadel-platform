-- Slice 3: audit log and the Spring Modulith event publication registry.

-- Audit log of admin actions. Append-only: the application role may only read and insert.
-- id is the id of the domain event that produced the entry, so a redelivered event cannot create a second row.
CREATE TABLE audit_entry (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    occurred_at       timestamptz NOT NULL,
    actor_member_id   uuid,
    actor_subject     text,
    action            text        NOT NULL,
    entity_type       text        NOT NULL,
    entity_id         uuid        NOT NULL,
    entity_label      text,
    changes           jsonb       NOT NULL DEFAULT '[]'::jsonb
);

COMMENT ON COLUMN audit_entry.changes IS 'Array of {field, old, new} objects';

CREATE INDEX audit_entry_tenant_idx ON audit_entry (tenant_id);
CREATE INDEX audit_entry_tenant_occurred_idx ON audit_entry (tenant_id, occurred_at DESC);
CREATE INDEX audit_entry_tenant_entity_idx ON audit_entry (tenant_id, entity_type, entity_id);
CREATE INDEX audit_entry_tenant_actor_idx ON audit_entry (tenant_id, actor_member_id);

ALTER TABLE audit_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_entry
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- V1 default privileges grant UPDATE and DELETE; the audit log must not allow them (TRUNCATE revoked defensively).
REVOKE UPDATE, DELETE, TRUNCATE ON audit_entry FROM nadel_app;

-- Spring Modulith event publication registry, copied exactly from
-- org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql in spring-modulith-events-jdbc-2.1.1.jar
-- (spring-modulith-events-jpa-2.1.1.jar ships no SQL; its JpaEventPublication entity maps to this same table).
--
-- No tenant_id and no RLS, deliberately: these are infrastructure rows. Incomplete publications are resubmitted at
-- application startup, which runs without a tenant, so a tenant policy would hide them. Payloads may contain
-- personal data, so completion mode is "delete" (completed rows are removed, no archive table is created).
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
