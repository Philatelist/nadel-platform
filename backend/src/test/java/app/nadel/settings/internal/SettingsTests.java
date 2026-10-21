package app.nadel.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cooperative settings: defaults, permissions, validation, persistence and audit. */
class SettingsTests extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/settings";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    CooperativeSettings cooperativeSettings;

    private final List<UUID> roles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        // a first sign-in links the demo members to a subject; other tests expect them unlinked (their own subject)
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where email in "
                + "('chairman@demo.local', 'treasurer@demo.local', 'member@demo.local', 'electrician@demo.local')"));
        // the demo cooperative goes back to "never saved", the default other tests rely on
        ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);
    }

    // ---- defaults

    @Test
    void anExistingCooperativeWithoutARowIsOwnOnly() throws Exception {
        assertThat(rowCount(DEMO_TENANT)).isZero();

        read(demo("chairman@demo.local")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::memberPlotStatusVisibility))
                .isEqualTo(PlotStatusVisibility.OWN_ONLY);
    }

    @Test
    void aNewCooperativeWithoutARowIsOwnOnly() throws Exception {
        JdbcTemplate owner = ownerJdbc();
        UUID tenant = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String email = "settings-new-" + tenant + "@example.com";
        owner.update("insert into tenant (id, name) values (?, 'Settings Test Cooperative')", tenant);
        try {
            owner.queryForList("select seed_base_roles(?)", tenant);
            owner.update("insert into member (id, tenant_id, last_name, first_name, email) values (?, ?, 'Test', 'New', ?)",
                    member, tenant, email);
            owner.update("insert into member_role (tenant_id, member_id, role_id) "
                    + "select ?, ?, id from role where tenant_id = ? and base_code = 'CHAIRMAN'", tenant, member, tenant);

            read(TestTokens.bearer(tenant, UUID.randomUUID().toString(), email)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
            assertThat(TenantContext.runAs(tenant, cooperativeSettings::memberPlotStatusVisibility))
                    .isEqualTo(PlotStatusVisibility.OWN_ONLY);
            assertThat(owner.queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                    Integer.class, tenant)).isZero();
        } finally {
            owner.update("delete from member_role where tenant_id = ?", tenant);
            owner.update("delete from member where tenant_id = ?", tenant);
            owner.update("delete from charge_type where tenant_id = ?", tenant);
            owner.update("delete from role_permission where tenant_id = ?", tenant);
            owner.update("delete from role where tenant_id = ?", tenant);
            owner.update("delete from cooperative_settings where tenant_id = ?", tenant);
            owner.update("delete from tenant where id = ?", tenant);
        }
    }

    // ---- read

    @Test
    void everyMemberOfTheCooperativeMayReadTheSettings() throws Exception {
        for (String email : List.of("member@demo.local", "treasurer@demo.local", "chairman@demo.local",
                "electrician@demo.local")) {
            read(demo(email)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        }
    }

    @Test
    void readingNeedsASignedInUser() throws Exception {
        mvc.perform(get(SETTINGS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePlatformOperatorCannotReadOrChangeTheSettings() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        read(operator).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        save(operator, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    // ---- write

    @Test
    void theChairmanChangesTheSettingAndItIsPersisted() throws Exception {
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));

        assertThat(storedValue(DEMO_TENANT)).isEqualTo("ALL_PLOTS");
        read(demo("member@demo.local")).andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        assertThat(TenantContext.runAs(DEMO_TENANT, cooperativeSettings::memberPlotStatusVisibility))
                .isEqualTo(PlotStatusVisibility.ALL_PLOTS);

        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(storedValue(DEMO_TENANT)).isEqualTo("OWN_ONLY");
        assertThat(rowCount(DEMO_TENANT)).isEqualTo(1);
    }

    @Test
    void aCustomRoleWithSettingsManageMayChangeTheSetting() throws Exception {
        String manager = customRoleToken("SETTINGS_MANAGE");

        save(manager, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        assertThat(storedValue(DEMO_TENANT)).isEqualTo("ALL_PLOTS");
    }

    @Test
    void aCustomRoleWithoutSettingsManageMayNot() throws Exception {
        String other = customRoleToken("PLOTS_EDIT");

        save(other, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void theTreasurerAndAMemberCannotChangeTheSetting() throws Exception {
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            save(demo(email), Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void anInvalidOrMissingValueIsRejected() throws Exception {
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "EVERYONE")).andExpect(status().isBadRequest());
        save(chairman, Map.of()).andExpect(status().isBadRequest());
        save(chairman, "not json").andExpect(status().isBadRequest());
        assertThat(rowCount(DEMO_TENANT)).isZero();
    }

    @Test
    void theCooperativeComesFromTheTokenNotFromTheBody() throws Exception {
        save(demo("chairman@demo.local"), Map.of("memberPlotStatusVisibility", "ALL_PLOTS",
                "tenantId", OTHER_TENANT.toString())).andExpect(status().isOk());

        assertThat(rowCount(DEMO_TENANT)).isEqualTo(1);
        assertThat(rowCount(OTHER_TENANT)).isZero();
    }

    // ---- audit

    @Test
    void aChangeIsAuditedWithTheOldAndTheNewValue() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));
        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 2));

        List<Map<String, Object>> entries = auditEntries();
        Map<String, Object> first = entries.get(entries.size() - 2);
        Map<String, Object> second = entries.get(entries.size() - 1);
        assertThat(first.get("action")).isEqualTo("SETTINGS_UPDATED");
        assertThat(first.get("entity_label")).isEqualTo("settings");
        assertThat(first.get("actor_member_id")).isNotNull();
        JsonNode firstChange = json.readTree((String) first.get("changes"));
        assertThat(firstChange).hasSize(1);
        assertThat(firstChange.get(0).get("field").asString()).isEqualTo("memberPlotStatusVisibility");
        assertThat(firstChange.get(0).get("old").asString()).isEqualTo("OWN_ONLY");
        assertThat(firstChange.get(0).get("new").asString()).isEqualTo("ALL_PLOTS");
        JsonNode secondChange = json.readTree((String) second.get("changes"));
        assertThat(secondChange.get(0).get("old").asString()).isEqualTo("ALL_PLOTS");
        assertThat(secondChange.get(0).get("new").asString()).isEqualTo("OWN_ONLY");
    }

    @Test
    void savingTheCurrentValueWritesNoAuditEntryAndNoRow() throws Exception {
        long before = auditCount();
        String chairman = demo("chairman@demo.local");

        // the default, saved explicitly
        save(chairman, Map.of("memberPlotStatusVisibility", "OWN_ONLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("OWN_ONLY"));
        assertThat(rowCount(DEMO_TENANT)).isZero();

        // a stored value, saved again
        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(before + 1));
        save(chairman, Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditCount()).isEqualTo(before + 1);
    }

    // ---- helpers

    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    private ResultActions read(String bearer) throws Exception {
        return mvc.perform(get(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions save(String bearer, Object body) throws Exception {
        String content = body instanceof String text ? text : json.writeValueAsString(body);
        return mvc.perform(put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with one permission. */
    private String customRoleToken(String permission) {
        String email = "settings-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT,
                    "Settings " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    DEMO_TENANT, role, permission);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private int rowCount(UUID tenant) {
        return ownerJdbc().queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                Integer.class, tenant);
    }

    private String storedValue(UUID tenant) {
        return ownerJdbc().queryForObject(
                "select member_plot_status_visibility from cooperative_settings where tenant_id = ?",
                String.class, tenant);
    }

    private long auditCount() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from audit_entry where entity_type = 'SETTINGS' and entity_id = ?", Long.class,
                DEMO_TENANT));
    }

    private List<Map<String, Object>> auditEntries() {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_label, cast(changes as text) as changes from audit_entry "
                        + "where entity_type = 'SETTINGS' and entity_id = ? order by occurred_at, id", DEMO_TENANT));
    }
}
