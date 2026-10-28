package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.audit.FieldChange;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /members, PUT /members/{id}, archive and restore: permission, validation, domain rules and audit. */
class MembersWriteTests extends IntegrationTest {

    static final UUID DEMO_CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID DEMO_TREASURER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    PersonalDataCipher cipher;

    private final List<UUID> createdThroughApi = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // first sign-in links the demo members and a refused archive must leave the chairman active
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set keycloak_subject = null, archived_at = null where id = ?", DEMO_CHAIRMAN));
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where id in (?, ?)",
                DEMO_TREASURER, DEMO_MEMBER));
        for (UUID id : createdThroughApi) {
            inTenant(DEMO_TENANT, sql -> sql.update("delete from member where id = ?", id));
        }
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
    }

    // ---- permission -------------------------------------------------------------------------------------------

    @Test
    void holderOfMembersEditCreatesAMember() throws Exception {
        String email = "created-" + tag() + "@example.com";
        Map<String, Object> body = body("Created", "Member", email);
        body.put("middleName", "Test");
        body.put("phones", List.of("067 123 45 67"));
        body.put("postalAddress", "1 Garden Street");
        body.put("notes", "first note");

        CreatedMember created = create(chairman(), body);

        created.actions
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Location", endsWith("/api/v1/members/" + created.id)))
                .andExpect(jsonPath("$.lastName").value("Created"))
                .andExpect(jsonPath("$.middleName").value("Test"))
                .andExpect(jsonPath("$.phones[0]").value("+380671234567"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.deliveryChannel").value("PAPER"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.notes").value("first note"));
        // the cooperative comes from the token, never from the request
        UUID storedTenant = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select tenant_id from member where id = ?", UUID.class, created.id));
        assertThat(storedTenant).isEqualTo(DEMO_TENANT);
    }

    @Test
    void writesAreDeniedWithoutMembersEdit() throws Exception {
        UUID target = newMember(DEMO_TENANT, "target-" + tag() + "@example.com");
        String treasurer = demo("treasurer@demo.local"); // MEMBERS_VIEW but not MEMBERS_EDIT
        String member = demo("member@demo.local");

        for (String token : List.of(treasurer, member)) {
            send(token, post("/api/v1/members"), body("Denied", "Person", null))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, put("/api/v1/members/" + target), body("Denied", "Person", null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, post("/api/v1/members/" + target + "/archive"), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, post("/api/v1/members/" + target + "/restore"), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(lastName(target)).isEqualTo("Test");
        assertThat(archivedAt(target)).isNull();
    }

    @Test
    void writesAreDeniedForThePlatformOperator() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        send(operator, post("/api/v1/members"), body("Denied", "Person", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void writesRequireAToken() throws Exception {
        UUID target = newMember(DEMO_TENANT, "target-" + tag() + "@example.com");
        String id = target.toString();

        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("No", "Token", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(put("/api/v1/members/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("No", "Token", null))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/members/" + id + "/archive")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/members/" + id + "/restore")).andExpect(status().isUnauthorized());
    }

    // ---- validation -------------------------------------------------------------------------------------------

    @Test
    void blankAndMissingNamesAreRequiredFields() throws Exception {
        send(chairman(), post("/api/v1/members"), body("   ", "Person", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("lastName"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        Map<String, Object> noFirstName = new LinkedHashMap<>();
        noFirstName.put("lastName", "Person");
        send(chairman(), post("/api/v1/members"), noFirstName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("firstName"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void tooLongNameIsRejectedByTheContract() throws Exception {
        send(chairman(), post("/api/v1/members"), body("x".repeat(101), "Person", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lastName"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void invalidPhonesAreReportedByPosition() throws Exception {
        Map<String, Object> body = body("Phones", "Person", null);
        body.put("phones", List.of("12345", "0671234567", "not a phone"));
        body.put("viberPhone", "999");

        send(chairman(), post("/api/v1/members"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.code == 'PHONE_INVALID')].field",
                        containsInAnyOrder("phones[0]", "phones[2]", "viberPhone")));
    }

    @Test
    void channelNeedsItsContact() throws Exception {
        for (String[] channel : new String[][] {
                {"EMAIL", "email"}, {"TELEGRAM", "telegramUsername"}, {"VIBER", "viberPhone"}}) {
            Map<String, Object> body = body("Channel", "Person", null);
            body.put("deliveryChannel", channel[0]);

            send(chairman(), post("/api/v1/members"), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value(channel[1]))
                    .andExpect(jsonPath("$.errors[0].code").value("CHANNEL_CONTACT_MISSING"));
        }
    }

    @Test
    void channelWithItsContactIsAccepted() throws Exception {
        Map<String, Object> body = body("Channel", "Telegram", null);
        body.put("deliveryChannel", "TELEGRAM");
        body.put("telegramUsername", "some_user");
        body.put("viberPhone", "050 111 22 33");

        create(chairman(), body).actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryChannel").value("TELEGRAM"))
                .andExpect(jsonPath("$.telegramUsername").value("some_user"))
                .andExpect(jsonPath("$.viberPhone").value("+380501112233"));
    }

    @Test
    void duplicatePhonesAreStoredOnce() throws Exception {
        Map<String, Object> body = body("Duplicates", "Person", null);
        body.put("phones", List.of("0671234567", "+380 67 123 45 67", "050 111 22 33"));

        create(chairman(), body).actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phones", containsInAnyOrder("+380671234567", "+380501112233")));
    }

    // ---- e-mail -----------------------------------------------------------------------------------------------

    @Test
    void emailIsStoredLowerCaseAndMustBeUniqueAmongActiveMembers() throws Exception {
        String email = "mixed-" + tag() + "@Example.COM";
        create(chairman(), body("First", "Holder", email)).actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));

        send(chairman(), post("/api/v1/members"), body("Second", "Holder", email.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("EMAIL_TAKEN"));
    }

    @Test
    void emailOfAnArchivedMemberCanBeReusedButTheArchivedMemberCannotBeRestoredThen() throws Exception {
        String email = "reuse-" + tag() + "@example.com";
        UUID first = create(chairman(), body("First", "Holder", email)).id;
        send(chairman(), post("/api/v1/members/" + first + "/archive"), null).andExpect(status().isOk());

        UUID second = create(chairman(), body("Second", "Holder", email)).id;

        send(chairman(), post("/api/v1/members/" + first + "/restore"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        assertThat(archivedAt(first)).isNotNull();

        send(chairman(), post("/api/v1/members/" + second + "/archive"), null).andExpect(status().isOk());
        send(chairman(), post("/api/v1/members/" + first + "/restore"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void updateCannotTakeTheEmailOfAnotherActiveMember() throws Exception {
        String taken = "taken-" + tag() + "@example.com";
        create(chairman(), body("Holder", "Of", taken));
        UUID other = create(chairman(), body("Other", "Person", "other-" + tag() + "@example.com")).id;

        send(chairman(), put("/api/v1/members/" + other), body("Other", "Person", taken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        // keeping the member's own e-mail is not a conflict
        send(chairman(), put("/api/v1/members/" + other), body("Other", "Renamed", emailOf(other)))
                .andExpect(status().isOk());
    }

    // ---- update, archive, restore -----------------------------------------------------------------------------

    @Test
    void updateReplacesTheFieldsAndIsAudited() throws Exception {
        String email = "update-" + tag() + "@example.com";
        Map<String, Object> original = body("Before", "Person", email);
        original.put("phones", List.of("0671234567"));
        UUID id = create(chairman(), original).id;
        awaitAudit(id, 1);

        Map<String, Object> changed = body("Before", "Person", email);
        changed.put("phones", List.of("050 111 22 33"));
        changed.put("middleName", "Added");
        send(chairman(), put("/api/v1/members/" + id), changed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phones[0]").value("+380501112233"))
                .andExpect(jsonPath("$.middleName").value("Added"));

        List<Map<String, Object>> entries = awaitAudit(id, 2);
        Map<String, Object> update = entries.get(1);
        assertThat(update.get("action")).isEqualTo("MEMBER_UPDATED");
        assertThat(update.get("actor_member_id")).isEqualTo(DEMO_CHAIRMAN);
        assertThat(update.get("entity_type")).isEqualTo("MEMBER");
        assertThat(update.get("entity_label")).isEqualTo("Before Person");
        JsonNode changes = json.readTree((String) update.get("changes"));
        assertThat(changes).hasSize(2);
        // personal values are stored sealed at their place in this entry (ADR 0016); a name is stored as it is
        UUID entry = (UUID) update.get("id");
        assertThat(update.get("changes").toString()).doesNotContain("380671234567").doesNotContain("380501112233");
        assertThat(openAudit(entry, "phones", change(changes, "phones").get("old"), true)).isEqualTo("+380671234567");
        assertThat(openAudit(entry, "phones", change(changes, "phones").get("new"), false)).isEqualTo("+380501112233");
        assertThat(change(changes, "middleName").get("new").asString()).isEqualTo("Added");
        assertThat(entries.get(0).get("action")).isEqualTo("MEMBER_CREATED");
    }

    @Test
    void updateWithoutChangesWritesNoAuditEntry() throws Exception {
        String email = "same-" + tag() + "@example.com";
        UUID id = create(chairman(), body("Same", "Person", email)).id;
        awaitAudit(id, 1);

        send(chairman(), put("/api/v1/members/" + id), body("Same", "Person", email)).andExpect(status().isOk());
        send(chairman(), post("/api/v1/members/" + id + "/archive"), null).andExpect(status().isOk());

        // the archive is the second entry; the no-op update between creation and archive left none
        List<Map<String, Object>> entries = awaitAudit(id, 2);
        assertThat(entries).extracting(e -> e.get("action")).containsExactly("MEMBER_CREATED", "MEMBER_ARCHIVED");
    }

    @Test
    void archiveAndRestoreAreAuditedAndRepeatingThemIsANoOp() throws Exception {
        UUID id = create(chairman(), body("Cycle", "Person", null)).id;

        send(chairman(), post("/api/v1/members/" + id + "/archive"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedAt").exists());
        send(chairman(), post("/api/v1/members/" + id + "/archive"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        send(chairman(), post("/api/v1/members/" + id + "/restore"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        send(chairman(), post("/api/v1/members/" + id + "/restore"), null)
                .andExpect(status().isOk());

        List<Map<String, Object>> entries = awaitAudit(id, 3);
        assertThat(entries).extracting(e -> e.get("action"))
                .containsExactly("MEMBER_CREATED", "MEMBER_ARCHIVED", "MEMBER_RESTORED");
        JsonNode archived = json.readTree((String) entries.get(1).get("changes"));
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).get("field").asString()).isEqualTo("archived");
        assertThat(archived.get(0).get("old").asString()).isEqualTo("false");
        assertThat(archived.get(0).get("new").asString()).isEqualTo("true");
    }

    @Test
    void unknownMemberIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        send(chairman(), put("/api/v1/members/" + unknown), body("No", "One", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), post("/api/v1/members/" + unknown + "/archive"), null).andExpect(status().isNotFound());
        send(chairman(), post("/api/v1/members/" + unknown + "/restore"), null).andExpect(status().isNotFound());
    }

    @Test
    void memberOfAnotherCooperativeIsNotFound() throws Exception {
        send(chairman(), put("/api/v1/members/" + OTHER_MEMBER), body("Hijacked", "Person", null))
                .andExpect(status().isNotFound());
        send(chairman(), post("/api/v1/members/" + OTHER_MEMBER + "/archive"), null)
                .andExpect(status().isNotFound());
        Boolean untouched = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select archived_at is null and last_name <> 'Hijacked' from member where id = ?", Boolean.class,
                OTHER_MEMBER));
        assertThat(untouched).isTrue();
    }

    // ---- last chairman ----------------------------------------------------------------------------------------

    @Test
    void theLastChairmanCannotBeArchived() throws Exception {
        send(chairman(), post("/api/v1/members/" + DEMO_CHAIRMAN + "/archive"), null)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));

        assertThat(archivedAt(DEMO_CHAIRMAN)).isNull();
    }

    @Test
    void aChairmanCanBeArchivedWhenAnotherChairmanRemains() throws Exception {
        UUID second = newMember(DEMO_TENANT, "second-chairman-" + tag() + "@example.com");
        giveBaseRole(DEMO_TENANT, second, "CHAIRMAN");

        send(chairman(), post("/api/v1/members/" + second + "/archive"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
    }

    // ---- notes ------------------------------------------------------------------------------------------------

    @Test
    void editorWithoutMembersViewCannotWriteNotes() throws Exception {
        String editor = editOnlyToken();
        Map<String, Object> body = body("Notes", "Person", null);
        body.put("notes", "secret from an editor");

        CreatedMember created = create(editor, body);
        created.actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", not(hasKey("notes"))));
        assertThat(notes(created.id)).isNull();

        // the chairman adds a note; a later edit by the editor must keep it
        Map<String, Object> withNote = body("Notes", "Person", null);
        withNote.put("notes", "chairman note");
        send(chairman(), put("/api/v1/members/" + created.id), withNote).andExpect(status().isOk());
        Map<String, Object> edited = body("Notes", "Edited", null);
        edited.put("notes", "overwritten by editor");
        send(editor, put("/api/v1/members/" + created.id), edited)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Edited"))
                .andExpect(jsonPath("$", not(hasKey("notes"))));

        assertThat(notes(created.id)).isEqualTo("chairman note");
    }

    @Test
    void editorWithMembersViewReplacesNotes() throws Exception {
        UUID id = create(chairman(), body("Notes", "Viewer", null)).id;
        Map<String, Object> body = body("Notes", "Viewer", null);
        body.put("notes", "new note");

        send(chairman(), put("/api/v1/members/" + id), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("new note"));
        assertThat(notes(id)).isEqualTo("new note");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String chairman() {
        return demo("chairman@demo.local");
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private static Map<String, Object> body(String lastName, String firstName, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastName", lastName);
        body.put("firstName", firstName);
        if (email != null) {
            body.put("email", email);
        }
        return body;
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private record CreatedMember(ResultActions actions, UUID id) {
    }

    /** POST /members; removes the member after the test when it was created. */
    private CreatedMember create(String bearer, Map<String, Object> body) throws Exception {
        ResultActions actions = send(bearer, post("/api/v1/members"), body);
        String response = actions.andReturn().getResponse().getContentAsString();
        UUID id = null;
        if (actions.andReturn().getResponse().getStatus() == 201) {
            id = UUID.fromString(json.readTree(response).get("id").asString());
            createdThroughApi.add(id);
        }
        return new CreatedMember(actions, id);
    }

    /** A token of a member whose only permission is MEMBERS_EDIT (no MEMBERS_VIEW). */
    private String editOnlyToken() {
        String email = "editor-" + tag() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "EditOnly " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'MEMBERS_EDIT')",
                    DEMO_TENANT, role);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    /** Waits until the entity has {@code count} audit entries (written asynchronously) and returns them, oldest first. */
    private List<Map<String, Object>> awaitAudit(UUID entityId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(entityId)).hasSize(count));
        return auditEntries(entityId);
    }

    private List<Map<String, Object>> auditEntries(UUID entityId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select id, action, actor_member_id, entity_type, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where entity_id = ? order by occurred_at, action desc", entityId));
    }

    /** Opens a sealed audit value ({@code {"enc": "<base64>"}}) of the entry with the cooperative's key. */
    private String openAudit(UUID entry, String field, JsonNode stored, boolean old) {
        byte[] sealed = Base64.getDecoder().decode(stored.get("enc").asString());
        return TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT,
                old ? FieldChange.oldValueRef(entry, field) : FieldChange.newValueRef(entry, field), sealed));
    }

    private static JsonNode change(JsonNode changes, String field) {
        for (JsonNode change : changes) {
            if (field.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + field);
    }

    private String lastName(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select last_name from member where id = ?", String.class, id));
    }

    private Object archivedAt(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select archived_at from member where id = ?", id)).get(0).get("archived_at");
    }

    private String notes(UUID id) {
        return decryptedContacts(DEMO_TENANT, id).notes();
    }

    private String emailOf(UUID id) {
        return decryptedContacts(DEMO_TENANT, id).email();
    }
}
