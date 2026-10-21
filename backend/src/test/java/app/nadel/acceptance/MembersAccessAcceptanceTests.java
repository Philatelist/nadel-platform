package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoAccessChecks;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Members and Access Control", driven only through the public HTTP API with
 * the demo data. They complement the per-slice tests with cross-slice journeys and the criteria that no slice owned
 * (demo data, audit immutability, no personal-identity fields, isolation of writes, operator).
 * <p>
 * Each test names the functional-spec criteria it covers as {@code §2.x #n}.
 * <pre>
 * @layer: integration
 * @spec: 002-members-access
 * @regression
 * </pre>
 */
@Tag("regression")
class MembersAccessAcceptanceTests extends IntegrationTest {

    static final UUID DEMO_CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID DEMO_SECRETARY_HOLDER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000009");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private static final String SEEDED_TREASURER_NAME = "Treasurer";
    private static final String SEEDED_TREASURER_DESCRIPTION = "Read access to members, plots and audit";

    private final List<String> problems = new ArrayList<>();
    private final List<UUID> createdMembers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> otherTenantMembers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // first sign-in links demo members and the journeys touch base roles
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null "
                + "where email in ('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local', 'electrician@demo.local')"));
        inTenant(DEMO_TENANT, sql -> sql.update("update member set archived_at = null where id = ?", DEMO_CHAIRMAN));
        for (UUID id : createdMembers) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where member_id = ?", id);
                return sql.update("delete from member where id = ?", id);
            });
        }
        for (UUID id : createdRoles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", id);
                sql.update("delete from role_permission where role_id = ?", id);
                return sql.update("delete from role where id = ?", id);
            });
        }
        for (UUID id : otherTenantMembers) {
            inTenant(OTHER_TENANT, sql -> {
                sql.update("delete from member_role where member_id = ?", id);
                return sql.update("delete from member where id = ?", id);
            });
        }
        // the treasurer's permissions are restored by the tests that change them
    }

    // ---- §2.13 demo data ----------------------------------------------------------------------------------------

    /** §2.13 #1: between 30 and 40 members across two pages for the demo chairman. */
    @Test
    void demoChairmanSeesThirtyToFortyMembersAcrossTwoPages() throws Exception {
        JsonNode first = readJson(doGet(chairman(), "/api/v1/members?page=0&size=20"));
        JsonNode second = readJson(doGet(chairman(), "/api/v1/members?page=1&size=20"));

        assertThat(first.get("totalItems").asInt()).isBetween(30, 40);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("items")).hasSize(20);
        assertThat(second.get("items")).isNotEmpty();
        assertThat(first.get("items").size() + second.get("items").size())
                .isEqualTo(first.get("totalItems").asInt());
    }

    /** §2.13 #2 and §2.4 demo users: five roles; Secretary is given to exactly one member; Electrician has none. */
    @Test
    void demoChairmanSeesTheFiveRolesAndSecretaryIsGivenToOneMember() throws Exception {
        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItems("Chairman", "Treasurer", "Member", "Секретар", "Електрик")))
                .andExpect(jsonPath("$[?(@.name == 'Секретар')].memberCount").value(1))
                .andExpect(jsonPath("$[?(@.name == 'Електрик')].permissions[0]").isEmpty());
        mvc.perform(get("/api/v1/members/" + DEMO_SECRETARY_HOLDER).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[*].name", hasItem("Секретар")));
    }

    // ---- the whole feature as one journey -----------------------------------------------------------------------

    /**
     * §2.3 #1, #4, #5, #6; §2.4 #1, #4; §2.6 #1, #6, #7, #8; §2.8 #2, #3; §2.9 #1-#4, #5; §2.11 #4 (mutation by a
     * newly empowered person).
     * The chairman creates a custom role and a member, gives the member Treasurer and the custom role, the person
     * signs in, edits a member, the e-mail changes, the member is archived and restored, the role is released and
     * deleted - and the audit log tells the whole story.
     */
    @Test
    void chairmanOnboardsASecretaryAndTheAuditLogTellsTheWholeStory() throws Exception {
        String tag = tag();
        String roleName = "Secretary-" + tag;
        String email = "Onboard-" + tag + "@Example.com";

        // custom role appears in the list with exactly its permissions
        UUID role = idOf(doPost(chairman(), "/api/v1/roles", Map.of("name", roleName,
                "permissions", List.of("MEMBERS_VIEW", "MEMBERS_EDIT"))).andExpect(status().isCreated()));
        createdRoles.add(role);
        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$[?(@.id == '" + role + "')].permissions[*]",
                        containsInAnyOrder("MEMBERS_VIEW", "MEMBERS_EDIT")));

        // member created: local phone is normalised, no channel chosen means Paper
        Map<String, Object> input = memberInput("Onboard" + tag, "Person", email);
        input.put("phones", List.of("067 123 45 67"));
        UUID member = idOf(doPost(chairman(), "/api/v1/members", input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.phones[0]").value("+380671234567"))
                .andExpect(jsonPath("$.deliveryChannel").value("PAPER")));
        createdMembers.add(member);

        // gets Treasurer (audit view) and the custom role (edit members)
        UUID treasurerRole = baseRoleId("TREASURER");
        doPut(chairman(), "/api/v1/members/" + member + "/roles", Map.of("roleIds", List.of(treasurerRole, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[*].name", containsInAnyOrder("Treasurer", roleName)));

        // sign-in, e-mail case ignored; permissions are the union of both roles
        String subject = UUID.randomUUID().toString();
        String person = TestTokens.bearer(DEMO_TENANT, subject, email.toUpperCase());
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, person))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "MEMBERS_VIEW", "MEMBERS_EDIT", "AUDIT_VIEW", "PLOTS_VIEW", "TARIFFS_VIEW")));
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, person)).andExpect(status().isOk());

        // the new editor changes the postal address and the phone of the member
        Map<String, Object> changed = memberInput("Onboard" + tag, "Person", email);
        changed.put("phones", List.of("050 111 22 33"));
        changed.put("postalAddress", "5 New Street");
        doPut(person, "/api/v1/members/" + member, changed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postalAddress").value("5 New Street"))
                .andExpect(jsonPath("$.phones[0]").value("+380501112233"));

        // the e-mail is edited by an admin; the person is still the same member
        String newEmail = "moved-" + tag + "@example.com";
        Map<String, Object> moved = memberInput("Onboard" + tag, "Person", newEmail);
        moved.put("phones", List.of("050 111 22 33"));
        moved.put("postalAddress", "5 New Street");
        doPut(chairman(), "/api/v1/members/" + member, moved).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(DEMO_TENANT, subject, newEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));

        // archive and restore, as seen by the person
        doPost(chairman(), "/api/v1/members/" + member + "/archive", null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, person))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
        mvc.perform(get("/api/v1/members").param("includeArchived", "false").param("q", "Onboard" + tag)
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/members").param("includeArchived", "true").param("q", "Onboard" + tag)
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].archived").value(true));
        doPost(chairman(), "/api/v1/members/" + member + "/restore", null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/members").param("q", "Onboard" + tag).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$.items", hasSize(1)));

        // a role held by a member cannot be deleted; once released it can
        doDelete(chairman(), "/api/v1/roles/" + role)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_IN_USE"))
                .andExpect(jsonPath("$.detail").value("This role is assigned to members and cannot be deleted."));
        doPut(chairman(), "/api/v1/members/" + member + "/roles", Map.of("roleIds", List.of(treasurerRole)))
                .andExpect(status().isOk());
        doDelete(chairman(), "/api/v1/roles/" + role).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$[*].id", not(hasItem(role.toString()))));

        // the audit log, read by the treasurer, within seconds shows every step with actor and old/new values
        String treasurer = demo("treasurer@demo.local");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode memberLog = readJson(doGet(treasurer, "/api/v1/audit?entityId=" + member + "&size=50"));
            List<String> actions = texts(memberLog.get("items"), "action");
            assertThat(actions).containsExactlyInAnyOrder("MEMBER_CREATED", "MEMBER_ROLES_CHANGED",
                    "MEMBER_UPDATED", "MEMBER_UPDATED", "MEMBER_ARCHIVED", "MEMBER_RESTORED", "MEMBER_ROLES_CHANGED");
            // newest first
            assertThat(actions.get(0)).isEqualTo("MEMBER_ROLES_CHANGED");
            JsonNode phoneUpdate = find(memberLog.get("items"), "MEMBER_UPDATED", "phones");
            assertThat(phoneUpdate.get("actorName").asString()).isEqualTo("Onboard" + tag + " Person");
            assertThat(phoneUpdate.get("occurredAt").asString()).isNotBlank();
            assertThat(phoneUpdate.get("entityLabel").asString()).isEqualTo("Onboard" + tag + " Person");
            JsonNode phone = field(phoneUpdate, "phones");
            assertThat(phone.get("oldValue").asString()).isEqualTo("+380671234567");
            assertThat(phone.get("newValue").asString()).isEqualTo("+380501112233");
            // the person who became an editor is the author of that change; the chairman created the member
            assertThat(find(memberLog.get("items"), "MEMBER_CREATED", "lastName").get("actorName").asString())
                    .isEqualTo("Коваленко Олександр Петрович");
            JsonNode rolesChange = find(memberLog.get("items"), "MEMBER_ROLES_CHANGED", "roles");
            assertThat(field(rolesChange, "roles").get("oldValue").asString()).isNotBlank();

            List<String> roleActions = texts(readJson(doGet(treasurer, "/api/v1/audit?entityId=" + role))
                    .get("items"), "action");
            assertThat(roleActions).containsExactly("ROLE_DELETED", "ROLE_CREATED");
        });
    }

    /** §2.3 #2, #3 (case-insensitive duplicate, empty name) and §2.6 #3-#5 in one pass through the public contract. */
    @Test
    void validationMessagesComeBackAsFieldCodesAndNothingIsSaved() throws Exception {
        doPost(chairman(), "/api/v1/roles", Map.of("name", "treasurer", "permissions", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        doPost(chairman(), "/api/v1/roles", Map.of("name", "", "permissions", List.of()))
                .andExpect(status().isBadRequest());

        long before = memberCount();
        doPost(chairman(), "/api/v1/members", Map.of("firstName", "Only"))
                .andExpect(status().isBadRequest());
        Map<String, Object> badPhone = memberInput("Bad", "Phone", null);
        badPhone.put("phones", List.of("12345"));
        doPost(chairman(), "/api/v1/members", badPhone)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PHONE_INVALID"));
        Map<String, Object> telegram = memberInput("No", "Telegram", null);
        telegram.put("deliveryChannel", "TELEGRAM");
        doPost(chairman(), "/api/v1/members", telegram)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CHANNEL_CONTACT_MISSING"));
        assertThat(memberCount()).isEqualTo(before);
    }

    // ---- §2.2 base roles - permission change through the whole stack --------------------------------------------

    /** §2.2 #3: unticking "View audit log" for Treasurer takes the audit log from the treasurer's next sign-in. */
    @Test
    void untickingAuditViewForTreasurerRemovesTheAuditLogFromTheTreasurer() throws Exception {
        UUID treasurerRole = baseRoleId("TREASURER");
        String treasurer = demo("treasurer@demo.local");
        // a role PUT is a full replacement: send the stored name and description back so only permissions change
        JsonNode seeded = treasurerRoleBody();
        String name = seeded.get("name").asString();
        assertThat(name).isEqualTo(SEEDED_TREASURER_NAME);
        assertThat(seeded.get("description").asString()).isEqualTo(SEEDED_TREASURER_DESCRIPTION);
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, treasurer)).andExpect(status().isOk());
        try {
            doPut(chairman(), "/api/v1/roles/" + treasurerRole, Map.of("name", SEEDED_TREASURER_NAME,
                    "description", SEEDED_TREASURER_DESCRIPTION,
                    "permissions", List.of("MEMBERS_VIEW", "PLOTS_VIEW", "TARIFFS_VIEW"))).andExpect(status().isOk());

            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, treasurer))
                    .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "TARIFFS_VIEW")));
            mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, treasurer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        } finally {
            doPut(chairman(), "/api/v1/roles/" + treasurerRole, Map.of("name", SEEDED_TREASURER_NAME,
                    "description", SEEDED_TREASURER_DESCRIPTION,
                    "permissions", List.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, treasurer)).andExpect(status().isOk());

        // the shared demo role is back to its seeded state, so no later test class sees a leftover
        JsonNode restored = treasurerRoleBody();
        assertThat(restored.get("name").asString()).isEqualTo(SEEDED_TREASURER_NAME);
        assertThat(restored.path("description").asString()).isEqualTo(SEEDED_TREASURER_DESCRIPTION);
        List<String> permissions = new ArrayList<>();
        restored.get("permissions").forEach(node -> permissions.add(node.asString()));
        assertThat(permissions).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW");
    }

    /**
     * Feature 004: names are labels, never permissions. All three base roles are renamed through the API, then the
     * permission checks of {@code MeEndpointTests} run unchanged (chairman, treasurer, member and the electrician with
     * a custom role): the same permissions in {@code /me} and the same allowed and denied endpoints. The seeded names and
     * flags are put back afterwards.
     */
    @Test
    void renamingEveryBaseRoleDoesNotChangeWhoCanDoWhat() throws Exception {
        String tag = tag();
        Map<String, String> newNames = Map.of("CHAIRMAN", "Head-" + tag, "TREASURER", "Bookkeeper-" + tag,
                "MEMBER", "Plotholder-" + tag);
        Map<String, JsonNode> seeded = new LinkedHashMap<>();
        for (JsonNode role : readJson(doGet(chairman(), "/api/v1/roles"))) {
            if (role.hasNonNull("baseCode")) {
                seeded.put(role.get("baseCode").asString(), role);
            }
        }
        assertThat(seeded.keySet()).containsExactlyInAnyOrder("CHAIRMAN", "TREASURER", "MEMBER");
        DemoAccessChecks.all(mvc);
        try {
            for (Map.Entry<String, String> rename : newNames.entrySet()) {
                JsonNode before = seeded.get(rename.getKey());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", rename.getValue());
                body.put("description", before.get("description").asString());
                List<String> permissions = new ArrayList<>();
                before.get("permissions").forEach(p -> permissions.add(p.asString()));
                body.put("permissions", permissions);
                doPut(chairman(), "/api/v1/roles/" + before.get("id").asString(), body)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.name").value(rename.getValue()))
                        .andExpect(jsonPath("$.nameCustomized").value(true));
            }

            // the new names are what people see ...
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, demo("treasurer@demo.local")))
                    .andExpect(jsonPath("$.roles[0].name").value(newNames.get("TREASURER")))
                    .andExpect(jsonPath("$.roles[0].nameCustomized").value(true));
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, demo("electrician@demo.local")))
                    .andExpect(jsonPath("$.roles[?(@.baseCode == 'MEMBER')].name").value(newNames.get("MEMBER")))
                    .andExpect(jsonPath("$.roles[?(@.name == 'Електрик')].nameCustomized").value(true));
            // ... and nothing else changed
            DemoAccessChecks.all(mvc);
        } finally {
            restoreSeededBaseRoles(seeded);
        }

        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$[?(@.baseCode == 'CHAIRMAN')].name").value("Chairman"))
                .andExpect(jsonPath("$[?(@.baseCode == 'TREASURER')].name").value("Treasurer"))
                .andExpect(jsonPath("$[?(@.baseCode == 'MEMBER')].name").value("Member"))
                .andExpect(jsonPath("$[?(@.baseCode == 'CHAIRMAN')].nameCustomized").value(false))
                .andExpect(jsonPath("$[?(@.baseCode == 'TREASURER')].nameCustomized").value(false))
                .andExpect(jsonPath("$[?(@.baseCode == 'MEMBER')].nameCustomized").value(false))
                .andExpect(jsonPath("$[?(@.baseCode == 'CHAIRMAN')].descriptionCustomized").value(false))
                .andExpect(jsonPath("$[?(@.baseCode == 'TREASURER')].descriptionCustomized").value(false))
                .andExpect(jsonPath("$[?(@.baseCode == 'MEMBER')].descriptionCustomized").value(false));
        DemoAccessChecks.all(mvc);
    }

    /** Puts the base roles of the demo cooperative back to the state read before the test: name, flag, permissions. */
    private void restoreSeededBaseRoles(Map<String, JsonNode> seeded) {
        inTenant(DEMO_TENANT, sql -> {
            for (Map.Entry<String, JsonNode> entry : seeded.entrySet()) {
                sql.update("update role set name = ?, name_customized = false, description = ?, "
                                + "description_customized = false where base_code = ?",
                        entry.getValue().get("name").asString(), entry.getValue().get("description").asString(),
                        entry.getKey());
                sql.update("delete from role_permission where role_id = (select id from role where base_code = ?)",
                        entry.getKey());
                entry.getValue().get("permissions").forEach(permission -> sql.update(
                        "insert into role_permission (tenant_id, role_id, permission) "
                                + "select tenant_id, id, ? from role where base_code = ?",
                        permission.asString(), entry.getKey()));
            }
            return null;
        });
    }

    private JsonNode treasurerRoleBody() throws Exception {
        for (JsonNode role : readJson(doGet(chairman(), "/api/v1/roles"))) {
            JsonNode baseCode = role.get("baseCode");
            if (baseCode != null && "TREASURER".equals(baseCode.asString())) {
                return role;
            }
        }
        throw new AssertionError("the Treasurer base role is missing");
    }

    // ---- §2.9 audit log immutability ------------------------------------------------------------------------------

    /** §2.9 #8 (and "entries can never be changed or removed"): the API offers no write on the audit log. */
    @Test
    void theAuditApiOffersNoWayToChangeOrRemoveAnEntry() throws Exception {
        UUID entry = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from audit_entry limit 1", UUID.class));
        // make sure there is something to attack
        UUID anyEntry = entry != null ? entry : UUID.randomUUID();
        for (String path : List.of("/api/v1/audit", "/api/v1/audit/" + anyEntry)) {
            for (MockHttpServletRequestBuilder request : List.of(MockMvcRequestBuilders.post(path), MockMvcRequestBuilders.put(path),
                    MockMvcRequestBuilders.patch(path), MockMvcRequestBuilders.delete(path))) {
                int code = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, chairman())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus();
                assertThat(code).as("%s %s", request.buildRequest(new org.springframework.mock.web.MockServletContext())
                        .getMethod(), path).isBetween(400, 499);
            }
        }
        Integer remaining = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from audit_entry where id = ?", Integer.class, anyEntry));
        assertThat(remaining).isEqualTo(entry != null ? 1 : 0);
    }

    // ---- §2.6 #10: no identity-document data -------------------------------------------------------------------------

    /** §2.6 #10: the contract and the card carry no tax number, passport or date of birth. */
    @Test
    void noTaxPassportOrBirthDataExistsInTheContractOrOnTheCard() throws Exception {
        String contract = Files.readString(Path.of("..", "api", "openapi.yaml")).toLowerCase();
        for (String word : List.of("tax", "passport", "birth", "dob", "inn")) {
            assertThat(contract).as("openapi mentions '%s'", word).doesNotContainPattern("\\b" + word + "\\w*\\b");
        }
        JsonNode card = readJson(doGet(chairman(), "/api/v1/members/" + DEMO_CHAIRMAN));
        card.propertyNames().forEach(name -> assertThat(name.toLowerCase())
                .doesNotContain("tax", "passport", "birth"));
        List<String> columns = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select column_name from information_schema.columns where table_name = 'member'", String.class));
        assertThat(columns).noneMatch(c -> c.contains("tax") || c.contains("passport") || c.contains("birth"));
    }

    // ---- §2.12 isolation of writes -----------------------------------------------------------------------------------

    /**
     * §2.12 #1, #2 and "never changeable by people of another cooperative": even a chairman of the other cooperative
     * cannot read, edit, archive or re-role a demo member, and sees neither demo roles nor demo audit entries.
     */
    @Test
    void aChairmanOfTheOtherCooperativeCannotReachAnythingOfTheDemoCooperative() throws Exception {
        String tag = tag();
        UUID otherChairman = newMember(OTHER_TENANT, "chair-" + tag + "@other.local");
        otherTenantMembers.add(otherChairman);
        giveBaseRole(OTHER_TENANT, otherChairman, "CHAIRMAN");
        String other = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "chair-" + tag + "@other.local");
        UUID demoMember = DEMO_SECRETARY_HOLDER;
        UUID demoRole = baseRoleId("TREASURER");

        mvc.perform(get("/api/v1/members/" + demoMember).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        doPut(other, "/api/v1/members/" + demoMember, memberInput("Hijacked", "Member", null))
                .andExpect(status().isNotFound());
        doPost(other, "/api/v1/members/" + demoMember + "/archive", null).andExpect(status().isNotFound());
        doPut(other, "/api/v1/members/" + demoMember + "/roles", Map.of("roleIds", List.of(demoRole)))
                .andExpect(status().isNotFound());
        doPut(other, "/api/v1/roles/" + demoRole, Map.of("name", "Hijacked", "permissions", List.of()))
                .andExpect(status().isNotFound());
        doDelete(other, "/api/v1/roles/" + demoRole).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(jsonPath("$[*].id", not(hasItem(demoRole.toString()))));
        JsonNode members = readJson(doGet(other, "/api/v1/members?size=100"));
        assertThat(texts(members.get("items"), "email")).noneMatch(e -> e != null && e.endsWith("@demo.local"));
        assertThat(texts(members.get("items"), "id")).doesNotContain(demoMember.toString());
        assertThat(readJson(doGet(other, "/api/v1/audit?entityId=" + demoMember)).get("items")).isEmpty();

        // and the demo member is unchanged
        mvc.perform(get("/api/v1/members/" + demoMember).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(jsonPath("$.lastName").value("Поліщук"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.roles[*].name", hasItem("Секретар")));
    }

    // ---- §2.4 #5: platform operator --------------------------------------------------------------------------------------

    /** §2.4 #5: the operator belongs to no cooperative and reaches no cooperative data through any endpoint. */
    @Test
    void thePlatformOperatorBelongsToNoCooperativeAndSeesNoCooperativeData() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformOperator").value(true))
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.permissions").isEmpty());
        for (String path : List.of("/api/v1/members", "/api/v1/roles", "/api/v1/permissions", "/api/v1/audit")) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, operator))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/members/" + DEMO_CHAIRMAN).header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isNotFound());
    }

    // ---- §2.11 #4: refusal regardless of the screen ---------------------------------------------------------------------

    /**
     * §2.11 #4: a person without "Edit members" is refused every write, and "Manage roles" is needed on top for
     * roles: the member is unchanged afterwards.
     */
    @Test
    void writesWithoutThePermissionAreRefusedAndLeaveTheMemberUnchanged() throws Exception {
        UUID target = newMember(DEMO_TENANT, "target-" + tag() + "@example.com");
        String viewer = demo("treasurer@demo.local");
        String ordinary = demo("member@demo.local");

        for (String token : List.of(viewer, ordinary)) {
            doPut(token, "/api/v1/members/" + target, memberInput("Changed", "Name", null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            doPut(token, "/api/v1/members/" + target + "/roles", Map.of("roleIds", List.of(baseRoleId("CHAIRMAN"))))
                    .andExpect(status().isForbidden());
        }
        String lastName = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select last_name from member where id = ?", String.class, target));
        Integer roleCount = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from member_role where member_id = ?", Integer.class, target));
        assertThat(lastName).isEqualTo("Test");
        assertThat(roleCount).isZero();
    }

    /** §2.8 #4: an editor without "Manage roles" edits fields but cannot change roles. */
    @Test
    void anEditorWithoutManageRolesEditsFieldsButNotRoles() throws Exception {
        UUID role = idOf(doPost(chairman(), "/api/v1/roles", Map.of("name", "Editor-" + tag(),
                "permissions", List.of("MEMBERS_VIEW", "MEMBERS_EDIT"))).andExpect(status().isCreated()));
        createdRoles.add(role);
        String email = "editor-" + tag() + "@example.com";
        UUID editor = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, editor, "MEMBER");
        doPut(chairman(), "/api/v1/members/" + editor + "/roles", Map.of("roleIds", List.of(role)))
                .andExpect(status().isOk());
        UUID target = newMember(DEMO_TENANT, "target-" + tag() + "@example.com");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);

        Map<String, Object> input = memberInput("Test", "Member", "t-" + tag() + "@example.com");
        input.put("postalAddress", "7 Edited Street");
        doPut(token, "/api/v1/members/" + target, input).andExpect(status().isOk())
                .andExpect(jsonPath("$.postalAddress").value("7 Edited Street"));
        doPut(token, "/api/v1/members/" + target + "/roles", Map.of("roleIds", List.of(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    // ---- errors as the browser receives them ---------------------------------------------------------------------------------

    /**
     * The web client sends {@code Accept: application/json} on every call and shows messages by the Problem Details
     * {@code code} (§2.3 #5, §2.2 #4, §2.11 #4): an error must reach it as its own status and code, not as a 406.
     */
    @Test
    void businessErrorsReachAClientThatAcceptsJson() throws Exception {
        UUID role = idOf(doPost(chairman(), "/api/v1/roles", Map.of("name", "Json-" + tag(),
                "permissions", List.of())).andExpect(status().isCreated()));
        createdRoles.add(role);
        UUID holder = newMember(DEMO_TENANT, "json-" + tag() + "@example.com");
        doPut(chairman(), "/api/v1/members/" + holder + "/roles", Map.of("roleIds", List.of(role)))
                .andExpect(status().isOk());

        expectProblem(MockMvcRequestBuilders.delete("/api/v1/roles/" + role), chairman(), 409, "ROLE_IN_USE");
        expectProblem(MockMvcRequestBuilders.delete("/api/v1/roles/" + baseRoleId("MEMBER")), chairman(), 409,
                "BASE_ROLE_IMMUTABLE");
        expectProblem(MockMvcRequestBuilders.delete("/api/v1/roles/" + UUID.randomUUID()), chairman(), 404,
                "NOT_FOUND");
        expectProblem(MockMvcRequestBuilders.delete("/api/v1/roles/" + role), demo("treasurer@demo.local"), 403,
                "PERMISSION_DENIED");
        expectProblem(MockMvcRequestBuilders.post("/api/v1/members/" + UUID.randomUUID() + "/archive"), chairman(),
                404, "NOT_FOUND");
        expectProblem(MockMvcRequestBuilders.post("/api/v1/members/" + holder + "/restore")
                .contentType(MediaType.APPLICATION_JSON), demo("treasurer@demo.local"), 403, "PERMISSION_DENIED");
        assertThat(problems).as("every case, in order; null means fine").containsOnlyNulls();
    }

    private void expectProblem(MockHttpServletRequestBuilder request, String bearer, int status, String code)
            throws Exception {
        int actual = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer).accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
        problems.add(actual == status ? null : "expected " + status + " " + code + " but got " + actual);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private static String chairman() {
        return demo("chairman@demo.local");
    }

    private static Map<String, Object> memberInput(String lastName, String firstName, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastName", lastName);
        body.put("firstName", firstName);
        if (email != null) {
            body.put("email", email);
        }
        return body;
    }

    private UUID baseRoleId(String code) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = ?", UUID.class, code));
    }

    private long memberCount() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select count(*) from member", Long.class));
    }

    private ResultActions doGet(String bearer, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions doPost(String bearer, String path, Object body) throws Exception {
        return send(bearer, MockMvcRequestBuilders.post(path), body);
    }

    private ResultActions doPut(String bearer, String path, Object body) throws Exception {
        return send(bearer, MockMvcRequestBuilders.put(path), body);
    }

    private ResultActions doDelete(String bearer, String path) throws Exception {
        return send(bearer, MockMvcRequestBuilders.delete(path), null);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return json.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID idOf(ResultActions created) throws Exception {
        return UUID.fromString(json.readTree(created.andReturn().getResponse().getContentAsString())
                .get("id").asString());
    }

    private static List<String> texts(JsonNode items, String property) {
        List<String> result = new ArrayList<>();
        items.forEach(n -> result.add(n.hasNonNull(property) ? n.get(property).asString() : null));
        return result;
    }

    private static JsonNode field(JsonNode entry, String field) {
        for (JsonNode change : entry.get("changes")) {
            if (field.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + field);
    }

    private static JsonNode find(JsonNode items, String action, String changedField) {
        for (JsonNode item : items) {
            if (action.equals(item.get("action").asString())) {
                for (JsonNode change : item.get("changes")) {
                    if (changedField.equals(change.get("field").asString())) {
                        return item;
                    }
                }
            }
        }
        throw new AssertionError("no " + action + " entry changing " + changedField);
    }
}
