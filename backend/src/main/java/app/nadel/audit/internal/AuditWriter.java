package app.nadel.audit.internal;

import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts the audit entry of an event, or of a whole batch of them. Idempotent: the entry id is the event id, so a redelivery does nothing.
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

    /** The same statement with positional parameters, for the JDBC batch. */
    private static final String BATCH_INSERT = """
            insert into audit_entry (id, tenant_id, occurred_at, actor_member_id, actor_subject, action, entity_type,
                                     entity_id, entity_label, changes)
            values (?, ?, ?, cast(? as uuid), ?, ?, ?, ?, ?, cast(? as jsonb))
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

    /**
     * Inserts the entries of a batch with one JDBC batch ({@code PreparedStatement.executeBatch}) on the connection of
     * the current transaction, so row level security applies as for {@link #write}. Parameterised, one statement text
     * for every row. Idempotent per entry id.
     *
     * @return the number of rows inserted; entries that had been written before are not counted
     */
    int writeAll(List<AuditEvent> events) {
        List<String> changes = events.stream().map(e -> json.writeValueAsString(toJson(e.changes()))).toList();
        int[] counts = new int[1];
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(BATCH_INSERT)) {
                for (int i = 0; i < events.size(); i++) {
                    AuditEvent event = events.get(i);
                    UUID actorMemberId = event.actor().memberId();
                    statement.setObject(1, event.eventId());
                    statement.setObject(2, event.tenantId());
                    statement.setTimestamp(3, Timestamp.from(event.occurredAt()));
                    statement.setString(4, actorMemberId == null ? null : actorMemberId.toString());
                    statement.setString(5, event.actor().subject());
                    statement.setString(6, event.action());
                    statement.setString(7, event.entityType());
                    statement.setObject(8, event.entityId());
                    statement.setString(9, event.entityLabel());
                    statement.setString(10, changes.get(i));
                    statement.addBatch();
                }
                for (int count : statement.executeBatch()) {
                    counts[0] += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
                }
            }
        });
        return counts[0];
    }

    private static List<ChangeJson> toJson(List<FieldChange> changes) {
        return changes.stream().map(c -> new ChangeJson(c.field(), c.oldValue(), c.newValue())).toList();
    }

    /** Stored shape of a change: {@code {"field": ..., "old": ..., "new": ...}}, nulls kept. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ChangeJson(String field, @JsonProperty("old") String oldValue, @JsonProperty("new") String newValue) {
    }
}
