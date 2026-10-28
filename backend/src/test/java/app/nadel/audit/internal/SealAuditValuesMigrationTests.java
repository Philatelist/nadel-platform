package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java migration 24 over audit data written in the clear by the previous release, in two cooperatives: member
 * entries, an entry of another entity with a field of the same name, a pending publication of a member's audit event
 * and a pending import batch. Runs the migration on an owner connection of the shared container (as Flyway does),
 * twice: the second run changes nothing.
 */
class SealAuditValuesMigrationTests extends IntegrationTest {

    @Autowired
    SealAuditValuesMigration migration;

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    JsonMapper json;

    private final UUID memberEntry = UUID.randomUUID();
    private final UUID otherMemberEntry = UUID.randomUUID();
    private final UUID plotEntry = UUID.randomUUID();
    private final UUID publication = UUID.randomUUID();
    private final UUID batchPublication = UUID.randomUUID();

    @AfterEach
    void removePublications() {
        // audit_entry rows stay (append-only, unique ids); a pending publication must not be resubmitted later
        ownerJdbc().update("delete from event_publication where id in (?, ?)", publication, batchPublication);
    }

    @Test
    void sealsThePersonalValuesOfMemberEntriesAndPendingPublicationsOnce() throws Exception {
        insertEntry(memberEntry, "MEMBER", """
                [{"field": "phones", "old": "+380671230024", "new": "+380501230024"},
                 {"field": "email", "old": null, "new": "migrated24@example.com"},
                 {"field": "middleName", "old": null, "new": "Ivanovych"}]""");
        insertEntry(plotEntry, "PLOT", "[{\"field\": \"notes\", \"old\": null, \"new\": \"plot note 24\"}]");
        insertEntry(OTHER_TENANT, otherMemberEntry, "MEMBER", """
                [{"field": "postalAddress", "old": "Other street 24", "new": null},
                 {"field": "telegramUsername", "old": null, "new": "other_tg_24"}]""");
        AuditEvent pending = new AuditEvent(UUID.randomUUID(), DEMO_TENANT, Instant.parse("2026-05-10T10:00:00Z"),
                new Actor(null, "migration-test", "Migration"), "MEMBER_UPDATED", "MEMBER", UUID.randomUUID(),
                "Pending Member", List.of(new FieldChange("notes", "old note 24", "new note 24"),
                        new FieldChange("lastName", "Old", "New")));
        ownerJdbc().update("insert into event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date) values (?, 'migration-test', ?, ?, now())", publication,
                AuditEvent.class.getName(), json.writeValueAsString(pending));
        // an import batch of the other cooperative: a member event and an event of another entity
        AuditEvent batchedMember = new AuditEvent(UUID.randomUUID(), OTHER_TENANT,
                Instant.parse("2026-05-10T10:00:00Z"), new Actor(null, "migration-test", "Import"), "MEMBER_CREATED", "MEMBER", UUID.randomUUID(),
                "Batched Member", List.of(new FieldChange("viberPhone", null, "+380931230024"),
                        new FieldChange("firstName", null, "Batched")));
        AuditEvent batchedPlot = new AuditEvent(UUID.randomUUID(), OTHER_TENANT,
                Instant.parse("2026-05-10T10:00:00Z"), new Actor(null, "migration-test", "Import"), "PLOT_CREATED", "PLOT", UUID.randomUUID(), "24",
                List.of(new FieldChange("notes", null, "batched plot note 24")));
        ownerJdbc().update("insert into event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date) values (?, 'migration-test', ?, ?, now())", batchPublication,
                AuditBatch.class.getName(), json.writeValueAsString(
                        new AuditBatch(UUID.randomUUID(), OTHER_TENANT, List.of(batchedMember, batchedPlot))));

        SealAuditValuesMigration.Counts first = run();

        assertThat(first.entriesSealed).isGreaterThanOrEqualTo(2);
        assertThat(first.publicationsSealed).isEqualTo(2);
        JsonNode member = storedChanges(memberEntry);
        assertThat(member.toString()).doesNotContain("380671230024").doesNotContain("380501230024")
                .doesNotContain("migrated24");
        assertThat(open(FieldChange.oldValueRef(memberEntry, "phones"), member.get(0).get("old"))).isEqualTo(
                "+380671230024");
        assertThat(open(FieldChange.newValueRef(memberEntry, "phones"), member.get(0).get("new"))).isEqualTo(
                "+380501230024");
        assertThat(member.get(1).get("old").isNull()).isTrue();
        assertThat(open(FieldChange.newValueRef(memberEntry, "email"), member.get(1).get("new"))).isEqualTo(
                "migrated24@example.com");
        assertThat(member.get(2).get("new").asString()).isEqualTo("Ivanovych");
        // only member entries hold a member's personal data
        assertThat(storedChanges(plotEntry).get(0).get("new").asString()).isEqualTo("plot note 24");
        // the other cooperative's entry is sealed with that cooperative's key
        JsonNode other = storedChanges(otherMemberEntry);
        assertThat(other.toString()).doesNotContain("Other street 24").doesNotContain("other_tg_24");
        assertThat(open(OTHER_TENANT, FieldChange.oldValueRef(otherMemberEntry, "postalAddress"),
                other.get(0).get("old"))).isEqualTo("Other street 24");
        assertThat(other.get(0).get("new").isNull()).isTrue();
        assertThat(open(OTHER_TENANT, FieldChange.newValueRef(otherMemberEntry, "telegramUsername"),
                other.get(1).get("new"))).isEqualTo("other_tg_24");

        String serialized = serializedEvent();
        assertThat(serialized).doesNotContain("old note 24").doesNotContain("new note 24");
        AuditEvent resealed = json.readValue(serialized, AuditEvent.class);
        FieldChange notes = resealed.changes().get(0);
        assertThat(notes.sealed()).isTrue();
        assertThat(openText(FieldChange.oldValueRef(pending.eventId(), "notes"), notes.oldValue()))
                .isEqualTo("old note 24");
        assertThat(openText(FieldChange.newValueRef(pending.eventId(), "notes"), notes.newValue()))
                .isEqualTo("new note 24");
        assertThat(resealed.changes().get(1)).isEqualTo(new FieldChange("lastName", "Old", "New"));

        String serializedBatch = serializedEvent(batchPublication);
        assertThat(serializedBatch).doesNotContain("380931230024").contains("batched plot note 24");
        AuditBatch batch = json.readValue(serializedBatch, AuditBatch.class);
        FieldChange viber = batch.entries().get(0).changes().get(0);
        assertThat(viber.sealed()).isTrue();
        assertThat(viber.oldValue()).isNull();
        assertThat(openText(OTHER_TENANT, FieldChange.newValueRef(batchedMember.eventId(), "viberPhone"),
                viber.newValue())).isEqualTo("+380931230024");
        assertThat(batch.entries().get(0).changes().get(1)).isEqualTo(new FieldChange("firstName", null, "Batched"));
        assertThat(batch.entries().get(1).changes()).containsExactly(
                new FieldChange("notes", null, "batched plot note 24"));

        // a second run finds nothing left to seal and changes nothing
        String memberBefore = member.toString();
        String otherBefore = other.toString();
        SealAuditValuesMigration.Counts second = run();
        assertThat(second.entriesSealed).isZero();
        assertThat(second.publicationsSealed).isZero();
        assertThat(storedChanges(memberEntry).toString()).isEqualTo(memberBefore);
        assertThat(storedChanges(otherMemberEntry).toString()).isEqualTo(otherBefore);
        assertThat(serializedEvent()).isEqualTo(serialized);
        assertThat(serializedEvent(batchPublication)).isEqualTo(serializedBatch);
    }

