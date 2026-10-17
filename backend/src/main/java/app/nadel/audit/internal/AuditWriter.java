package app.nadel.audit.internal;

import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts the audit entry of an event. Idempotent: the entry id is the event id, so a redelivery does nothing.
 * <p>
 * Must run in a transaction whose tenant is the event's tenant (the listener arranges that); row level security
 * rejects an insert for any other tenant.
 */
@Component
class AuditWriter {

    private static final String INSERT = """
            insert into audit_entry (id, tenant_id, occurred_at, actor_member_id, actor_subject, action, entity_type,
                                     entity_id, entity_label, changes)
            values (:id, :tenantId, :occurredAt, cast(:actorMemberId as uuid), :actorSubject, :action, :entityType, :entityId,
                    :entityLabel, cast(:changes as jsonb))
            on conflict (id) do nothing
            """;

    @PersistenceContext
    private EntityManager em;

    private final JsonMapper json;

    AuditWriter(JsonMapper json) {
        this.json = json;
    }

    /** @return true when a row was inserted, false when the event had been written before */
    boolean write(AuditEvent event) {
        int inserted = em.createNativeQuery(INSERT)
                .setParameter("id", event.eventId())
                .setParameter("tenantId", event.tenantId())
                .setParameter("occurredAt", event.occurredAt())
                .setParameter("actorMemberId", event.actor().memberId())
                .setParameter("actorSubject", event.actor().subject())
                .setParameter("action", event.action())
                .setParameter("entityType", event.entityType())
                .setParameter("entityId", event.entityId())
                .setParameter("entityLabel", event.entityLabel())
                .setParameter("changes", json.writeValueAsString(toJson(event.changes())))
                .executeUpdate();
        return inserted == 1;
    }

    private static List<ChangeJson> toJson(List<FieldChange> changes) {
        return changes.stream().map(c -> new ChangeJson(c.field(), c.oldValue(), c.newValue())).toList();
    }

    /** Stored shape of a change: {@code {"field": ..., "old": ..., "new": ...}}, nulls kept. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ChangeJson(String field, @JsonProperty("old") String oldValue, @JsonProperty("new") String newValue) {
    }
}
