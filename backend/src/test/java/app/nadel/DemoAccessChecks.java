package app.nadel;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who can do what for the demo users: the permission checks shared by {@code MeEndpointTests} and the acceptance test
 * that renames every base role. They look at base codes and permissions only, never at role names, so they hold
 * before and after a rename.
 */
public final class DemoAccessChecks {

    private DemoAccessChecks() {
    }

    /** Every check below, in the order of the demo users. */
    public static void all(MockMvc mvc) throws Exception {
        chairmanHasEveryPermission(mvc);
        treasurerHasReadPermissions(mvc);
        memberHasNoPermissions(mvc);
        electricianHasTheMeterPermissions(mvc);
        permissionsAreEnforcedFromTheMembersRoles(mvc);
    }

    public static void chairmanHasEveryPermission(MockMvc mvc) throws Exception {
        getAs(mvc, demo("chairman@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0].baseCode").value("CHAIRMAN"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("MEMBERS_VIEW", "MEMBERS_EDIT",
                        "ROLES_MANAGE", "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN",
                        "SETTINGS_MANAGE", "TARIFFS_VIEW", "TARIFFS_MANAGE", "METERS_VIEW", "METERS_MANAGE", "READINGS_ENTER",
                        "READINGS_APPROVE", "CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE", "PERIODS_CLOSE",
                        "RECALCULATION_RUN", "REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE", "PAYMENTS_VIEW",
                        "PAYMENTS_ENTER", "PAYMENTS_REVERSE", "INVOICES_ISSUE", "CERTIFICATES_ISSUE")));
    }

    public static void treasurerHasReadPermissions(MockMvc mvc) throws Exception {
        getAs(mvc, demo("treasurer@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].baseCode").value("TREASURER"))
                .andExpect(jsonPath("$.permissions",
                        containsInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW", "TARIFFS_VIEW",
                                "METERS_VIEW", "CHARGES_VIEW", "REPORTS_VIEW", "PAYMENTS_VIEW",
                                "PAYMENTS_ENTER", "INVOICES_ISSUE")));
    }

    public static void memberHasNoPermissions(MockMvc mvc) throws Exception {
        getAs(mvc, demo("member@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].baseCode").value("MEMBER"))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    /** The electrician is a Member plus the custom role "Electrician", which views meters and enters readings. */
    public static void electricianHasTheMeterPermissions(MockMvc mvc) throws Exception {
        getAs(mvc, demo("electrician@demo.local"), "/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasSize(2)))
                .andExpect(jsonPath("$.roles[?(@.name == 'Електрик')].baseCode").isEmpty())
                .andExpect(jsonPath("$.roles[?(@.baseCode == 'MEMBER')]", hasSize(1)))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("METERS_VIEW", "READINGS_ENTER")));
    }

    /** Representative allowed and denied endpoints: members, audit and roles, for the three kinds of caller. */
    public static void permissionsAreEnforcedFromTheMembersRoles(MockMvc mvc) throws Exception {
        String treasurer = demo("treasurer@demo.local");
        String member = demo("member@demo.local");
        String chairman = demo("chairman@demo.local");

        getAs(mvc, treasurer, "/api/v1/members").andExpect(status().isOk());
        // MEMBERS_EDIT guards the write endpoints; an unknown id shows whether the guard let the call through (404)
        postAs(mvc, treasurer, "/api/v1/members/" + UUID.randomUUID() + "/archive")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        getAs(mvc, member, "/api/v1/members")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        postAs(mvc, chairman, "/api/v1/members/" + UUID.randomUUID() + "/archive")
                .andExpect(status().isNotFound());

        getAs(mvc, treasurer, "/api/v1/audit").andExpect(status().isOk());
        getAs(mvc, member, "/api/v1/audit")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        getAs(mvc, chairman, "/api/v1/roles").andExpect(status().isOk());
        getAs(mvc, treasurer, "/api/v1/roles")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    /** A token as Keycloak would issue it for a demo user; the subject is stable per e-mail. */
    private static String demo(String email) {
        return TestTokens.bearer(IntegrationTest.DEMO_TENANT, "sub-" + email, email);
    }

    private static ResultActions getAs(MockMvc mvc, String bearer, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static ResultActions postAs(MockMvc mvc, String bearer, String path) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
