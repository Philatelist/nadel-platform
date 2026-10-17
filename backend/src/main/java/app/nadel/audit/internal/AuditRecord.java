package app.nadel.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of an {@code audit_entry} row. Rows are written by {@link AuditWriter} with a native insert, and the
 * application role cannot update or delete them.
 */
@Entity
@Immutable
@Table(name = "audit_entry")
class AuditRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_member_id", updatable = false)
    private UUID actorMemberId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "entity_label", updatable = false)
    private String entityLabel;

    /** The jsonb column as text: {@code [{"field", "old", "new"}]}. */
    @Formula("cast(changes as text)")
    private String changesJson;

    protected AuditRecord() {
    }

    UUID getId() {
        return id;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    UUID getActorMemberId() {
        return actorMemberId;
    }

    String getAction() {
        return action;
    }

    String getEntityType() {
        return entityType;
    }

    UUID getEntityId() {
        return entityId;
    }

    String getEntityLabel() {
        return entityLabel;
    }

    String getChangesJson() {
        return changesJson;
    }
}
