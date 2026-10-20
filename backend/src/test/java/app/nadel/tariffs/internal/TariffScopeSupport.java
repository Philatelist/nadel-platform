package app.nadel.tariffs.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the scope tests share: a clean tariffs state, tokens, plots and tariffs created through the API. */
abstract class TariffScopeSupport extends IntegrationTest {

    static final String API = "/api/v1/tariffs";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> roles = new ArrayList<>();
    private final List<UUID> plots = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void restoreTheSeededState() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : plots) {
            owner.update("delete from plot where id = ?", plot); // scope rows cascade
        }
        plots.clear();
        owner.update("delete from tariff_scope_plot where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from tariff_version where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from tariff where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.update("delete from charge_type where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
        owner.queryForList("select seed_charge_types(?)", DEMO_TENANT);
        owner.queryForList("select seed_charge_types(?)", OTHER_TENANT);
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
    }

    String chairman() {
        return demo("chairman@demo.local");
    }

    static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }

    /** A token of a chairman of the other cooperative (a fresh member; removed after the test). */
    String otherChairman() {
        String email = "scope-other-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of a fresh member of the demo cooperative whose only role is a custom role with the permissions. */
    String customRoleToken(String... permissions) {
        String email = "scope-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Scope " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** A per-sotka membership tariff of all plots; returns its id. */
    UUID newTariff(String name) throws Exception {
        return newTariff(typeId(DEMO_TENANT, "MEMBERSHIP"), name, "PER_AREA_SOTKA", "YEARLY");
    }

    UUID newTariff(UUID type, String name, String basis, String frequency) throws Exception {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", "2026-01-01");
        version.put("basis", basis);
        version.put("frequency", frequency);
        version.put("rate", "50");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", type.toString());
        request.put("name", name);
        request.put("firstVersion", version);
        String id = body(send(chairman(), post(API), request).andExpect(status().isCreated())).get("id").asString();
        return UUID.fromString(id);
    }

    UUID typeId(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** The request body of a scope of selected plots. */
    static Map<String, Object> selected(UUID... plotIds) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "SELECTED_PLOTS");
        request.put("plotIds", java.util.Arrays.stream(plotIds).map(UUID::toString).toList());
        return request;
    }

    /** The request body of a scope of all plots with exclusions given as plot, reason, plot, reason, ... */
    static Map<String, Object> allExcept(Object... plotAndReason) {
        List<Map<String, Object>> exclusions = new ArrayList<>();
        for (int i = 0; i < plotAndReason.length; i += 2) {
            Map<String, Object> exclusion = new LinkedHashMap<>();
            exclusion.put("plotId", plotAndReason[i].toString());
            exclusion.put("reason", plotAndReason[i + 1]);
            exclusions.add(exclusion);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "ALL_PLOTS");
        request.put("exclusions", exclusions);
        return request;
    }

    /** A plot of the demo cooperative (the owner role creates it); removed after the test. */
    UUID newPlot(String number, String kind, boolean archived) {
        return newPlot(DEMO_TENANT, number, kind, archived);
    }

    UUID newPlot(UUID tenant, String number, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, kind,
                new BigDecimal("600.00"), archived);
        plots.add(id);
        return id;
    }

    void archivePlot(UUID plot) {
        ownerJdbc().update("update plot set archived_at = now() where id = ?", plot);
    }

    /** The number of rows of the tariff's scope, by kind ({@code SELECTED} or {@code EXCLUDED}). */
    int scopeRows(UUID tariff, String kind) {
        return ownerJdbc().queryForObject("select count(*) from tariff_scope_plot where tariff_id = ? and kind = ?",
                Integer.class, tariff, kind);
    }
}
