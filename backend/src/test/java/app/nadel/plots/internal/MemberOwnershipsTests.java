package app.nadel.plots.internal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /members/{id}/ownerships: who may ask, the split into current and past plots, and their order. */
class MemberOwnershipsTests extends OwnershipTestSupport {

    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID HONCHARENKO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000020");
    private static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @AfterEach
    void unlinkDemoMember() {
        ownerJdbc().update("update member set keycloak_subject = null where id = ?", DEMO_MEMBER);
    }

    @Test
    void aMemberSeesTheirOwnPlotsInNaturalOrderOfTheNumber() throws Exception {
        // "7" sorts before "15" naturally, although "15" is the smaller text
        membersOwnershipsAs(demoMemberToken(), DEMO_MEMBER)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.current", hasSize(2)))
                .andExpect(jsonPath("$.current[0].plotNumber").value("7"))
                .andExpect(jsonPath("$.current[0].street").value("вул. Садова"))
                .andExpect(jsonPath("$.current[0].share").value("1/2"))
                .andExpect(jsonPath("$.current[0].validFrom").value("2019-04-15"))
                .andExpect(jsonPath("$.current[0].validTo").doesNotExist())
                .andExpect(jsonPath("$.current[0].ownershipId").value("dddddddd-0000-0000-0000-000000000008"))
                .andExpect(jsonPath("$.current[0].plotId").exists())
                .andExpect(jsonPath("$.current[1].plotNumber").value("15"))
                .andExpect(jsonPath("$.current[1].street").value("вул. Вишнева"))
                .andExpect(jsonPath("$.current[1].share").value("1/1"))
                .andExpect(jsonPath("$.current[1].validFrom").value("2021-03-25"))
                .andExpect(jsonPath("$.past", empty()))
                // no co-owner and no basis: only the member's own records
                .andExpect(content().string(not(containsString("Мороз"))))
                .andExpect(content().string(not(containsString("Purchase"))));
    }

    @Test
    void viewersOfPlotsAndViewersOfMembersSeeAnyMembersPlots() throws Exception {
        for (String bearer : new String[] {treasurer(), chairman(), customRoleToken("MEMBERS_VIEW"),
                customRoleToken("PLOTS_VIEW")}) {
            membersOwnershipsAs(bearer, DEMO_MEMBER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current[*].plotNumber").value(org.hamcrest.Matchers.contains("7", "15")));
        }
    }

    @Test
    void aFormerOwnersPastPlotIsListedWithItsPeriodForTheChairman() throws Exception {
        membersOwnershipsAs(chairman(), HONCHARENKO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", empty()))
                .andExpect(jsonPath("$.past", hasSize(1)))
                .andExpect(jsonPath("$.past[0].plotNumber").value("9"))
                .andExpect(jsonPath("$.past[0].street").value("вул. Садова"))
                .andExpect(jsonPath("$.past[0].share").value("1/1"))
                .andExpect(jsonPath("$.past[0].validFrom").value("2015-06-01"))
                .andExpect(jsonPath("$.past[0].validTo").value("2025-05-31"))
                .andExpect(jsonPath("$.past[0].ownershipId").value("dddddddd-0000-0000-0000-000000000011"));
    }

    @Test
    void pastPlotsComeNewestEndFirstAndStayApartFromCurrentOnes() throws Exception {
        UUID owner = demoMember("Seller", "Sam");
        UUID first = newPlot();
        UUID second = newPlot();
        UUID third = newPlot();
        UUID current = newPlot();
        seedOwner(first, owner, "1/1", "2010-01-01", "2012-12-31");
        seedOwner(second, owner, "1/3", "2013-01-01", "2020-02-29");
        seedOwner(third, owner, "1/1", "2012-01-01", "2016-06-30");
        seedOwner(current, owner, "2/3", "2021-01-01", null);

        membersOwnershipsAs(chairman(), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.current[0].plotId").value(current.toString()))
                .andExpect(jsonPath("$.current[0].share").value("2/3"))
                .andExpect(jsonPath("$.past", hasSize(3)))
                .andExpect(jsonPath("$.past[*].plotId").value(org.hamcrest.Matchers.contains(
                        second.toString(), third.toString(), first.toString())))
                .andExpect(jsonPath("$.past[*].validTo").value(org.hamcrest.Matchers.contains(
                        "2020-02-29", "2016-06-30", "2012-12-31")))
                .andExpect(jsonPath("$.past[1].street").doesNotExist());
    }

    @Test
    void aMemberWithoutPlotsGetsTwoEmptyLists() throws Exception {
        UUID owner = demoMember("Landless", "Lev");

        membersOwnershipsAs(chairman(), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", empty()))
                .andExpect(jsonPath("$.past", empty()));
    }

    @Test
    void aMemberWithoutPermissionsCannotAskAboutSomeoneElse() throws Exception {
        for (String bearer : new String[] {member(), customRoleToken("AUDIT_VIEW"), noRoleToken()}) {
            membersOwnershipsAs(bearer, DEMO_MEMBER)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(content().string(not(containsString("Garden"))));
        }
    }

    @Test
    void aMembersOwnPlotsNeedNoPermission() throws Exception {
        UUID owner = demoMember("Lonely", "Lena");
        UUID plot = newPlot();
        seedOwner(plot, owner, "1/1", "2020-05-05", null);
        giveBaseRole(DEMO_TENANT, owner, "MEMBER");
        String email = memberEmail(DEMO_TENANT, owner);

        membersOwnershipsAs(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[0].plotId").value(plot.toString()));
    }

    @Test
    void aMemberOfAnotherCooperativeAndAnUnknownIdAreNotFound() throws Exception {
        String chairman = chairman();

        membersOwnershipsAs(chairman, OTHER_MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Мельник"))));
        membersOwnershipsAs(chairman, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void anotherCooperativesChairmanCannotReadDemoMembers() throws Exception {
        UUID chairman = newMember(OTHER_TENANT, "memberships-other@other.local");
        giveBaseRole(OTHER_TENANT, chairman, "CHAIRMAN");
        String bearer = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "memberships-other@other.local");

        membersOwnershipsAs(bearer, DEMO_MEMBER).andExpect(status().isNotFound());
        membersOwnershipsAs(bearer, OTHER_MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.current[0].plotNumber").value("1"));
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        membersOwnershipsAs(operator, DEMO_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aTokenIsRequiredAndTheIdMustBeAUuid() throws Exception {
        mvc.perform(get("/api/v1/members/" + DEMO_MEMBER + "/ownerships"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/members/not-a-uuid/ownerships").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers

    private String demoMemberToken() {
        return TestTokens.bearer(DEMO_TENANT, "sub-member@demo.local", "member@demo.local");
    }

    /** A signed-in cooperative member who holds no role at all. */
    private String noRoleToken() {
        String email = "memberships-norole-" + UUID.randomUUID() + "@demo.local";
        newMember(DEMO_TENANT, email);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private ResultActions membersOwnershipsAs(String bearer, UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/" + member + "/ownerships")
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
