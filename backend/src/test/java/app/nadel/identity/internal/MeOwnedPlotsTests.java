package app.nadel.identity.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /me lists the plots the signed-in member owns now. */
class MeOwnedPlotsTests extends IntegrationTest {

    private static final UUID MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @AfterEach
    void unlinkDemoMembers() {
        // first sign-in links the demo members; restore the seeded state for other tests
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set keycloak_subject = null where id in (?, ?)", CHAIRMAN, MEMBER));
        inTenant(OTHER_TENANT, sql -> sql.update(
                "update member set keycloak_subject = null where id = ?", OTHER_MEMBER));
    }

    @Test
    void theDemoMemberGetsPlot7AndPlot15WithTheirShares() throws Exception {
        meAs(demo("member@demo.local", DEMO_TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedPlots", hasSize(2)))
                .andExpect(jsonPath("$.ownedPlots[0].number").value("7"))
                .andExpect(jsonPath("$.ownedPlots[0].street").value("вул. Садова"))
                .andExpect(jsonPath("$.ownedPlots[0].share").value("1/2"))
                .andExpect(jsonPath("$.ownedPlots[0].plotId").exists())
                .andExpect(jsonPath("$.ownedPlots[1].number").value("15"))
                .andExpect(jsonPath("$.ownedPlots[1].street").value("вул. Вишнева"))
                .andExpect(jsonPath("$.ownedPlots[1].share").value("1/1"));
    }

    @Test
    void aMemberWhoOwnsNothingGetsAnEmptyList() throws Exception {
        meAs(demo("chairman@demo.local", DEMO_TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedPlots", empty()));
    }

    @Test
    void thePlatformOperatorGetsAnEmptyList() throws Exception {
        meAs(TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformOperator").value(true))
                .andExpect(jsonPath("$.ownedPlots", empty()));
    }

    @Test
    void onlyOwnPlotsOfTheOwnCooperativeAreListed() throws Exception {
        meAs(demo("member@other.local", OTHER_TENANT))
                .andExpect(jsonPath("$.ownedPlots", hasSize(1)))
                .andExpect(jsonPath("$.ownedPlots[0].number").value("1"))
                .andExpect(jsonPath("$.ownedPlots[0].street").value("вул. Лісова"));
    }

    @Test
    void aPlotThatWasSoldIsNoLongerListed() throws Exception {
        // the demo member sells plot 15 (ends the record); a record that ended is not an owned plot
        ownerJdbc().update("update ownership set valid_to = date '2025-12-31' "
                + "where id = 'dddddddd-0000-0000-0000-000000000020'");
        try {
            meAs(demo("member@demo.local", DEMO_TENANT))
                    .andExpect(jsonPath("$.ownedPlots[*].number", contains("7")));
        } finally {
            ownerJdbc().update("update ownership set valid_to = null "
                    + "where id = 'dddddddd-0000-0000-0000-000000000020'");
        }
    }

    // ---- helpers

    private static String demo(String email, UUID tenant) {
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    private ResultActions meAs(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
