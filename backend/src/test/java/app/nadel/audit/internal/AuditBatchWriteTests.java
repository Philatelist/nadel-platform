package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import app.nadel.IntegrationTest;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit batches: one JDBC batch insert, idempotent, tenant bound, delivered through the outbox. */
class AuditBatchWriteTests extends IntegrationTest {

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

    // ---- the writer

    @Test
    void oneBatchWritesEveryEntryWithAllItsFields() throws Exception {
        UUID actorMember = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        AuditEvent full = new AuditEvent(UUID.randomUUID(), DEMO_TENANT, Instant.parse("2026-02-03T04:05:06Z"),
                new Actor(actorMember, "sub-batch-writer", "Writer"), "MEMBER_UPDATED", "MEMBER", entityId,
                "Ivanenko Petro", List.of(new FieldChange("phones", "+380671234567", "+380501112233"),
                        new FieldChange("middleName", null, "Ivanovych")));
        AuditEvent minimal = new AuditEvent(UUID.randomUUID(), DEMO_TENANT, Instant.parse("2026-02-03T04:05:07Z"),
                new Actor(null, null, "System"), "PLOT_CREATED", "PLOT", UUID.randomUUID(), null, List.of());
        List<AuditEvent> events = new ArrayList<>(List.of(full, minimal));
        events.addAll(events(DEMO_TENANT, 48));

        int inserted = write(DEMO_TENANT, events);

        assertThat(inserted).isEqualTo(50);
        assertThat(entryCount(events)).isEqualTo(50);
        Map<String, Object> row = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select tenant_id, actor_member_id, actor_subject, action, entity_type, entity_id, entity_label,"
                        + " occurred_at, cast(changes as text) as changes from audit_entry where id = ?",
                full.eventId()));
        assertThat(row.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(row.get("actor_member_id")).isEqualTo(actorMember);
        assertThat(row.get("actor_subject")).isEqualTo("sub-batch-writer");
        assertThat(row.get("action")).isEqualTo("MEMBER_UPDATED");
        assertThat(row.get("entity_type")).isEqualTo("MEMBER");
        assertThat(row.get("entity_id")).isEqualTo(entityId);
        assertThat(row.get("entity_label")).isEqualTo("Ivanenko Petro");
        assertThat(((Timestamp) row.get("occurred_at")).toInstant())
                .isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
        JsonNode changes = json.readTree((String) row.get("changes"));
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).get("field").asString()).isEqualTo("phones");
        assertThat(changes.get(0).get("old").asString()).isEqualTo("+380671234567");
        assertThat(changes.get(1).get("old").isNull()).isTrue();
        assertThat(changes.get(1).get("new").asString()).isEqualTo("Ivanovych");

        Map<String, Object> bare = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select actor_member_id, actor_subject, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where id = ?", minimal.eventId()));
        assertThat(bare.get("actor_member_id")).isNull();
        assertThat(bare.get("actor_subject")).isNull();
        assertThat(bare.get("entity_label")).isNull();
        assertThat(json.readTree((String) bare.get("changes"))).isEmpty();
    }

    @Test
    void writingTheSameBatchAgainCreatesNoDuplicates() {
        List<AuditEvent> events = events(DEMO_TENANT, 30);

        assertThat(write(DEMO_TENANT, events)).isEqualTo(30);
        assertThat(write(DEMO_TENANT, events)).isZero();

        assertThat(entryCount(events)).isEqualTo(30);
    }

    @Test
    void aBatchThatRepeatsAnEntryWritesItOnce() {
        List<AuditEvent> events = events(DEMO_TENANT, 3);
        List<AuditEvent> repeated = List.of(events.get(0), events.get(1), events.get(2), events.get(0));

        assertThat(write(DEMO_TENANT, repeated)).isEqualTo(3);

        assertThat(entryCount(events)).isEqualTo(3);
    }

    @Test
    void aBatchWrittenForAnotherCooperativeIsRejectedByRowLevelSecurity() {
        List<AuditEvent> events = events(DEMO_TENANT, 5);

        // the transaction belongs to OTHER_TENANT, the entries to DEMO_TENANT: the policy refuses the insert
        assertThatThrownBy(() -> write(OTHER_TENANT, events)).isInstanceOf(RuntimeException.class);

        assertThat(entryCount(events)).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where id = any (?)", Integer.class,
                (Object) events.stream().map(AuditEvent::eventId).toArray(UUID[]::new))).isZero();
    }

    @Test
    void entriesOfOneCooperativeAreInvisibleToAnother() {
        List<AuditEvent> events = events(DEMO_TENANT, 4);
        write(DEMO_TENANT, events);

        assertThat(entryCount(OTHER_TENANT, events)).isZero();
    }

    @Test
    void aFailedBatchLeavesNoPartialRows() {
        AuditEvent good = events(DEMO_TENANT, 1).getFirst();
        // a null action violates NOT NULL on the second row: the whole batch fails and the first row is not kept
        AuditEvent broken = new AuditEvent(UUID.randomUUID(), DEMO_TENANT, Instant.now(), new Actor(null, "s", "n"),
                null, "PLOT", UUID.randomUUID(), "x", List.of());

        assertThatThrownBy(() -> write(DEMO_TENANT, List.of(good, broken))).isInstanceOf(RuntimeException.class);

        assertThat(entryCount(List.of(good))).isZero();
    }

    // ---- the listener and the outbox

    @Test
    void aPublishedBatchIsWrittenForItsCooperativeAndItsPublicationCompletes() {
        List<AuditEvent> events = events(DEMO_TENANT, 40);
        AuditBatch batch = new AuditBatch(UUID.randomUUID(), DEMO_TENANT, events);

        publish(batch);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(entryCount(events)).isEqualTo(40);
            assertThat(publications(batch.batchId())).isZero();
        });
    }

    @Test
    void aFailedBatchStaysIncompleteAndIsRedeliveredOnceWithoutDuplicates() {
        List<AuditEvent> events = events(DEMO_TENANT, 25);
        AuditBatch batch = new AuditBatch(UUID.randomUUID(), DEMO_TENANT, events);
        doThrow(new IllegalStateException("simulated failure")).doCallRealMethod().when(writer).writeAll(any());

        publish(batch);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(incompletePublications(batch.batchId()))
                .isEqualTo(1));
        assertThat(entryCount(events)).isZero();

        incomplete.resubmitIncompletePublications(
                p -> p.getEvent() instanceof AuditBatch b && b.batchId().equals(batch.batchId()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(entryCount(events)).isEqualTo(25);
            assertThat(publications(batch.batchId())).isZero();
        });
    }

    @Test
    void theSameBatchPublishedTwiceCreatesEachEntryOnce() {
        List<AuditEvent> events = events(DEMO_TENANT, 10);
        AuditBatch batch = new AuditBatch(UUID.randomUUID(), DEMO_TENANT, events);

        publish(batch);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(entryCount(events)).isEqualTo(10);
            assertThat(publications(batch.batchId())).isZero();
        });

        publish(batch);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(publications(batch.batchId())).isZero());

        assertThat(entryCount(events)).isEqualTo(10);
    }

    // ---- helpers

    private int write(UUID tenant, List<AuditEvent> events) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> writer.writeAll(events)));
    }

    private void publish(AuditBatch batch) {
        TenantContext.runAs(batch.tenantId(), () -> tx.execute(status -> {
            publisher.publishEvent(batch);
            return null;
        }));
    }

    private static List<AuditEvent> events(UUID tenant, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> AuditEvent.now(tenant, new Actor(null, "sub-batch", "Batch Actor"), "PLOT_CREATED",
                        "PLOT", UUID.randomUUID(), "B-" + i, List.of(new FieldChange("number", null, "B-" + i))))
                .toList();
    }

    private int entryCount(List<AuditEvent> events) {
        return entryCount(DEMO_TENANT, events);
    }

    private int entryCount(UUID tenant, List<AuditEvent> events) {
        UUID[] ids = events.stream().map(AuditEvent::eventId).toArray(UUID[]::new);
        return inTenant(tenant, sql -> sql.queryForObject(
                "select count(*) from audit_entry where id = any (?)", Integer.class, (Object) ids));
    }

    private int publications(UUID batchId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from event_publication where serialized_event like ?", Integer.class,
                "%" + batchId + "%"));
    }

    private int incompletePublications(UUID batchId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + batchId + "%"));
    }
}
