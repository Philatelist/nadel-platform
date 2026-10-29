package app.nadel.acceptance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Role Names" (functional-spec.md of 004), through the public HTTP API with the
 * demo data. They fill what the per-slice tests do not own: the name rules on a base role (2.1 #4) and the journey
 * rename, read everywhere, restore (2.1 #1, 2.3 #2, 2.6 #1). Criteria are named as {@code §2.x #n}.
 * <pre>
 * @layer: integration
 * @spec: 004-role-names
 * @regression
 * </pre>
 */
@Tag("regression")
class RoleNamesAcceptanceTests extends IntegrationTest {

    static final UUID TREASURER_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @AfterEach
    void restoreTreasurer() {
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update role set name = 'Treasurer', name_customized = false, "
                    + "description = 'Read access to members, plots and audit', description_customized = false "
                    + "where base_code = 'TREASURER'");
            return null;
        });
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local");
    }

    private static String bearer(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private UUID treasurerRole() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from role where base_code = 'TREASURER'", UUID.class));
    }

    private String storedName(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select name from role where id = ?", String.class, id));
    }

    private ResultActions putRole(UUID id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/roles/" + id).header(HttpHeaders.AUTHORIZATION, bearer("chairman@demo.local"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Map<String, Object> treasurerBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "Read access to members, plots and audit");
        body.put("permissions", List.of("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW", "METERS_VIEW",
                "CHARGES_VIEW", "REPORTS_VIEW", "PAYMENTS_VIEW", "PAYMENTS_ENTER", "INVOICES_ISSUE"));
        return body;
    }

    /** 2.1 #4 on a base role: empty and 61-character names are refused with a field error and the name stays. */
    @Test
    void aBaseRoleNameIsRefusedWhenEmptyBlankOrLongerThanSixtyAndStaysUnchanged() throws Exception {
        UUID treasurer = treasurerRole();
        for (String blank : List.of("", "   ")) {
            putRole(treasurer, treasurerBody(blank)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        putRole(treasurer, treasurerBody("x".repeat(61))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(storedName(treasurer)).isEqualTo("Treasurer");

        // exactly sixty characters is accepted
        putRole(treasurer, treasurerBody("y".repeat(60))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("y".repeat(60)));
    }

    /** 2.1 #1, 2.6 #1, 2.3 #2: renamed to "Бухгалтер", every read shows it, restore brings the standard name back. */
    @Test
    void aRenamedTreasurerRoleIsReadUnderTheNewNameEverywhereAndRestoredToItsStandardName() throws Exception {
        UUID treasurer = treasurerRole();
        putRole(treasurer, treasurerBody("Бухгалтер")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Бухгалтер")).andExpect(jsonPath("$.nameCustomized").value(true));

        String treasurerToken = bearer("treasurer@demo.local");
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, treasurerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.baseCode == 'TREASURER')].name").value("Бухгалтер"))
                .andExpect(jsonPath("$.roles[?(@.baseCode == 'TREASURER')].nameCustomized").value(true));
        mvc.perform(get("/api/v1/members/" + TREASURER_MEMBER).header(HttpHeaders.AUTHORIZATION, treasurerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.baseCode == 'TREASURER')].name").value("Бухгалтер"));
        mvc.perform(get("/api/v1/members").param("q", "Шевченко")
                        .header(HttpHeaders.AUTHORIZATION, bearer("chairman@demo.local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].roles[?(@.baseCode == 'TREASURER')].name").value("Бухгалтер"));

        Map<String, Object> restore = treasurerBody("ignored");
        restore.put("useDefaultName", true);
        putRole(treasurer, restore).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Treasurer")).andExpect(jsonPath("$.nameCustomized").value(false))
                .andExpect(jsonPath("$.descriptionCustomized").value(false));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, treasurerToken))
                .andExpect(jsonPath("$.roles[?(@.baseCode == 'TREASURER')].nameCustomized").value(false));
    }
}
