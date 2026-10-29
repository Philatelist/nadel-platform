package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.members.MemberImport;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 014, 2.1: the personal values of a member's audit events are never stored in the clear, neither in the outbox
 * ({@code event_publication}, inspected while the publication is still pending) nor in {@code audit_entry}. The
 * audit writer is held for this test's events so their publications stay pending; they are then released and
 * resubmitted. Read as the schema owner ({@code row_to_json(t)::text}), so no policy hides a row.
 */
class AuditPlaintextScanTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    MemberImport memberImport;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    IncompleteEventPublications incomplete;

    @MockitoSpyBean
    AuditWriter writer;

    private final String tag = "scan" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final List<UUID> members = new ArrayList<>();
    private volatile boolean holding = true;

    @AfterEach
    void cleanUp() {
        holding = false;
        incomplete.resubmitIncompletePublications(this::ours);
        for (UUID member : members) {
            ownerJdbc().update("delete from member where id = ?", member);
        }
    }

    @Test
    void aMemberCreatedAndUpdatedThroughTheApiLeavesNoContactInTheClear() throws Exception {
        holdTheWriter();
        UUID chairmanMember = newMember(DEMO_TENANT, "chairman-" + tag + "@demo.local");
        giveBaseRole(DEMO_TENANT, chairmanMember, "CHAIRMAN");
        String chairman = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(),
                memberEmail(DEMO_TENANT, chairmanMember));
        Contacts before = contacts("a");
        Contacts after = contacts("b");

        String created = mvc.perform(post("/api/v1/members").header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(before.body(tag))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID member = UUID.fromString(json.readTree(created).path("id").asString());
        members.add(member);
        mvc.perform(put("/api/v1/members/" + member).header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(after.body(tag))))
                .andExpect(status().isOk());

        // pending in the outbox: the creation and the update, sealed
        List<String> pending = pendingPublications(member);
        assertThat(pending).hasSize(2).allSatisfy(row -> assertThat(row).contains("\\\"sealed\\\":true"));
        assertNoContact(pending, before, after);

        release();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(entries(member)).hasSize(2));
        List<String> stored = entries(member);
        assertThat(String.join("", stored)).contains("\"enc\"");
        assertNoContact(stored, before, after);
        // the listener commits the entries before the registry marks its publication completed, in a later step
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pendingPublications(member)).isEmpty());
    }

    @Test
    void anImportedMemberLeavesNoContactInTheClear() {
        holdTheWriter();
        Contacts contacts = contacts("c");
        List<MemberImportRow> rows = List.of(new MemberImportRow(2, "S-1", "Scanned" + tag, "Imported", null,
                List.of(contacts.phone()), contacts.email(), contacts.postalAddress(), "TELEGRAM",
                contacts.telegramUsername(), contacts.viberPhone()));
        UUID member = TenantContext.runAs(DEMO_TENANT, () ->
                new TransactionTemplate(transactions).execute(status -> {
                    MemberImportPlan plan = memberImport.plan(DEMO_TENANT, UUID.randomUUID(), rows);
                    memberImport.lock(plan);
                    List<AuditEvent> events = memberImport.apply(DEMO_TENANT, rows, plan,
                            new Actor(null, "scan-test", "Import"));
                    // as the import commit publishes them
                    AuditBatch.of(DEMO_TENANT, events).forEach(publisher::publishEvent);
                    return plan.items().getFirst().memberId();
                }));
        members.add(member);

        List<String> pending = pendingPublications(member);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst()).contains("app.nadel.audit.AuditBatch").contains("\\\"sealed\\\":true");
        assertNoContact(pending, contacts);

        release();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(entries(member)).hasSize(1));
        assertThat(entries(member).getFirst()).contains("\"enc\"");
        assertNoContact(entries(member), contacts);
    }

    /** Unique contact values of this test, one set per letter. */
    private Contacts contacts(String set) {
        return new Contacts("+38067" + digits(7), set + "-" + tag + "@example.com", set + " street " + tag,
                set + "_" + tag, "+38093" + digits(7), set + " note " + tag);
    }

    private record Contacts(String phone, String email, String postalAddress, String telegramUsername,
            String viberPhone, String notes) {

        Map<String, Object> body(String tag) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lastName", "Scanned" + tag);
            body.put("firstName", "Person");
            body.put("phones", List.of(phone));
            body.put("email", email);
            body.put("postalAddress", postalAddress);
            body.put("deliveryChannel", "TELEGRAM");
            body.put("telegramUsername", telegramUsername);
            body.put("viberPhone", viberPhone);
            body.put("notes", notes);
            return body;
        }

        /** What must never appear: the values, and the phones without their plus sign. */
        List<String> markers() {
            return List.of(phone.substring(1), email, postalAddress, telegramUsername, viberPhone.substring(1), notes);
        }
    }

    private static void assertNoContact(List<String> rows, Contacts... sets) {
        String all = String.join("\n", rows);
        for (Contacts set : sets) {
            for (String marker : set.markers()) {
                assertThat(all).doesNotContain(marker);
            }
        }
    }

    /** Holds the audit writer for this test's events: their publications stay pending until {@link #release}. */
    private void holdTheWriter() {
        doAnswer(invocation -> {
            AuditEvent event = invocation.getArgument(0);
            if (held(event)) {
                throw new IllegalStateException("held by the test");
            }
            return invocation.callRealMethod();
        }).when(writer).write(any());
        doAnswer(invocation -> {
            List<AuditEvent> events = invocation.getArgument(0);
            if (events.stream().anyMatch(this::held)) {
                throw new IllegalStateException("held by the test");
            }
            return invocation.callRealMethod();
        }).when(writer).writeAll(any());
    }

    private boolean held(AuditEvent event) {
        return holding && event.entityLabel() != null && event.entityLabel().contains(tag);
    }

    private void release() {
        holding = false;
        incomplete.resubmitIncompletePublications(this::ours);
    }

    private boolean ours(EventPublication publication) {
        Object event = publication.getEvent();
        if (event instanceof AuditEvent single) {
            return single.entityLabel() != null && single.entityLabel().contains(tag);
        }
        return event instanceof AuditBatch batch
                && batch.entries().stream().anyMatch(e -> e.entityLabel() != null && e.entityLabel().contains(tag));
    }

    /** Every pending publication that mentions the member, as the owner reads the row. */
    private List<String> pendingPublications(UUID member) {
        return ownerJdbc().queryForList("select row_to_json(t)::text from event_publication t "
                + "where completion_date is null and serialized_event like ?", String.class, "%" + member + "%");
    }

    /** The audit entries of the member, as the owner reads the rows. */
    private List<String> entries(UUID member) {
        return ownerJdbc().queryForList("select row_to_json(t)::text from audit_entry t where entity_id = ?",
                String.class, member);
    }

    private static String digits(int count) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < count; i++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        return digits.toString();
    }
}
