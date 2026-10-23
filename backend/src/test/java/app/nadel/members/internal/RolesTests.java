package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
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

/**
 * GET /permissions, /roles, POST/PUT/DELETE /roles and PUT /members/{id}/roles: permission (allowed, denied, 401),
 * name rules, base-role rules, in-use rule, last chairman, audit events and "a permission change applies on the next
 * request".
 */
class RolesTests extends IntegrationTest {

    static final UUID DEMO_CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private record BaseRoleSeed(String code, String name, String description, List<String> permissions) {
    }

    /** What {@code seed_base_roles} (V2, V7, V10, V12, V13, V19) writes for the demo cooperative. */
    private static final List<BaseRoleSeed> SEEDED_BASE_ROLES = List.of(
            new BaseRoleSeed("CHAIRMAN", "Chairman", "Full access", List.of("MEMBERS_VIEW", "MEMBERS_EDIT",
                    "ROLES_MANAGE", "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN",
                    "SETTINGS_MANAGE", "TARIFFS_VIEW", "TARIFFS_MANAGE", "METERS_VIEW", "METERS_MANAGE",
                    "READINGS_ENTER", "READINGS_APPROVE", "CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE",
                    "PERIODS_CLOSE", "RECALCULATION_RUN")),
            new BaseRoleSeed("TREASURER", "Treasurer", "Read access to members, plots and audit",
                    List.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW",
                            "CHARGES_VIEW")),
            new BaseRoleSeed("MEMBER", "Member", "Own data only", List.of()));

    private final List<UUID> createdRoles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // first sign-in links the demo members; some tests change base roles
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null "
                + "where email in ('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local')"));
        for (UUID role : createdRoles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        restoreBaseRoles(); // last: its check must not leave custom roles behind
    }

    /**
     * Puts the three base roles of the demo cooperative back to what {@code seed_base_roles} writes (name,
     * name_customized, description, description_customized, permissions) - the database is shared by every test class - and checks that it worked.
     */
    private void restoreBaseRoles() {
        inTenant(DEMO_TENANT, sql -> {
            for (BaseRoleSeed seed : SEEDED_BASE_ROLES) {
                sql.update("update role set name = ?, name_customized = false, description = ?, "
                        + "description_customized = false where base_code = ?",
                        seed.name(), seed.description(), seed.code());
                sql.update("delete from role_permission where role_id = (select id from role where base_code = ?)",
                        seed.code());
                for (String permission : seed.permissions()) {
                    sql.update("insert into role_permission (tenant_id, role_id, permission) "
                            + "select tenant_id, id, ? from role where base_code = ?", permission, seed.code());
                }
            }
            return null;
        });
        List<BaseRoleSeed> actual = inTenant(DEMO_TENANT, sql -> sql.query(
                "select r.base_code, r.name, r.description, "
                        + "coalesce(array_to_string(array(select permission from role_permission "
                        + "where role_id = r.id order by permission), ','), '') as permissions "
                        + "from role r where r.base_code is not null order by r.base_code",
                (rs, i) -> new BaseRoleSeed(rs.getString("base_code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("permissions").isEmpty() ? List.of()
                                : List.of(rs.getString("permissions").split(",")))));
        List<String> stillFlagged = inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select base_code from role where base_code is not null and (name_customized or description_customized)",
                String.class));
        assertThat(stillFlagged).as("no base role is left flagged as renamed or re-described").isEmpty();
        assertThat(actual).as("base roles restored to the seeded state").containsExactlyInAnyOrderElementsOf(
                SEEDED_BASE_ROLES.stream().map(s -> new BaseRoleSeed(s.code(), s.name(), s.description(),
                        s.permissions().stream().sorted().toList())).toList());
    }

    // ---- permission: allowed, denied, 401 -----------------------------------------------------------------------

    @Test
    void holderOfRolesManageListsTheTwentyPermissionsInOrder() throws Exception {
        send(chairman(), get("/api/v1/permissions"), null)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(20)))
                .andExpect(jsonPath("$[*].code", contains("MEMBERS_VIEW", "MEMBERS_EDIT", "ROLES_MANAGE", "AUDIT_VIEW",
                        "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN", "SETTINGS_MANAGE", "TARIFFS_VIEW",
                        "TARIFFS_MANAGE", "METERS_VIEW", "METERS_MANAGE", "READINGS_ENTER", "READINGS_APPROVE",
                        "CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE", "PERIODS_CLOSE", "RECALCULATION_RUN")))
                .andExpect(jsonPath("$[0].name").value("View members"))
                .andExpect(jsonPath("$[0].description").isNotEmpty());
    }

    @Test
    void everyEndpointIsDeniedWithoutRolesManage() throws Exception {
        UUID someRole = createRole("Denied " + tag(), "MEMBERS_VIEW");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String token : List.of(demo("treasurer@demo.local"), demo("member@demo.local"), operator)) {
            send(token, get("/api/v1/permissions"), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, get("/api/v1/roles"), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, post("/api/v1/roles"), roleBody("Nope " + tag(), "MEMBERS_VIEW")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, put("/api/v1/roles/" + someRole), roleBody("Nope " + tag())).andExpect(status().isForbidden());
            send(token, delete("/api/v1/roles/" + someRole), null).andExpect(status().isForbidden());
            send(token, put("/api/v1/members/" + DEMO_CHAIRMAN + "/roles"), Map.of("roleIds", List.of()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(roleExists(someRole)).isTrue(); // the denied DELETE changed nothing
    }

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/permissions")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/roles")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(roleBody("No token")))).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/roles/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(roleBody("No token")))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/roles/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/members/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleIds\":[]}")).andExpect(status().isUnauthorized());
    }

    // ---- list ----------------------------------------------------------------------------------------------------

    @Test
    void listsBaseRolesFirstThenCustomRolesByNameWithPermissionsAndActiveMemberCounts() throws Exception {
        UUID zeta = createRole("Zeta " + tag(), "MEMBERS_VIEW");
        UUID alpha = createRole("alpha " + tag(), "AUDIT_VIEW", "MEMBERS_VIEW");
        UUID activeHolder = newMember(DEMO_TENANT, "holder-" + tag() + "@example.com");
        UUID archivedHolder = newMember(DEMO_TENANT, "archived-" + tag() + "@example.com");
        archiveMember(DEMO_TENANT, archivedHolder);
        setRoles(chairman(), activeHolder, List.of(alpha)).andExpect(status().isOk());
        setRoles(chairman(), archivedHolder, List.of(alpha)).andExpect(status().isOk()); // archived members may be edited

        String body = send(chairman(), get("/api/v1/roles"), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode roles = json.readTree(body);

        assertThat(roles.get(0).get("name").asString()).isEqualTo("Chairman");
        assertThat(roles.get(0).get("baseCode").asString()).isEqualTo("CHAIRMAN");
        assertThat(roles.get(0).get("permissions")).hasSize(20);
        assertThat(roles.get(1).get("baseCode").asString()).isEqualTo("TREASURER");
        assertThat(texts(roles.get(1).get("permissions"))).containsExactly("MEMBERS_VIEW", "AUDIT_VIEW", "PLOTS_VIEW",
                "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW");
        assertThat(roles.get(2).get("baseCode").asString()).isEqualTo("MEMBER");
        assertThat(roles.get(2).get("permissions")).isEmpty();
        assertThat(roles.get(0).get("memberCount").asInt()).isGreaterThanOrEqualTo(1);
        for (int i = 3; i < roles.size(); i++) {
            assertThat(roles.get(i).has("baseCode")).isFalse();
        }
        List<String> custom = new ArrayList<>();
        for (int i = 3; i < roles.size(); i++) {
            custom.add(roles.get(i).get("name").asString());
        }
        assertThat(custom).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        JsonNode alphaNode = find(roles, alpha);
        assertThat(alphaNode.get("memberCount").asInt()).as("archived members are not counted").isEqualTo(1);
        assertThat(find(roles, zeta).get("memberCount").asInt()).isZero();
    }

    @Test
    void listIncludesNameCustomizedFlags() throws Exception {
        UUID custom = createRole("Flagged " + tag(), "MEMBERS_VIEW");

        String body = send(chairman(), get("/api/v1/roles"), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode roles = json.readTree(body);

        for (int i = 0; i < 3; i++) {
            assertThat(roles.get(i).get("nameCustomized").asBoolean())
                    .as("base role %s keeps its standard name", roles.get(i).get("baseCode").asString()).isFalse();
        }
        assertThat(find(roles, custom).get("nameCustomized").asBoolean()).isTrue();
        for (int i = 3; i < roles.size(); i++) {
            assertThat(roles.get(i).get("nameCustomized").asBoolean()).as("custom roles are always customised").isTrue();
        }
    }

    @Test
    void listIncludesDescriptionCustomizedFlags() throws Exception {
        UUID custom = createRole("Described " + tag(), "MEMBERS_VIEW");

        String body = send(chairman(), get("/api/v1/roles"), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode roles = json.readTree(body);

        for (int i = 0; i < 3; i++) {
            assertThat(roles.get(i).get("descriptionCustomized").asBoolean())
                    .as("base role %s keeps its standard description", roles.get(i).get("baseCode").asString())
                    .isFalse();
        }
        assertThat(find(roles, custom).get("descriptionCustomized").asBoolean()).isTrue();
        for (int i = 3; i < roles.size(); i++) {
            assertThat(roles.get(i).get("descriptionCustomized").asBoolean())
                    .as("custom roles are always customised").isTrue();
        }
    }

    // ---- create ----------------------------------------------------------------------------------------------------

    @Test
    void createsACustomRoleAndAuditsIt() throws Exception {
        String name = "Secretary " + tag();
        Map<String, Object> body = roleBody("  " + name + "  ", "MEMBERS_VIEW", "MEMBERS_EDIT");
        body.put("description", "  Keeps the records  ");

        ResultActions created = send(chairman(), post("/api/v1/roles"), body);
        created.andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("Keeps the records"))
                .andExpect(jsonPath("$.baseCode").doesNotExist())
                .andExpect(jsonPath("$.descriptionCustomized").value(true))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "MEMBERS_EDIT")))
                .andExpect(jsonPath("$.memberCount").value(0));
        UUID id = track(created);

        send(chairman(), get("/api/v1/roles"), null)
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].name").value(name));
        // the cooperative comes from the token
        UUID storedTenant = one(DEMO_TENANT, UUID.class, "select tenant_id from role where id = ?", id);
        assertThat(storedTenant).isEqualTo(DEMO_TENANT);

        List<Map<String, Object>> entries = awaitAudit(id, 1);
        assertThat(entries.get(0).get("action")).isEqualTo("ROLE_CREATED");
        assertThat(entries.get(0).get("entity_type")).isEqualTo("ROLE");
        assertThat(entries.get(0).get("entity_label")).isEqualTo(name);
        assertThat(entries.get(0).get("actor_member_id")).isEqualTo(DEMO_CHAIRMAN);
        JsonNode changes = json.readTree((String) entries.get(0).get("changes"));
        assertThat(change(changes, "name").get("new").asString()).isEqualTo(name);
        assertThat(change(changes, "description").get("new").asString()).isEqualTo("Keeps the records");
        assertThat(change(changes, "permissions").get("new").asString()).isEqualTo("MEMBERS_EDIT, MEMBERS_VIEW");
    }

    @Test
    void aRoleWithoutPermissionsAndDescriptionIsFine() throws Exception {
        ResultActions created = send(chairman(), post("/api/v1/roles"), roleBody("Empty " + tag()));
        created.andExpect(status().isCreated()).andExpect(jsonPath("$.permissions", empty()))
                .andExpect(jsonPath("$.description").doesNotExist());
        track(created);
    }

    @Test
    void theNameIsRequiredTrimmedAndAtMostSixtyCharacters() throws Exception {
        for (String blank : List.of("", "   ", " \t ")) {
            send(chairman(), post("/api/v1/roles"), roleBody(blank, "MEMBERS_VIEW"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        send(chairman(), post("/api/v1/roles"), roleBody("x".repeat(61)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // exactly 60 characters is the limit
        ResultActions sixty = send(chairman(), post("/api/v1/roles"), roleBody("y".repeat(52) + tag()));
        sixty.andExpect(status().isCreated());
        track(sixty);
        // a body without permissions is malformed
        send(chairman(), post("/api/v1/roles"), Map.of("name", "No permissions " + tag()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissions"));
    }

    @Test
    void theNameIsUniqueIgnoringCase() throws Exception {
        String name = "Unique " + tag();
        ResultActions first = send(chairman(), post("/api/v1/roles"), roleBody(name));
        first.andExpect(status().isCreated());
        track(first);

        for (String clash : List.of(name.toUpperCase(), name.toLowerCase(), "  " + name + " ", "treasurer", "CHAIRMAN",
                "member")) {
            send(chairman(), post("/api/v1/roles"), roleBody(clash))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"))
                    .andExpect(jsonPath("$.detail").value("A role with this name already exists."))
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }
    }

    @Test
    void theNameIsUniqueIgnoringCaseForCyrillicToo() throws Exception {
        String name = "Секретар " + tag();
        ResultActions first = send(chairman(), post("/api/v1/roles"), roleBody(name));
        first.andExpect(status().isCreated());
        track(first);

        send(chairman(), post("/api/v1/roles"), roleBody(name.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
    }

    @Test
    void twoConcurrentCreationsOfTheSameNameEndAsOneCreatedAndOneNameTaken() throws Exception {
        // Both requests may pass the friendly pre-check; the unique index decides and must surface as 409, never 500.
        send(chairman(), get("/api/v1/me"), null).andExpect(status().isOk()); // first sign-in links the member
        for (int round = 0; round < 5; round++) {
            String name = "Race " + tag();
            List<ResultActions> results = ConcurrentRequests.run(
                    () -> send(chairman(), post("/api/v1/roles"), roleBody(name)),
                    () -> send(chairman(), post("/api/v1/roles"), roleBody(name.toUpperCase())));
            List<Integer> statuses = results.stream().map(r -> r.andReturn().getResponse().getStatus()).sorted().toList();
            results.forEach(r -> {
                if (r.andReturn().getResponse().getStatus() == 201) {
                    track(r);
                }
            });
            assertThat(statuses).containsExactly(201, 409);
            for (ResultActions r : results) {
                if (r.andReturn().getResponse().getStatus() == 409) {
                    assertThat(r.andReturn().getResponse().getContentAsString()).contains("ROLE_NAME_TAKEN");
                }
            }
        }
    }

    // ---- update ----------------------------------------------------------------------------------------------------

    @Test
    void updatesNameDescriptionAndPermissionsAndAuditsOnlyTheChangedFields() throws Exception {
        String name = "Editor " + tag();
        UUID id = createRole(name, "MEMBERS_VIEW", "PLOTS_VIEW");
        awaitAudit(id, 1);
        String renamed = "Renamed " + tag();
        Map<String, Object> body = roleBody(renamed, "MEMBERS_VIEW", "PLOTS_EDIT");

        send(chairman(), put("/api/v1/roles/" + id), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "PLOTS_EDIT")));

        assertThat(storedPermissions(id)).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_EDIT");
        List<Map<String, Object>> entries = awaitAudit(id, 2);
        assertThat(entries.get(1).get("action")).isEqualTo("ROLE_UPDATED");
        assertThat(entries.get(1).get("entity_label")).isEqualTo(renamed);
        JsonNode changes = json.readTree((String) entries.get(1).get("changes"));
        assertThat(changes).hasSize(2); // description did not change
        assertThat(change(changes, "name").get("old").asString()).isEqualTo(name);
        assertThat(change(changes, "name").get("new").asString()).isEqualTo(renamed);
        assertThat(change(changes, "permissions").get("old").asString()).isEqualTo("MEMBERS_VIEW, PLOTS_VIEW");
        assertThat(change(changes, "permissions").get("new").asString()).isEqualTo("MEMBERS_VIEW, PLOTS_EDIT");
    }

    @Test
    void anUpdateThatChangesNothingWritesNoAuditEntry() throws Exception {
        String name = "Same " + tag();
        UUID id = createRole(name, "MEMBERS_VIEW");
        awaitAudit(id, 1);

        send(chairman(), put("/api/v1/roles/" + id), roleBody(name, "MEMBERS_VIEW")).andExpect(status().isOk());
        send(chairman(), put("/api/v1/roles/" + id), roleBody(name, "MEMBERS_VIEW", "AUDIT_VIEW"))
                .andExpect(status().isOk());

        assertThat(awaitAudit(id, 2)).extracting(e -> e.get("action")).containsExactly("ROLE_CREATED", "ROLE_UPDATED");
    }

    @Test
    void renamingToAnotherRolesNameIsRefusedButChangingTheCaseOfOwnNameIsFine() throws Exception {
        String first = "First " + tag();
        String second = "Second " + tag();
        UUID firstId = createRole(first);
        createRole(second);

        send(chairman(), put("/api/v1/roles/" + firstId), roleBody(second.toLowerCase()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        send(chairman(), put("/api/v1/roles/" + firstId), roleBody("Treasurer"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        send(chairman(), put("/api/v1/roles/" + firstId), roleBody(first.toUpperCase()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(first.toUpperCase()));
        send(chairman(), put("/api/v1/roles/" + firstId), roleBody("  "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void anUnknownRoleOrOneOfAnotherCooperativeIsNotFound() throws Exception {
        UUID otherRole = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = 'MEMBER'", UUID.class));

        for (UUID id : List.of(UUID.randomUUID(), otherRole)) {
            send(chairman(), put("/api/v1/roles/" + id), roleBody("Whatever " + tag()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
            send(chairman(), delete("/api/v1/roles/" + id), null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        String otherName = one(OTHER_TENANT, String.class, "select name from role where id = ?", otherRole);
        assertThat(otherName).isEqualTo("Member");
    }

    // ---- base roles ------------------------------------------------------------------------------------------------

    @Test
    void renamingTheTreasurerRoleShowsTheStoredNameAndAuditsTheStandardNameToken() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Бухгалтер"))
                .andExpect(jsonPath("$.baseCode").value("TREASURER"))
                .andExpect(jsonPath("$.nameCustomized").value(true));

        assertThat(roleName(treasurer)).isEqualTo("Бухгалтер");
        assertThat(nameCustomized(treasurer)).isTrue();
        send(chairman(), get("/api/v1/roles"), null)
                .andExpect(jsonPath("$[?(@.id == '" + treasurer + "')].name").value("Бухгалтер"))
                .andExpect(jsonPath("$[?(@.id == '" + treasurer + "')].nameCustomized").value(true));
        List<Map<String, Object>> entries = awaitNewAudit(treasurer, before, 1);
        assertThat(entries.get(0).get("action")).isEqualTo("ROLE_UPDATED");
        assertThat(entries.get(0).get("entity_label")).isEqualTo("Бухгалтер");
        JsonNode changes = json.readTree((String) entries.get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "name").get("old").asString()).isEqualTo("base:TREASURER");
        assertThat(change(changes, "name").get("new").asString()).isEqualTo("Бухгалтер");
    }

    @Test
    void renamingTheMemberRoleToAnotherRolesNameIgnoringCaseIsRefused() throws Exception {
        UUID member = baseRoleId("MEMBER");
        createRole("садівник");
        long before = auditCountForRole(member);

        send(chairman(), put("/api/v1/roles/" + member), seedBody("MEMBER", "Садівник"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        assertThat(roleName(member)).isEqualTo("Member");
        assertThat(nameCustomized(member)).isFalse();
        assertThat(auditCountForRole(member)).isEqualTo(before);
    }

    @Test
    void aBaseRoleCannotTakeTheNameOfAnotherBaseRole() throws Exception {
        send(chairman(), put("/api/v1/roles/" + baseRoleId("MEMBER")), seedBody("MEMBER", "TREASURER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        assertThat(roleName(baseRoleId("MEMBER"))).isEqualTo("Member");
    }

    @Test
    void aTrailingSpaceInTheNameOfACustomRoleIsNotARename() throws Exception {
        String name = "Електрик " + tag();
        UUID id = createRole(name, "MEMBERS_VIEW");
        awaitAudit(id, 1);

        send(chairman(), put("/api/v1/roles/" + id), roleBody(name + " ", "MEMBERS_VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.nameCustomized").value(true));

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditEntries(id)).extracting(e -> e.get("action")).containsExactly("ROLE_CREATED");
        assertThat(roleName(id)).isEqualTo(name);
    }

    @Test
    void restoringTheStandardNameBringsBackTheSeedNameAndIgnoresTheSubmittedName() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long start = auditCountForRole(treasurer);
        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер")).andExpect(status().isOk());
        awaitNewAudit(treasurer, start, 1); // the rename's entry is written asynchronously
        long before = start + 1;

        Map<String, Object> restore = seedBody("TREASURER", "Any other name");
        restore.put("useDefaultName", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer"))
                .andExpect(jsonPath("$.nameCustomized").value(false));

        assertThat(roleName(treasurer)).isEqualTo("Treasurer");
        assertThat(nameCustomized(treasurer)).isFalse();
        List<Map<String, Object>> entries = awaitNewAudit(treasurer, before, 1);
        assertThat(entries.get(0).get("entity_label")).isEqualTo("base:TREASURER");
        JsonNode changes = json.readTree((String) entries.get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "name").get("old").asString()).isEqualTo("Бухгалтер");
        assertThat(change(changes, "name").get("new").asString()).isEqualTo("base:TREASURER");
    }

    @Test
    void restoringTheStandardNameOfARoleThatWasNeverRenamedChangesNothing() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        Map<String, Object> restore = seedBody("TREASURER", "Ignored");
        restore.put("useDefaultName", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer"))
                .andExpect(jsonPath("$.nameCustomized").value(false));

        Thread.sleep(500);
        assertThat(auditCountForRole(treasurer)).isEqualTo(before);
        assertThat(roleName(treasurer)).isEqualTo("Treasurer");
        assertThat(nameCustomized(treasurer)).isFalse();
    }

    @Test
    void restoringTheStandardNameIsRefusedWhileAnotherRoleHoldsTheSeedName() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер")).andExpect(status().isOk());
        createRole("Treasurer"); // the seed name is free now

        Map<String, Object> restore = seedBody("TREASURER", "Бухгалтер");
        restore.put("useDefaultName", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        assertThat(roleName(treasurer)).isEqualTo("Бухгалтер");
        assertThat(nameCustomized(treasurer)).isTrue();
    }

    @Test
    void useDefaultNameOnACustomRoleIsAValidationError() throws Exception {
        String name = "Custom " + tag();
        UUID id = createRole(name, "MEMBERS_VIEW");

        Map<String, Object> body = roleBody("Another " + tag(), "MEMBERS_VIEW");
        body.put("useDefaultName", true);
        send(chairman(), put("/api/v1/roles/" + id), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("useDefaultName"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        assertThat(roleName(id)).isEqualTo(name);
        // an explicit false is the default and fine
        body.put("useDefaultName", false);
        send(chairman(), put("/api/v1/roles/" + id), body).andExpect(status().isOk());
    }

    @Test
    void aCaseOnlyChangeOfABaseRoleNameIsARenameAndIsAudited() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "TREASURER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TREASURER"))
                .andExpect(jsonPath("$.nameCustomized").value(true));

        JsonNode changes = json.readTree((String) awaitNewAudit(treasurer, before, 1).get(0).get("changes"));
        assertThat(change(changes, "name").get("old").asString()).isEqualTo("base:TREASURER");
        assertThat(change(changes, "name").get("new").asString()).isEqualTo("TREASURER");
    }

    @Test
    void aCaseOnlyChangeOfACustomRoleNameIsAudited() throws Exception {
        String name = "Warden " + tag();
        UUID id = createRole(name);
        awaitAudit(id, 1);

        send(chairman(), put("/api/v1/roles/" + id), roleBody(name.toUpperCase()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name.toUpperCase()));

        JsonNode changes = json.readTree((String) awaitAudit(id, 2).get(1).get("changes"));
        assertThat(change(changes, "name").get("old").asString()).isEqualTo(name);
        assertThat(change(changes, "name").get("new").asString()).isEqualTo(name.toUpperCase());
    }

    @Test
    void aPermissionOnlyChangeKeepsTheCustomNameAndTheFlagOfARenamedBaseRole() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long start = auditCountForRole(treasurer);
        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер")).andExpect(status().isOk());
        awaitNewAudit(treasurer, start, 1); // the rename's entry is written asynchronously
        long before = start + 1;

        Map<String, Object> body = seedBody("TREASURER", "Бухгалтер");
        body.put("permissions", List.of("MEMBERS_VIEW"));
        send(chairman(), put("/api/v1/roles/" + treasurer), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Бухгалтер"))
                .andExpect(jsonPath("$.nameCustomized").value(true));

        assertThat(roleName(treasurer)).isEqualTo("Бухгалтер");
        assertThat(nameCustomized(treasurer)).isTrue();
        JsonNode changes = json.readTree((String) awaitNewAudit(treasurer, before, 1).get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "permissions").get("new").asString()).isEqualTo("MEMBERS_VIEW");
    }

    @Test
    void savingANeverRenamedBaseRoleWithItsStoredNameKeepsItNonCustomized() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        Map<String, Object> body = seedBody("TREASURER", "Treasurer");
        body.put("permissions", List.of("MEMBERS_VIEW"));
        send(chairman(), put("/api/v1/roles/" + treasurer), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer"))
                .andExpect(jsonPath("$.nameCustomized").value(false));

        assertThat(nameCustomized(treasurer)).isFalse();
        JsonNode changes = json.readTree((String) awaitNewAudit(treasurer, before, 1).get(0).get("changes"));
        assertThat(changes).hasSize(1); // the name is not part of the entry
        assertThat(change(changes, "permissions")).isNotNull();
    }

    @Test
    void aTreasurerWithoutRolesManageCannotRenameARole() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");

        send(demo("treasurer@demo.local"), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        assertThat(roleName(treasurer)).isEqualTo("Treasurer");
        assertThat(nameCustomized(treasurer)).isFalse();
    }

    @Test
    void twoConcurrentRenamesToTheSameNameEndAsOneRenamedAndOneNameTaken() throws Exception {
        // Both requests may pass the friendly pre-check; the unique index decides and must surface as 409, never 500.
        send(chairman(), get("/api/v1/me"), null).andExpect(status().isOk()); // first sign-in links the member
        UUID treasurer = baseRoleId("TREASURER");
        UUID member = baseRoleId("MEMBER");
        String name = "Race " + tag();

        List<ResultActions> results = ConcurrentRequests.run(
                () -> send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", name)),
                () -> send(chairman(), put("/api/v1/roles/" + member), seedBody("MEMBER", name.toUpperCase())));

        List<Integer> statuses = results.stream().map(r -> r.andReturn().getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);
        for (ResultActions r : results) {
            if (r.andReturn().getResponse().getStatus() == 409) {
                assertThat(r.andReturn().getResponse().getContentAsString()).contains("ROLE_NAME_TAKEN");
            }
        }
    }

    // ---- base role descriptions (feature 005) ------------------------------------------------------------------------

    @Test
    void changingTheTreasurerDescriptionSetsTheFlagAndAuditsTheStandardTokenAsTheOldValue() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        Map<String, Object> body = seedBody("TREASURER", "Treasurer");
        body.put("description", "Веде касу");
        send(chairman(), put("/api/v1/roles/" + treasurer), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Веде касу"))
                .andExpect(jsonPath("$.descriptionCustomized").value(true))
                .andExpect(jsonPath("$.nameCustomized").value(false));

        assertThat(roleDescription(treasurer)).isEqualTo("Веде касу");
        assertThat(descriptionCustomized(treasurer)).isTrue();
        send(chairman(), get("/api/v1/roles"), null)
                .andExpect(jsonPath("$[?(@.id == '" + treasurer + "')].description").value("Веде касу"))
                .andExpect(jsonPath("$[?(@.id == '" + treasurer + "')].descriptionCustomized").value(true));
        List<Map<String, Object>> entries = awaitNewAudit(treasurer, before, 1);
        assertThat(entries.get(0).get("action")).isEqualTo("ROLE_UPDATED");
        assertThat(entries.get(0).get("entity_label")).isEqualTo("base:TREASURER"); // the name is still standard
        JsonNode changes = json.readTree((String) entries.get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "description").get("old").asString()).isEqualTo("base:TREASURER");
        assertThat(change(changes, "description").get("new").asString()).isEqualTo("Веде касу");
    }

    @Test
    void restoringTheStandardDescriptionBringsBackTheSeedTextIgnoresTheSubmittedOneAndAuditsTextToToken()
            throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long start = auditCountForRole(treasurer);
        Map<String, Object> change = seedBody("TREASURER", "Treasurer");
        change.put("description", "Веде касу");
        send(chairman(), put("/api/v1/roles/" + treasurer), change).andExpect(status().isOk());
        awaitNewAudit(treasurer, start, 1); // the change's entry is written asynchronously
        long before = start + 1;

        Map<String, Object> restore = seedBody("TREASURER", "Treasurer");
        restore.put("description", "Some other text");
        restore.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Read access to members, plots and audit"))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));

        assertThat(roleDescription(treasurer)).isEqualTo("Read access to members, plots and audit");
        assertThat(descriptionCustomized(treasurer)).isFalse();
        JsonNode changes = json.readTree((String) awaitNewAudit(treasurer, before, 1).get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "description").get("old").asString()).isEqualTo("Веде касу");
        assertThat(change(changes, "description").get("new").asString()).isEqualTo("base:TREASURER");
    }

    @Test
    void restoringTheStandardDescriptionOfARoleThatWasNeverChangedChangesNothing() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        Map<String, Object> restore = seedBody("TREASURER", "Treasurer");
        restore.put("description", "Ignored");
        restore.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Read access to members, plots and audit"))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCountForRole(treasurer)).isEqualTo(before);
        assertThat(roleDescription(treasurer)).isEqualTo("Read access to members, plots and audit");
        assertThat(descriptionCustomized(treasurer)).isFalse();
    }

    @Test
    void useDefaultDescriptionOnACustomRoleIsAValidationErrorOnThatField() throws Exception {
        String name = "Custom " + tag();
        UUID id = createRole(name, "MEMBERS_VIEW");

        Map<String, Object> body = roleBody("Another " + tag(), "MEMBERS_VIEW");
        body.put("description", "Changed");
        body.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + id), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("useDefaultDescription"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        assertThat(roleName(id)).isEqualTo(name);
        assertThat(roleDescription(id)).isNull(); // refused before any change
        // an explicit false is the default and fine
        body.put("useDefaultDescription", false);
        send(chairman(), put("/api/v1/roles/" + id), body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.descriptionCustomized").value(true));
    }

    @Test
    void bothRestoreFlagsOnACustomRoleReportBothFields() throws Exception {
        UUID id = createRole("Custom " + tag(), "MEMBERS_VIEW");

        Map<String, Object> body = roleBody("Another " + tag(), "MEMBERS_VIEW");
        body.put("useDefaultName", true);
        body.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + id), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("useDefaultName", "useDefaultDescription")));
    }

    @Test
    void aPermissionOnlySaveWithTheStoredDescriptionKeepsItNonCustomized() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long before = auditCountForRole(treasurer);

        Map<String, Object> body = seedBody("TREASURER", "Treasurer"); // the stored description, sent back untouched
        body.put("permissions", List.of("MEMBERS_VIEW"));
        send(chairman(), put("/api/v1/roles/" + treasurer), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Read access to members, plots and audit"))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));

        assertThat(descriptionCustomized(treasurer)).isFalse();
        JsonNode changes = json.readTree((String) awaitNewAudit(treasurer, before, 1).get(0).get("changes"));
        assertThat(changes).hasSize(1); // the description is not part of the entry
        assertThat(change(changes, "permissions")).isNotNull();
    }

    @Test
    void aPermissionOnlySaveKeepsAChangedDescriptionCustomized() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        Map<String, Object> change = seedBody("TREASURER", "Treasurer");
        change.put("description", "Веде касу");
        send(chairman(), put("/api/v1/roles/" + treasurer), change).andExpect(status().isOk());

        change.put("permissions", List.of("MEMBERS_VIEW"));
        send(chairman(), put("/api/v1/roles/" + treasurer), change)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Веде касу"))
                .andExpect(jsonPath("$.descriptionCustomized").value(true));

        assertThat(descriptionCustomized(treasurer)).isTrue();
    }

    @Test
    void aBlankDescriptionOnABaseRoleCustomizesItWithNull() throws Exception {
        UUID member = baseRoleId("MEMBER");
        long before = auditCountForRole(member);

        Map<String, Object> body = seedBody("MEMBER", "Member");
        body.put("description", "   ");
        send(chairman(), put("/api/v1/roles/" + member), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.descriptionCustomized").value(true));

        assertThat(roleDescription(member)).isNull();
        assertThat(descriptionCustomized(member)).isTrue();
        JsonNode changes = json.readTree((String) awaitNewAudit(member, before, 1).get(0).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "description").get("old").asString()).isEqualTo("base:MEMBER");
        JsonNode newValue = change(changes, "description").get("new");
        assertThat(newValue == null || newValue.isNull()).isTrue();
    }

    @Test
    void restoringBothTheNameAndTheDescriptionTogetherRestoresBothAndAuditsOnce() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        long start = auditCountForRole(treasurer);
        Map<String, Object> change = seedBody("TREASURER", "Бухгалтер");
        change.put("description", "Веде касу");
        send(chairman(), put("/api/v1/roles/" + treasurer), change)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.descriptionCustomized").value(true));
        awaitNewAudit(treasurer, start, 1);
        long before = start + 1;

        Map<String, Object> restore = seedBody("TREASURER", "Whatever");
        restore.put("description", "Whatever");
        restore.put("useDefaultName", true);
        restore.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer"))
                .andExpect(jsonPath("$.nameCustomized").value(false))
                .andExpect(jsonPath("$.description").value("Read access to members, plots and audit"))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));

        assertThat(nameCustomized(treasurer)).isFalse();
        assertThat(descriptionCustomized(treasurer)).isFalse();
        Thread.sleep(500);
        List<Map<String, Object>> entries = awaitNewAudit(treasurer, before, 1);
        assertThat(entries).hasSize(1);
        JsonNode changes = json.readTree((String) entries.get(0).get("changes"));
        assertThat(changes).hasSize(2);
        assertThat(change(changes, "name").get("old").asString()).isEqualTo("Бухгалтер");
        assertThat(change(changes, "name").get("new").asString()).isEqualTo("base:TREASURER");
        assertThat(change(changes, "description").get("old").asString()).isEqualTo("Веде касу");
        assertThat(change(changes, "description").get("new").asString()).isEqualTo("base:TREASURER");
    }

    @Test
    void theNameAndDescriptionRestoresAreIndependent() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        Map<String, Object> change = seedBody("TREASURER", "Бухгалтер");
        change.put("description", "Веде касу");
        send(chairman(), put("/api/v1/roles/" + treasurer), change).andExpect(status().isOk());

        // restoring only the description keeps the new name
        Map<String, Object> restoreDescription = seedBody("TREASURER", "Бухгалтер");
        restoreDescription.put("useDefaultDescription", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restoreDescription)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Бухгалтер"))
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));

        // changing the description again, then restoring only the name keeps the new description
        send(chairman(), put("/api/v1/roles/" + treasurer), change).andExpect(status().isOk());
        Map<String, Object> restoreName = new LinkedHashMap<>(change);
        restoreName.put("useDefaultName", true);
        send(chairman(), put("/api/v1/roles/" + treasurer), restoreName)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer"))
                .andExpect(jsonPath("$.nameCustomized").value(false))
                .andExpect(jsonPath("$.description").value("Веде касу"))
                .andExpect(jsonPath("$.descriptionCustomized").value(true));

        assertThat(roleDescription(treasurer)).isEqualTo("Веде касу");
        assertThat(nameCustomized(treasurer)).isFalse();
    }

    @Test
    void renamingDoesNotCustomizeTheDescriptionAndChangingTheDescriptionDoesNotCustomizeTheName() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        UUID member = baseRoleId("MEMBER");

        send(chairman(), put("/api/v1/roles/" + treasurer), seedBody("TREASURER", "Бухгалтер"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));
        Map<String, Object> described = seedBody("MEMBER", "Member");
        described.put("description", "Everyone");
        send(chairman(), put("/api/v1/roles/" + member), described)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameCustomized").value(false))
                .andExpect(jsonPath("$.descriptionCustomized").value(true));

        assertThat(descriptionCustomized(treasurer)).isFalse();
        assertThat(nameCustomized(member)).isFalse();
    }

    @Test
    void aTreasurerWithoutRolesManageCannotChangeOrRestoreADescription() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        Map<String, Object> change = seedBody("TREASURER", "Treasurer");
        change.put("description", "Веде касу");

        send(demo("treasurer@demo.local"), put("/api/v1/roles/" + treasurer), change)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        Map<String, Object> restore = seedBody("TREASURER", "Treasurer");
        restore.put("useDefaultDescription", true);
        send(demo("treasurer@demo.local"), put("/api/v1/roles/" + treasurer), restore)
                .andExpect(status().isForbidden());

        assertThat(roleDescription(treasurer)).isEqualTo("Read access to members, plots and audit");
        assertThat(descriptionCustomized(treasurer)).isFalse();
    }

    @Test
    void baseRolesCannotBeDeleted() throws Exception {
        for (String code : List.of("CHAIRMAN", "TREASURER", "MEMBER")) {
            send(chairman(), delete("/api/v1/roles/" + baseRoleId(code)), null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_ROLE_IMMUTABLE"));
            assertThat(roleExists(baseRoleId(code))).isTrue();
        }
    }

    @Test
    void theChairmanRoleCanBeRenamedButKeepsEveryPermission() throws Exception {
        UUID chairman = baseRoleId("CHAIRMAN");

        send(chairman(), put("/api/v1/roles/" + chairman), roleBody("Chairman", "MEMBERS_VIEW"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_ROLE_IMMUTABLE"));
        send(chairman(), put("/api/v1/roles/" + chairman), roleBody("Chairman"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_ROLE_IMMUTABLE"));
        // a rename together with fewer permissions is refused as a whole
        send(chairman(), put("/api/v1/roles/" + chairman), roleBody("Голова", "MEMBERS_VIEW"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_ROLE_IMMUTABLE"));
        assertThat(roleName(chairman)).isEqualTo("Chairman");
        assertThat(storedPermissions(chairman)).hasSize(20);

        send(chairman(), put("/api/v1/roles/" + chairman), seedBody("CHAIRMAN", "Голова"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Голова"))
                .andExpect(jsonPath("$.nameCustomized").value(true))
                .andExpect(jsonPath("$.permissions", hasSize(20)));

        assertThat(roleName(chairman)).isEqualTo("Голова");
        assertThat(storedPermissions(chairman)).hasSize(20);
        send(chairman(), get("/api/v1/me"), null)
                .andExpect(jsonPath("$.permissions", hasSize(20))); // the system logic keeps using baseCode
    }

    @Test
    void savingTheChairmanRoleUnchangedIsANoOp() throws Exception {
        UUID chairman = baseRoleId("CHAIRMAN");
        long before = auditCountForRole(chairman);

        // a PUT replaces everything, the description included: send the stored one back
        Map<String, Object> unchanged = roleBody("Chairman", allPermissions());
        unchanged.put("description", one(DEMO_TENANT, String.class, "select description from role where id = ?", chairman));
        send(chairman(), put("/api/v1/roles/" + chairman), unchanged)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCode").value("CHAIRMAN"))
                .andExpect(jsonPath("$.permissions", hasSize(20)));

        assertThat(storedPermissions(chairman)).hasSize(20);
        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCountForRole(chairman)).isEqualTo(before);
    }

    @Test
    void treasurerAndMemberPermissionsAndDescriptionsAreEditable() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        UUID member = baseRoleId("MEMBER");

        send(chairman(), put("/api/v1/roles/" + treasurer), roleBody("Treasurer", "MEMBERS_VIEW", "PLOTS_EDIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCode").value("TREASURER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "PLOTS_EDIT")));
        Map<String, Object> memberBody = roleBody("Member", "PLOTS_VIEW");
        memberBody.put("description", "Everyone");
        send(chairman(), put("/api/v1/roles/" + member), memberBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", contains("PLOTS_VIEW")))
                .andExpect(jsonPath("$.description").value("Everyone"));

        assertThat(storedPermissions(treasurer)).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_EDIT");
    }

    @Test
    void aPermissionChangeAppliesOnTheNextRequest() throws Exception {
        UUID treasurer = baseRoleId("TREASURER");
        String treasurerToken = demo("treasurer@demo.local");
        send(treasurerToken, get("/api/v1/audit?size=1"), null).andExpect(status().isOk());

        send(chairman(), put("/api/v1/roles/" + treasurer), roleBody("Treasurer", "MEMBERS_VIEW", "PLOTS_VIEW"))
                .andExpect(status().isOk());

        // same token, next request: AUDIT_VIEW is gone
        send(treasurerToken, get("/api/v1/audit?size=1"), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(treasurerToken, get("/api/v1/members?size=1"), null).andExpect(status().isOk());
        send(treasurerToken, get("/api/v1/me"), null)
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW")));

        send(chairman(), put("/api/v1/roles/" + treasurer),
                roleBody("Treasurer", "MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW")).andExpect(status().isOk());
        send(treasurerToken, get("/api/v1/audit?size=1"), null).andExpect(status().isOk());
    }

    // ---- delete ------------------------------------------------------------------------------------------------------

    @Test
    void deletesAnUnusedCustomRoleAndAuditsIt() throws Exception {
        String name = "Temporary " + tag();
        UUID id = createRole(name, "AUDIT_VIEW");
        awaitAudit(id, 1);

        send(chairman(), delete("/api/v1/roles/" + id), null).andExpect(status().isNoContent());

        assertThat(roleExists(id)).isFalse();
        Integer leftovers = one(DEMO_TENANT, Integer.class, "select count(*) from role_permission where role_id = ?", id);
        assertThat(leftovers).isZero();
        send(chairman(), get("/api/v1/roles"), null).andExpect(jsonPath("$[?(@.id == '" + id + "')]", empty()));
        List<Map<String, Object>> entries = awaitAudit(id, 2);
        assertThat(entries.get(1).get("action")).isEqualTo("ROLE_DELETED");
        assertThat(entries.get(1).get("entity_label")).isEqualTo(name);
        JsonNode changes = json.readTree((String) entries.get(1).get("changes"));
        assertThat(change(changes, "name").get("old").asString()).isEqualTo(name);
        assertThat(change(changes, "permissions").get("old").asString()).isEqualTo("AUDIT_VIEW");
        // deleting it again: not found
        send(chairman(), delete("/api/v1/roles/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void aRoleGivenToAMemberCannotBeDeletedEvenIfOnlyAnArchivedMemberHoldsIt() throws Exception {
        UUID role = createRole("InUse " + tag(), "MEMBERS_VIEW");
        UUID holder = newMember(DEMO_TENANT, "inuse-" + tag() + "@example.com");
        setRoles(chairman(), holder, List.of(role)).andExpect(status().isOk());

        send(chairman(), delete("/api/v1/roles/" + role), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"))
                .andExpect(jsonPath("$.detail").value("This role is assigned to members and cannot be deleted."));
        archiveMember(DEMO_TENANT, holder);
        send(chairman(), delete("/api/v1/roles/" + role), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
        assertThat(roleExists(role)).isTrue();

        setRoles(chairman(), holder, List.of()).andExpect(status().isOk());
        send(chairman(), delete("/api/v1/roles/" + role), null).andExpect(status().isNoContent());
    }

    /**
     * The web client sends {@code Accept: application/json} on every call. DELETE has no response body on success, so
     * the contract must still offer {@code application/json} for it, or content negotiation answers 406 before the
     * handler runs.
     */
    @Test
    void deleteWorksForAClientThatOnlyAcceptsJson() throws Exception {
        UUID unused = createRole("JsonDelete " + tag());
        send(chairman(), delete("/api/v1/roles/" + unused).accept(MediaType.APPLICATION_JSON), null)
                .andExpect(status().isNoContent());
        assertThat(roleExists(unused)).isFalse();

        UUID inUse = createRole("JsonInUse " + tag(), "MEMBERS_VIEW");
        UUID holder = newMember(DEMO_TENANT, "json-inuse-" + tag() + "@example.com");
        setRoles(chairman(), holder, List.of(inUse)).andExpect(status().isOk());
        send(chairman(), delete("/api/v1/roles/" + inUse).accept(MediaType.APPLICATION_JSON), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
        send(chairman(), delete("/api/v1/roles/" + baseRoleId("MEMBER")).accept(MediaType.APPLICATION_JSON), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_ROLE_IMMUTABLE"));
        send(chairman(), delete("/api/v1/roles/" + UUID.randomUUID()).accept(MediaType.APPLICATION_JSON), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(roleExists(inUse)).isTrue();
    }

    // ---- member roles --------------------------------------------------------------------------------------------------

    @Test
    void replacesTheRolesOfAMemberAndAuditsOldAndNewLists() throws Exception {
        UUID secretary = createRole("Secretary " + tag(), "MEMBERS_VIEW");
        UUID electrician = createRole("Electrician " + tag());
        UUID target = newMember(DEMO_TENANT, "target-" + tag() + "@example.com");
        giveBaseRole(DEMO_TENANT, target, "MEMBER");
        String secretaryName = roleName(secretary);
        String electricianName = roleName(electrician);

        setRoles(chairman(), target, List.of(baseRoleId("MEMBER"), secretary))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(2)))
                .andExpect(jsonPath("$.roles[*].name", containsInAnyOrder("Member", secretaryName)));
        setRoles(chairman(), target, List.of(secretary, electrician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[*].name", containsInAnyOrder(secretaryName, electricianName)));
        // unchanged set: no event
        setRoles(chairman(), target, List.of(electrician, secretary)).andExpect(status().isOk());

        awaitAudit(target, 2);
        Thread.sleep(500); // the unchanged set must not add a third entry
        List<Map<String, Object>> entries = auditEntries(target);
        assertThat(entries).extracting(e -> e.get("action")).containsExactly("MEMBER_ROLES_CHANGED", "MEMBER_ROLES_CHANGED");
        assertThat(entries.get(0).get("actor_member_id")).isEqualTo(DEMO_CHAIRMAN);
        assertThat(entries.get(0).get("entity_type")).isEqualTo("MEMBER");
        JsonNode first = json.readTree((String) entries.get(0).get("changes"));
        assertThat(first).hasSize(1);
        assertThat(change(first, "roles").get("old").asString()).isEqualTo("base:MEMBER");
        assertThat(change(first, "roles").get("new").asString()).isEqualTo(join("base:MEMBER", secretaryName));
        JsonNode second = json.readTree((String) entries.get(1).get("changes"));
        assertThat(change(second, "roles").get("old").asString()).isEqualTo(join("base:MEMBER", secretaryName));
        assertThat(change(second, "roles").get("new").asString()).isEqualTo(join(electricianName, secretaryName));
    }

    @Test
    void emptyingTheRolesAuditsANullNewValue() throws Exception {
        UUID target = newMember(DEMO_TENANT, "empty-" + tag() + "@example.com");
        giveBaseRole(DEMO_TENANT, target, "TREASURER");

        setRoles(chairman(), target, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.roles", empty()));

        JsonNode changes = json.readTree((String) awaitAudit(target, 1).get(0).get("changes"));
        assertThat(change(changes, "roles").get("old").asString()).isEqualTo("base:TREASURER");
        JsonNode newValue = change(changes, "roles").get("new");
        assertThat(newValue == null || newValue.isNull()).isTrue();
    }

    @Test
    void aMemberGetsTheUnionOfThePermissionsOfAllHerRoles() throws Exception {
        String email = "union-" + tag() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID editor = createRole("Editor " + tag(), "MEMBERS_EDIT");
        setRoles(chairman(), member, List.of(baseRoleId("TREASURER"), editor)).andExpect(status().isOk());
        String token = demo(email);

        send(token, get("/api/v1/me"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW",
                        "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW", "MEMBERS_EDIT")));
        send(token, get("/api/v1/audit?size=1"), null).andExpect(status().isOk());          // from Treasurer
        send(token, post("/api/v1/members/" + member + "/restore"), null).andExpect(status().isOk()); // from Editor
        send(token, get("/api/v1/roles"), null).andExpect(status().isForbidden());           // neither has it
    }

    @Test
    void unknownRolesMembersAndOtherCooperativesAreNotFound() throws Exception {
        UUID target = newMember(DEMO_TENANT, "nf-" + tag() + "@example.com");
        UUID otherRole = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = 'MEMBER'", UUID.class));
        UUID keep = createRole("Keep " + tag());
        setRoles(chairman(), target, List.of(keep)).andExpect(status().isOk());

        setRoles(chairman(), target, List.of(UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        setRoles(chairman(), target, List.of(keep, otherRole)).andExpect(status().isNotFound());
        setRoles(chairman(), UUID.randomUUID(), List.of(keep)).andExpect(status().isNotFound());
        setRoles(chairman(), OTHER_MEMBER, List.of(keep)).andExpect(status().isNotFound());

        // nothing was applied
        send(chairman(), get("/api/v1/members/" + target), null).andExpect(jsonPath("$.roles", hasSize(1)));
        Integer otherRoles = one(OTHER_TENANT, Integer.class,
                "select count(*) from member_role where member_id = ?", OTHER_MEMBER);
        assertThat(otherRoles).isEqualTo(1);
    }

    @Test
    void theRoleIdsAreRequired() throws Exception {
        send(chairman(), put("/api/v1/members/" + DEMO_CHAIRMAN + "/roles"), Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- last chairman ---------------------------------------------------------------------------------------------------

    @Test
    void theOnlyActiveChairmanCannotLoseTheChairmanRole() throws Exception {
        UUID memberRole = baseRoleId("MEMBER");
        UUID archivedChairman = newMember(DEMO_TENANT, "archived-chair-" + tag() + "@example.com");
        giveBaseRole(DEMO_TENANT, archivedChairman, "CHAIRMAN");
        archiveMember(DEMO_TENANT, archivedChairman); // an archived chairman does not count

        setRoles(chairman(), DEMO_CHAIRMAN, List.of(memberRole))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"))
                .andExpect(jsonPath("$.detail").value("The cooperative must keep at least one chairman."));
        setRoles(chairman(), DEMO_CHAIRMAN, List.of()).andExpect(status().isConflict());
        send(chairman(), get("/api/v1/members/" + DEMO_CHAIRMAN), null)
                .andExpect(jsonPath("$.roles[0].baseCode").value("CHAIRMAN"));
        // keeping Chairman (alone or with other roles) is fine
        setRoles(chairman(), DEMO_CHAIRMAN, List.of(baseRoleId("CHAIRMAN"), memberRole)).andExpect(status().isOk());
        setRoles(chairman(), DEMO_CHAIRMAN, List.of(baseRoleId("CHAIRMAN"))).andExpect(status().isOk());
    }

    @Test
    void chairmanCanBeRemovedWhileAnotherActiveChairmanRemains() throws Exception {
        UUID second = newMember(DEMO_TENANT, "second-chair-" + tag() + "@example.com");
        giveBaseRole(DEMO_TENANT, second, "CHAIRMAN");

        setRoles(chairman(), second, List.of(baseRoleId("MEMBER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0].name").value("Member"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String chairman() {
        return demo("chairman@demo.local");
    }

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private static String join(String... names) {
        return String.join(", ", names);
    }

    private static String[] allPermissions() {
        return new String[] {"MEMBERS_VIEW", "MEMBERS_EDIT", "ROLES_MANAGE", "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT",
                "OWNERSHIP_EDIT", "IMPORT_RUN", "SETTINGS_MANAGE", "TARIFFS_VIEW", "TARIFFS_MANAGE", "METERS_VIEW",
                "METERS_MANAGE", "READINGS_ENTER", "READINGS_APPROVE", "CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE",
                "PERIODS_CLOSE", "RECALCULATION_RUN"};
    }

    private static Map<String, Object> roleBody(String name, String... permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("permissions", List.of(permissions));
        return body;
    }

    /** A full-replacement body with the seeded description and permissions of a base role, under the given name. */
    private static Map<String, Object> seedBody(String code, String name) {
        BaseRoleSeed seed = SEEDED_BASE_ROLES.stream().filter(b -> b.code().equals(code)).findFirst().orElseThrow();
        Map<String, Object> body = roleBody(name, seed.permissions().toArray(String[]::new));
        body.put("description", seed.description());
        return body;
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private ResultActions setRoles(String bearer, UUID member, List<UUID> roleIds) throws Exception {
        return send(bearer, put("/api/v1/members/" + member + "/roles"), Map.of("roleIds", roleIds));
    }

    /** POST /roles as the chairman; the role is removed after the test. */
    private UUID createRole(String name, String... permissions) throws Exception {
        ResultActions created = send(chairman(), post("/api/v1/roles"), roleBody(name, permissions));
        created.andExpect(status().isCreated());
        return track(created);
    }

    private UUID track(ResultActions created) {
        try {
            UUID id = UUID.fromString(json.readTree(created.andReturn().getResponse().getContentAsString())
                    .get("id").asString());
            createdRoles.add(id);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T one(UUID tenant, Class<T> type, String query, Object... args) {
        return inTenant(tenant, sql -> sql.queryForObject(query, type, args));
    }

    private UUID baseRoleId(String code) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = ?", UUID.class, code));
    }

    private String roleName(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select name from role where id = ?", String.class, id));
    }

    private boolean nameCustomized(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select name_customized from role where id = ?", Boolean.class, id));
    }

    private String roleDescription(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select description from role where id = ?", String.class, id));
    }

    private boolean descriptionCustomized(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select description_customized from role where id = ?", Boolean.class, id));
    }

    private boolean roleExists(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from role where id = ?", Integer.class, id)) == 1;
    }

    private List<String> storedPermissions(UUID roleId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select permission from role_permission where role_id = ?", String.class, roleId));
    }

    private long auditCountForRole(UUID roleId) {
        return auditEntries(roleId).size();
    }

    private static JsonNode find(JsonNode roles, UUID id) {
        for (JsonNode role : roles) {
            if (id.toString().equals(role.get("id").asString())) {
                return role;
            }
        }
        throw new AssertionError("role not listed: " + id);
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asString()));
        return result;
    }

    private List<Map<String, Object>> awaitAudit(UUID entityId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(entityId)).hasSize(count));
        return auditEntries(entityId);
    }

    /** Waits for {@code added} entries beyond the {@code before} that existed (base roles keep entries across tests). */
    private List<Map<String, Object>> awaitNewAudit(UUID entityId, long before, int added) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(auditEntries(entityId)).hasSize((int) before + added));
        return auditEntries(entityId).subList((int) before, (int) before + added);
    }

    private List<Map<String, Object>> auditEntries(UUID entityId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_type, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where entity_id = ? order by occurred_at, id", entityId));
    }

    private static JsonNode change(JsonNode changes, String field) {
        for (JsonNode change : changes) {
            if (field.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + field);
    }
}
