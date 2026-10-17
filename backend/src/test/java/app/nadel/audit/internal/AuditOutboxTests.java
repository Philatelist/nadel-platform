package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import app.nadel.IntegrationTest;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit entries are written from domain events through the event publication registry (outbox). */
class AuditOutboxTests extends IntegrationTest {

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    IncompleteEventPublications incomplete;

    @Autowired
    JsonMapper json;

    @MockitoSpyBean
    AuditWriter writer;

    @Test
    void entryIsWrittenWithActorAndFieldChanges() throws Exception {
        UUID actorMember = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        AuditEvent event = new AuditEvent(UUID.randomUUID(), DEMO_TENANT, Instant.parse("2026-01-02T03:04:05Z"),
                new Actor(actorMember, "sub-writer", "Writer Person"), "MEMBER_UPDATED", "MEMBER", entityId,
                "Ivanenko Petro",
                List.of(new FieldChange("phones", "+380671234567", "+380501112233"),
                        new FieldChange("middleName", null, "Ivanovych")));

        publish(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(entryCount(event.eventId())).isEqualTo(1));
        Map<String, Object> row = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select actor_member_id, actor_subject, action, entity_type, entity_id, entity_label, occurred_at,"
                        + " cast(changes as text) as changes from audit_entry where id = ?", event.eventId()));
        assertThat(row.get("actor_member_id")).isEqualTo(actorMember);
        assertThat(row.get("actor_subject")).isEqualTo("sub-writer");
        assertThat(row.get("action")).isEqualTo("MEMBER_UPDATED");
        assertThat(row.get("entity_type")).isEqualTo("MEMBER");
        assertThat(row.get("entity_id")).isEqualTo(entityId);
        assertThat(row.get("entity_label")).isEqualTo("Ivanenko Petro");
        JsonNode changes = json.readTree((String) row.get("changes"));
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("field").asString()).isEqualTo("phones");
        assertThat(changes.get(0).get("old").asString()).isEqualTo("+380671234567");
        assertThat(changes.get(0).get("new").asString()).isEqualTo("+380501112233");
        assertThat(changes.get(1).get("field").asString()).isEqualTo("middleName");
        assertThat(changes.get(1).get("old").isNull()).isTrue();
        assertThat(changes.get(1).get("new").asString()).isEqualTo("Ivanovych");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publications(event.eventId())).isZero());
    }

    @Test
    void failedListenerLeavesAnIncompletePublicationThatIsRedelivered() {
        AuditEvent event = event();
        doThrow(new IllegalStateException("simulated failure")).doCallRealMethod().when(writer).write(any());

        publish(event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(publications(event.eventId())).isEqualTo(1);
            assertThat(incompletePublications(event.eventId())).isEqualTo(1);
        });
        assertThat(entryCount(event.eventId())).isZero();

        incomplete.resubmitIncompletePublications(
                p -> p.getEvent() instanceof AuditEvent e && e.eventId().equals(event.eventId()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(entryCount(event.eventId())).isEqualTo(1);
            assertThat(publications(event.eventId())).isZero();
        });
    }

    /**
     * A redelivery arrives after the first delivery has finished. The second publication waits for the first to
     * complete: the registry tracks in-progress publications by event and listener, so two publications of an equal
     * event that complete at the same moment can resolve to the same publication and leave the other one incomplete.
     */
    @Test
    void sameEventIdDeliveredTwiceCreatesOneEntry() {
        AuditEvent event = event();

        publish(event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(entryCount(event.eventId())).isEqualTo(1);
            assertThat(publications(event.eventId())).isZero();
        });

        publish(event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publications(event.eventId())).isZero());

        assertThat(entryCount(event.eventId())).isEqualTo(1);
    }

    private void publish(AuditEvent event) {
        TenantContext.runAs(event.tenantId(), () -> tx.execute(status -> {
            publisher.publishEvent(event);
            return null;
        }));
    }

    private static AuditEvent event() {
        return AuditEvent.now(DEMO_TENANT, new Actor(null, "sub-system", "System"), "MEMBER_CREATED", "MEMBER",
                UUID.randomUUID(), "Test Member", List.of(new FieldChange("lastName", null, "Test")));
    }

    private int entryCount(UUID eventId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from audit_entry where id = ?", Integer.class, eventId));
    }

    /** Publications of this event that still exist (completed ones are deleted). */
    private int publications(UUID eventId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from event_publication where serialized_event like ?", Integer.class,
                "%" + eventId + "%"));
    }

    private int incompletePublications(UUID eventId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + eventId + "%"));
    }
}
