package app.nadel.plots.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The streets an announcement can go to ({@code GET /plots/streets}, feature 017 §2.6): the distinct streets of the
 * cooperative's active plots that are not common land, without surrounding spaces, each once ignoring case, in
 * Ukrainian alphabetical order (Ґ after Г); for holders of {@code PLOTS_VIEW} or {@code ANNOUNCEMENTS_SEND} only.
 */
class StreetsTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    private UUID tenant;

    @BeforeEach
    void cooperativeWithStreets() {
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, 'СТ «Вулиці»')", tenant);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        plot("1", "Вишнева", "PLOT", false);
        plot("2", " вишнева ", "PLOT", false);
        plot("3", "Ґрунтова", "PLOT", false);
        plot("4", "Горіхова", "PLOT", false);
        plot("5", "абрикосова", "PLOT", false);
        plot("6", "Яблунева", "PLOT", false);
        plot("7", "Архівна", "PLOT", true);
        plot("8", "Дорожня", "COMMON", false);
        plot("9", "   ", "PLOT", false);
        plot("10", null, "PLOT", false);
    }

    @AfterEach
    void removeCooperative() {
        for (String table : List.of("audit_entry", "plot_status", "plot", "member_role", "role_permission", "role",
                "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", tenant);
        }
        deleteDataKeys(tenant);
        ownerJdbc().update("delete from tenant where id = ?", tenant);
    }

    @Test
    void theStreetsOfActivePlotsAreDistinctAndInUkrainianOrder() throws Exception {
        streets(person("TREASURER")).andExpect(status().isOk())
                .andExpect(jsonPath("$").value(Matchers.contains("абрикосова", "Вишнева", "Горіхова", "Ґрунтова",
                        "Яблунева")));
    }

    @Test
    void aRoleWithOnlyTheRightToSendAnnouncementsSeesThem() throws Exception {
        streets(customRole("ANNOUNCEMENTS_SEND")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        streets(customRole("PLOTS_VIEW")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void aMemberARoleWithoutEitherRightAndTheOperatorAreRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        for (String bearer : List.of(person("MEMBER"), customRole("MEMBERS_VIEW"), operator)) {
            streets(bearer).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void anotherCooperativeDoesNotSeeTheseStreets() throws Exception {
        String email = "streets-" + UUID.randomUUID() + "@other.local";
        UUID other = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, other, "CHAIRMAN");
        streets(TestTokens.bearer(OTHER_TENANT, "sub-" + email, email)).andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.not(Matchers.hasItem("Ґрунтова"))));
    }

    private ResultActions streets(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/plots/streets").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void plot(String number, String street, String kind, boolean archived) {
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, 600, case when ? then now() end)", UUID.randomUUID(), tenant, number, street,
                kind, archived);
    }

    /** A member with one base role; returns a token. */
    private String person(String baseRole) {
        UUID id = member();
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) "
                + "select ?, ?, id from role where tenant_id = ? and base_code = ?", tenant, id, tenant, baseRole);
        return token(id);
    }

    /** A member whose only role is a custom role with the permissions; returns a token. */
    private String customRole(String... permissions) {
        UUID id = member();
        UUID role = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Role " + role);
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    tenant, role, permission);
        }
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, id,
                role);
        return token(id);
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Вуличний', "
                + "'Тест')", id, tenant);
        insertContacts(tenant, id, emailOf(id));
        return id;
    }

    private String token(UUID member) {
        return TestTokens.bearer(tenant, "sub-" + member, emailOf(member));
    }

    private static String emailOf(UUID member) {
        return "streets-" + member + "@streets.test";
    }
}
