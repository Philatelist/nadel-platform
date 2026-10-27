package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may list, run and download the reports (feature 013, functional spec 2.1 and 2.14): the chairman and the
 * treasurer have {@code REPORTS_VIEW}; a member, the electrician and the platform operator are refused with 403 for
 * every report, defined or not, and nothing is audited; without a token the answer is 401.
 */
class ReportsAccessTests extends IntegrationTest {

    private static final List<String> PATHS = List.of("/api/v1/reports", "/api/v1/reports/charges-by-month",
            "/api/v1/reports/charges-by-month.xlsx");

    @Autowired
    private MockMvc mvc;

    @Test
    void theChairmanAndTheTreasurerListTheReports() throws Exception {
        listReports(demo("chairman@demo.local"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
        listReports(demo("treasurer@demo.local")).andExpect(status().isOk());
    }

    @Test
    void aMemberAndTheElectricianAreRefused() throws Exception {
        for (String email : new String[] {"member@demo.local", "electrician@demo.local"}) {
            listReports(demo(email))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        listReports(operator)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aRequestWithoutATokenIsUnauthenticated() throws Exception {
        for (String path : PATHS) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void theChairmanAndTheTreasurerRunAReportAndDownloadIt() throws Exception {
        listReports(demo("chairman@demo.local")).andExpect(jsonPath("$[0].code").value("charges-by-month"));
        for (String email : new String[] {"chairman@demo.local", "treasurer@demo.local"}) {
            mvc.perform(get(PATHS.get(1)).header(HttpHeaders.AUTHORIZATION, demo(email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("charges-by-month"));
            mvc.perform(get(PATHS.get(2)).header(HttpHeaders.AUTHORIZATION, demo(email)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(ReportsController.XLSX));
        }
    }

    @Test
    void withoutReportsViewNoReportRunsAndNothingIsAudited() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        List<String> callers = List.of(demo("member@demo.local"), demo("electrician@demo.local"), operator);
        // entries of other tests of this class are written asynchronously: let them land first
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject("select count(*) from "
                + "event_publication where completion_date is null and serialized_event like ?", Integer.class,
                "%" + DEMO_TENANT + "%") == 0);
        int before = reportEntries();
        for (String caller : callers) {
            // every report answers alike: the permission is checked first
            for (String path : List.of(PATHS.get(1), PATHS.get(2), "/api/v1/reports/land-tax",
                    "/api/v1/reports/corrections.xlsx", "/api/v1/reports/water", "/api/v1/reports/debtors.xlsx")) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, caller))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
        assertThat(reportEntries()).isEqualTo(before);
    }

    private static int reportEntries() {
        return ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and action = "
                + "'REPORT_GENERATED'", Integer.class, DEMO_TENANT);
    }

    private ResultActions listReports(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** A token as Keycloak would issue it for a demo user; the subject is stable per e-mail. */
    private static String demo(String email) {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + email, email);
    }
}
