package app.nadel.plots.internal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /plots/map: who sees which fields, and which plots appear. */
class PlotMapTests extends IntegrationTest {

    private static final String MAP = "/api/v1/plots/map";

    @Autowired
    MockMvc mvc;

    @Test
    void memberSeesNumberKindAndStatusButNeitherAreaNorDebt() throws Exception {
        String member = bearerWithBaseRole("MEMBER");

        mapAs(member)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.features", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.number", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.kind", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.status", everyItem(is("GREY"))))
                .andExpect(jsonPath("$.features[*].properties.areaM2").isEmpty())
                .andExpect(jsonPath("$.features[*].properties.debt").isEmpty())
                .andExpect(content().string(not(containsString("areaM2"))))
                .andExpect(content().string(not(containsString("debt"))));
    }

    @Test
    void treasurerSeesAreaAndDebtOfEveryPlot() throws Exception {
        String treasurer = bearerWithBaseRole("TREASURER");

        mapAs(treasurer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[*].properties.areaM2", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.debt", hasSize(31)))
                .andExpect(jsonPath("$.features[?(@.properties.number == 'R-1')].properties.areaM2").value(2970.0))
                .andExpect(jsonPath("$.features[?(@.properties.number == 'R-1')].properties.kind").value("COMMON"))
                .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.areaM2").value(1500.0))
                .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.debt").value("0.00"))
                .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.street")
                        .value("Garden St"));
    }

    @Test
    void platformOperatorHasNoAccess() throws Exception {
        mvc.perform(get(MAP).header(HttpHeaders.AUTHORIZATION,
                        TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void mapRequiresAToken() throws Exception {
        mvc.perform(get(MAP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void archivedPlotsAreNotOnTheMap() throws Exception {
        String treasurer = bearerWithBaseRole("TREASURER");
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where number = '3'"));
        try {
            mapAs(treasurer)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.features", hasSize(30)))
                    .andExpect(jsonPath("$.features[?(@.properties.number == '3')]").isEmpty());
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where number = '3'"));
        }
    }

    @Test
    void anotherCooperativeSeesOnlyItsOwnPlots() throws Exception {
        UUID member = newMember(OTHER_TENANT, "map-other@other.local");
        giveBaseRole(OTHER_TENANT, member, "MEMBER");

        mapAs(TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "map-other@other.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features", hasSize(2)))
                .andExpect(jsonPath("$.features[*].properties.street", everyItem(is("Forest St"))));
    }

    /** A token for a fresh demo-cooperative member that holds the given base role. */
    private String bearerWithBaseRole(String baseCode) {
        String email = "map-" + baseCode.toLowerCase() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private ResultActions mapAs(String bearer) throws Exception {
        return mvc.perform(get(MAP).header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