    private SealAuditValuesMigration.Counts run() throws Exception {
        try (Connection connection = ownerJdbc().getDataSource().getConnection()) {
            return migration.seal(connection);
        }
    }

    private void insertEntry(UUID id, String entityType, String changes) {
        insertEntry(DEMO_TENANT, id, entityType, changes);
    }

    private void insertEntry(UUID tenant, UUID id, String entityType, String changes) {
        inTenant(tenant, sql -> sql.update("""
                insert into audit_entry (id, tenant_id, occurred_at, actor_subject, action, entity_type, entity_id,
                                         changes)
                values (?, ?, now(), 'fixture-subject', ?, ?, ?, cast(? as jsonb))
                """, id, tenant, entityType + "_UPDATED", entityType, UUID.randomUUID(), changes));
    }

    private JsonNode storedChanges(UUID id) {
        return json.readTree(ownerJdbc().queryForObject("select cast(changes as text) from audit_entry where id = ?",
                String.class, id));
    }

    private String serializedEvent() {
        return serializedEvent(publication);
    }

    private String serializedEvent(UUID id) {
        return ownerJdbc().queryForObject("select serialized_event from event_publication where id = ?", String.class,
                id);
    }

    private String open(FieldRef ref, JsonNode stored) {
        return open(DEMO_TENANT, ref, stored);
    }

    private String open(UUID tenant, FieldRef ref, JsonNode stored) {
        return openText(tenant, ref, stored.get("enc").asString());
    }

    private String openText(FieldRef ref, String base64) {
        return openText(DEMO_TENANT, ref, base64);
    }

    private String openText(UUID tenant, FieldRef ref, String base64) {
        byte[] sealed = Base64.getDecoder().decode(base64);
        return TenantContext.runAs(tenant, () -> cipher.decrypt(tenant, ref, sealed));
    }
}
