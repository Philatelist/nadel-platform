package app.nadel.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An admin change to record. {@code eventId} becomes the id of the audit entry, so a redelivered event cannot create
 * a second entry. Carries personal data (labels, values): never log it.
 *
 * @param action      for example {@code MEMBER_CREATED}
 * @param entityType  for example {@code MEMBER}
 * @param entityLabel human-readable name of the record at the time of the change
 */
public record AuditEvent(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        Actor actor,
        String action,
        String entityType,
        UUID entityId,
        String entityLabel,
        List<FieldChange> changes) {

    public AuditEvent {
        changes = List.copyOf(changes);
    }

    /** A new event with a fresh id and the current time. */
    public static AuditEvent now(UUID tenantId, Actor actor, String action, String entityType, UUID entityId,
            String entityLabel, List<FieldChange> changes) {
        return new AuditEvent(UUID.randomUUID(), tenantId, Instant.now(), actor, action, entityType, entityId,
                entityLabel, changes);
    }
}
