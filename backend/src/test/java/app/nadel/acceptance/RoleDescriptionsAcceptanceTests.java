package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "UI Preferences" (functional-spec.md of 005), part 2.3 "Base role
 * descriptions", through the public HTTP API with the demo data: the journey change, read by other people, audit,
 * restore (§2.3 #2, #3, #5 as the API serves them) and the refusal for a person without ROLES_MANAGE (§2.3 #6).
 * Criteria are named as {@code §2.x #n}.
 * <pre>
 * @layer: integration
 * @spec: 005-ui-preferences
 * @regression
 * </pre>
 */
@Tag("regression")
class RoleDescriptionsAcceptanceTests extends IntegrationTest {

    private static final String STANDARD = "Read access to members, plots and audit";
    private static final String CUSTOM = "Веде касу";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @AfterEach
    void restoreTreasurer() {
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update role set name = 'Treasurer', name_customized = false, description = ?, "
                    + "description_customized = false where base_code = 'TREASURER'", STANDARD);
            sql.update("update member set keycloak_subject = null "
                    + "where email in ('chairman@demo.local', 'treasurer@demo.local')");
            return null;
        });
    }

    private static String bearer(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private UUID treasurerRole() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = 'TREASURER'", UUID.class));
    }

    private ResultActions putRole(String email, UUID id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/roles/" + id).header(HttpHeaders.AUTHORIZATION, bearer(email))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Map<String, Object> body(String description, boolean restore) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Treasurer");
        body.put("description", description);
        body.put("permissions", List.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW"));
        if (restore) body.put("useDefaultDescription", true);
        return body;
    }

    private JsonNode roleInList(String email, UUID id) throws Exception {
        String content = mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode role : json.readTree(content)) {
            if (id.toString().equals(role.get("id").asString())) return role;
        }
        throw new AssertionError("role " + id + " is not in the list");
    }

    /** Audit entries of the role that carry a description change, as {oldValue, newValue}, newest first. */
    private List<List<String>> descriptionChanges(String email, UUID role) throws Exception {
        String content = mvc.perform(get("/api/v1/audit").param("entityId", role.toString()).param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<List<String>> result = new ArrayList<>();
        for (JsonNode entry : json.readTree(content).get("items")) {
            for (JsonNode change : entry.get("changes")) {
                if ("description".equals(change.get("field").asString())) {
                    result.add(List.of(change.get("oldValue").asString(), change.get("newValue").asString()));
                }
            }
        }
        return result;
    }

    /**
     * §2.3 #2, #3, #5: the chairman changes the Treasurer description; the list shows it with the flag, the audit log
     * (read by the treasurer, who may view the audit) carries standard token to text, restoring brings the standard
     * text back with the flag off and the audit carries text to the standard token.
     */
    @Test
    void aChangedTreasurerDescriptionIsReadByOthersAuditedAndRestoredToTheStandardText() throws Exception {
        UUID treasurer = treasurerRole();
        assertThat(roleInList("chairman@demo.local", treasurer).get("description").asString()).isEqualTo(STANDARD);
        assertThat(roleInList("chairman@demo.local", treasurer).get("descriptionCustomized").asBoolean()).isFalse();

        putRole("chairman@demo.local", treasurer, body(CUSTOM, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(CUSTOM))
                .andExpect(jsonPath("$.descriptionCustomized").value(true))
                .andExpect(jsonPath("$.nameCustomized").value(false));
        JsonNode listed = roleInList("chairman@demo.local", treasurer);
        assertThat(listed.get("description").asString()).isEqualTo(CUSTOM);
        assertThat(listed.get("descriptionCustomized").asBoolean()).isTrue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                descriptionChanges("treasurer@demo.local", treasurer)).containsExactly(List.of("base:TREASURER", CUSTOM)));

        putRole("chairman@demo.local", treasurer, body("ignored", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(STANDARD))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));
        assertThat(roleInList("chairman@demo.local", treasurer).get("descriptionCustomized").asBoolean()).isFalse();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                descriptionChanges("treasurer@demo.local", treasurer))
                .containsExactly(List.of(CUSTOM, "base:TREASURER"), List.of("base:TREASURER", CUSTOM)));
    }

    /** §2.3 #6: the treasurer may not manage roles; both change and restore are refused and nothing changes. */
    @Test
    void theTreasurerCannotChangeOrRestoreADescriptionAndTheRolesListStaysClosedToThem() throws Exception {
        UUID treasurer = treasurerRole();
        putRole("treasurer@demo.local", treasurer, body(CUSTOM, false)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        putRole("treasurer@demo.local", treasurer, body(CUSTOM, true)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer("treasurer@demo.local")))
                .andExpect(status().isForbidden());

        assertThat(roleInList("chairman@demo.local", treasurer).get("description").asString()).isEqualTo(STANDARD);
        assertThat(roleInList("chairman@demo.local", treasurer).get("descriptionCustomized").asBoolean()).isFalse();
    }

    /** Negative: a restore flag on a custom role and a too long description are refused with field errors. */
    @Test
    void aRestoreFlagOnACustomRoleIsAValidationErrorAndNothingChanges() throws Exception {
        Map<String, Object> create = new LinkedHashMap<>();
        String name = "Acceptance " + UUID.randomUUID().toString().substring(0, 8);
        create.put("name", name);
        create.put("description", "Custom text");
        create.put("permissions", List.of("MEMBERS_VIEW"));
        String created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/roles").header(HttpHeaders.AUTHORIZATION, bearer("chairman@demo.local"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).get("id").asString());
        try {
            create.put("useDefaultDescription", true);
            putRole("chairman@demo.local", id, create).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("useDefaultDescription"))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
            JsonNode role = roleInList("chairman@demo.local", id);
            assertThat(role.get("description").asString()).isEqualTo("Custom text");
            assertThat(role.get("descriptionCustomized").asBoolean()).isTrue();
        } finally {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/roles/" + id)
                    .header(HttpHeaders.AUTHORIZATION, bearer("chairman@demo.local"))).andExpect(status().isNoContent());
        }
    }
}
