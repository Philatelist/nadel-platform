package app.nadel.plots.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /plots/{id}/ownerships, and owners in the plots list: who sees them, their order, and the owner-name search. */
class OwnershipReadTests extends IntegrationTest {

    private static final String PLOTS = "/api/v1/plots";

    @Autowired
    MockMvc mvc;

    @AfterEach
    void removeTemporaryOwnership() {
        // the application role cannot delete ownership rows, the owner role can
        ownerJdbc().update("delete from ownership where id::text like 'eeeeeeee-%'");
    }

    // ---- GET /plots/{id}/ownerships

    @Test
    void coOwnedPlotListsBothCurrentOwnersByNameWithoutHistory() throws Exception {
        for (String bearer : List.of(treasurer(), chairman())) {
            ownershipsAs(bearer, plotId("7"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.plotId").value(plotId("7").toString()))
                    .andExpect(jsonPath("$.current", hasSize(2)))
                    .andExpect(jsonPath("$.current[*].memberName", contains("Bondarenko Andrii", "Moroz Halyna")))
                    .andExpect(jsonPath("$.current[*].share", contains("1/2", "1/2")))
                    .andExpect(jsonPath("$.current[0].memberId").value("aaaaaaaa-0000-0000-0000-000000000003"))
                    .andExpect(jsonPath("$.current[*].validFrom", everyItem(is("2019-04-15"))))
                    .andExpect(jsonPath("$.current[*].validTo").doesNotExist())
                    .andExpect(jsonPath("$.current[*].memberArchived", everyItem(is(false))))
                    .andExpect(jsonPath("$.current[0].basis").value("Purchase, contract of 2019-04-10"))
                    .andExpect(jsonPath("$.current[0].id").exists())
                    .andExpect(jsonPath("$.history", empty()));
        }
    }

    @Test
    void namesLeaveOutThePatronymic() throws Exception {
        String body = ownershipsAs(treasurer(), plotId("7")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Mykolaiovych").doesNotContain("Pavlivna");
    }

    @Test
    void soldPlotShowsTheNewOwnerAndTheArchivedPreviousOwnerInTheHistory() throws Exception {
        ownershipsAs(treasurer(), plotId("9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.current[0].memberName").value("Lytvynenko Svitlana"))
                .andExpect(jsonPath("$.current[0].share").value("1/1"))
                .andExpect(jsonPath("$.current[0].validFrom").value("2025-06-01"))
                .andExpect(jsonPath("$.current[0].memberArchived").value(false))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].memberName").value("Honcharenko Mykola"))
                .andExpect(jsonPath("$.history[0].memberArchived").value(true))
                .andExpect(jsonPath("$.history[0].share").value("1/1"))
                .andExpect(jsonPath("$.history[0].validFrom").value("2015-06-01"))
                .andExpect(jsonPath("$.history[0].validTo").value("2025-05-31"))
                .andExpect(jsonPath("$.history[0].basis").value("Initial allocation"));
    }

    @Test
    void unevenSharesAreWrittenAsFractions() throws Exception {
        ownershipsAs(chairman(), plotId("11"))
                .andExpect(jsonPath("$.current[*].memberName", contains("Kravchenko Dmytro", "Rudenko Bohdan")))
                .andExpect(jsonPath("$.current[*].share", contains("2/3", "1/3")));
    }

    @Test
    void historyIsNewestFirstByStartDate() throws Exception {
        UUID plot = plotId("26");
        insertPastOwner("eeeeeeee-0000-0000-0000-000000000001", plot, "aaaaaaaa-0000-0000-0000-000000000034",
                "2010-01-01", "2012-12-31");
        insertPastOwner("eeeeeeee-0000-0000-0000-000000000002", plot, "aaaaaaaa-0000-0000-0000-000000000035",
                "2013-01-01", "2015-06-30");
        insertPastOwner("eeeeeeee-0000-0000-0000-000000000003", plot, "aaaaaaaa-0000-0000-0000-000000000034",
                "2015-07-01", "2020-02-29");

        ownershipsAs(treasurer(), plot)
                .andExpect(jsonPath("$.current", empty()))
                .andExpect(jsonPath("$.history[*].validFrom", contains("2015-07-01", "2013-01-01", "2010-01-01")))
                .andExpect(jsonPath("$.history[*].validTo", contains("2020-02-29", "2015-06-30", "2012-12-31")))
                .andExpect(jsonPath("$.history[*].memberName",
                        contains("Kharchenko Yevhen", "Stetsenko Larysa", "Kharchenko Yevhen")));
    }

    @Test
    void aPlotWithoutOwnersAndCommonLandHaveEmptyLists() throws Exception {
        String treasurer = treasurer();

        for (String number : List.of("25", "R-1")) {
            ownershipsAs(treasurer, plotId(number))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current", empty()))
                    .andExpect(jsonPath("$.history", empty()));
        }
    }

    @Test
    void aMemberAndAnyoneWithoutPlotsViewAreRefused() throws Exception {
        for (String bearer : List.of(member(), bearerWithoutPermissions())) {
            ownershipsAs(bearer, plotId("7"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                    .andExpect(content().string(not(containsString("Bondarenko"))));
        }
    }

    @Test
    void thePlatformOperatorIsRefused() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        ownershipsAs(operator, plotId("7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aTokenIsRequired() throws Exception {
        mvc.perform(get(PLOTS + "/" + plotId("7") + "/ownerships"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aPlotOfAnotherCooperativeAndAnUnknownIdAreNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '1'", UUID.class, OTHER_TENANT);
        String treasurer = treasurer();

        ownershipsAs(treasurer, foreign)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Melnyk"))));
        ownershipsAs(treasurer, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mvc.perform(get(PLOTS + "/not-a-uuid/ownerships").header(HttpHeaders.AUTHORIZATION, treasurer()))
                .andExpect(status().isBadRequest());
    }

    // ---- owners in the plots list and status

    @Test
    void theListShowsOwnersToTheTreasurerAndOmitsThemForPlotsWithoutOwners() throws Exception {
        listAs(treasurer(), "size=100")
                .andExpect(jsonPath("$.items[?(@.number == '7')].owners[*].name",
                        contains("Bondarenko Andrii", "Moroz Halyna")))
                .andExpect(jsonPath("$.items[?(@.number == '7')].owners[*].share", contains("1/2", "1/2")))
                .andExpect(jsonPath("$.items[?(@.number == '7')].owners[0].memberId",
                        contains("aaaaaaaa-0000-0000-0000-000000000003")))
                .andExpect(jsonPath("$.items[?(@.number == '11')].owners[*].share", contains("2/3", "1/3")))
                .andExpect(jsonPath("$.items[?(@.number == '9')].owners[*].name", contains("Lytvynenko Svitlana")))
                .andExpect(jsonPath("$.items[?(@.number == '1')].owners[*].name", contains("Polishchuk Oksana")))
                .andExpect(jsonPath("$.items[*].owners", hasSize(25)))
                .andExpect(jsonPath("$.items[?(@.number == '25')].owners").isEmpty())
                .andExpect(jsonPath("$.items[?(@.number == 'R-1')].owners").isEmpty())
                .andExpect(content().string(not(containsString("\"owners\":[]"))));
    }

    @Test
    void theListShowsOnlyCurrentOwners() throws Exception {
        listAs(treasurer(), "q=9&size=100")
                .andExpect(jsonPath("$.items[?(@.number == '9')].owners[*].name", contains("Lytvynenko Svitlana")))
                .andExpect(content().string(not(containsString("Honcharenko"))));
    }

    @Test
    void aMemberNeverSeesOwnersInTheList() throws Exception {
        listAs(member(), "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(31)))
                .andExpect(jsonPath("$.items[*].owners").doesNotExist())
                .andExpect(content().string(not(containsString("owners"))))
                .andExpect(content().string(not(containsString("Bondarenko"))))
                .andExpect(content().string(not(containsString("Moroz"))));
    }

    @Test
    void ownedPlotsAreGreenAndTheRestAreGrey() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "status=GREEN&size=100")
                .andExpect(jsonPath("$.totalItems").value(25))
                .andExpect(jsonPath("$.items[*].number", not(org.hamcrest.Matchers.hasItems("25", "R-1"))))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("GREEN"))));
        listAs(treasurer, "status=GREY&size=100")
                .andExpect(jsonPath("$.items[*].number", contains("25", "26", "27", "28", "29", "R-1")));
        cardAs(treasurer, plotId("7")).andExpect(jsonPath("$.status").value("GREEN"));
        cardAs(treasurer, plotId("25")).andExpect(jsonPath("$.status").value("GREY"));
    }

    // ---- search by owner

    @Test
    void treasurerFindsPlotsByAnOwnersLastName() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=Bondarenko")
                .andExpect(jsonPath("$.items[*].number", contains("7", "15")))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].owners[*].name", contains("Bondarenko Andrii", "Moroz Halyna")));
        listAs(treasurer, "q=bONDARENKO").andExpect(jsonPath("$.items[*].number", contains("7", "15")));
        listAs(treasurer, "q=%20bondarenko%20").andExpect(jsonPath("$.items[*].number", contains("7", "15")));
    }

    @Test
    void ownerSearchMatchesFirstAndMiddleNamesToo() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=Halyna").andExpect(jsonPath("$.items[*].number", contains("7")));
        // a patronymic shared by Bondarenko (plots 7 and 15) and Chernenko (plot 21)
        listAs(treasurer, "q=Mykolaiovych").andExpect(jsonPath("$.items[*].number", contains("7", "15", "21")));
    }

    @Test
    void ownerSearchFindsOnlyCurrentOwners() throws Exception {
        // Honcharenko sold plot 9 last year and is archived: no plot is found by that name
        listAs(treasurer(), "q=Honcharenko")
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void ownerSearchCombinesWithTheOtherFilters() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=Bondarenko&kind=COMMON").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=Bondarenko&status=GREY").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=Bondarenko&status=GREEN&size=1")
                .andExpect(jsonPath("$.items[*].number", contains("7")))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void ownerSearchAlsoKeepsMatchingTheNumberAndTheStreet() throws Exception {
        // "15" is the number of one plot and part of nothing else; a text that is also a surname would match both
        listAs(treasurer(), "q=15").andExpect(jsonPath("$.items[*].number", contains("15")));
        listAs(treasurer(), "q=orchard&size=100").andExpect(jsonPath("$.totalItems").value(15));
    }

    @Test
    void ownerSearchTreatsWildcardsAsPlainText() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=%25").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=Bond_renko").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=Bond%25").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void aMemberCannotFindPlotsByOwnerName() throws Exception {
        String member = member();

        listAs(member, "q=Bondarenko&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0));
        listAs(member, "q=Halyna").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void theOwnersSearchStaysInsideTheCooperative() throws Exception {
        // Melnyk owns plot 1 of the other cooperative; the demo treasurer must not find it, and the other
        // cooperative's own full viewer finds only its plot
        listAs(treasurer(), "q=Melnyk").andExpect(jsonPath("$.items", empty()));

        UUID chairman = newMember(OTHER_TENANT, "ownership-other@other.local");
        giveBaseRole(OTHER_TENANT, chairman, "CHAIRMAN");
        String bearer = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "ownership-other@other.local");

        listAs(bearer, "q=Melnyk")
                .andExpect(jsonPath("$.items[*].number", contains("1")))
                .andExpect(jsonPath("$.items[0].owners[*].name", contains("Melnyk Olena")));
        listAs(bearer, "q=Bondarenko").andExpect(jsonPath("$.items", empty()));
    }

    // ---- helpers

    private void insertPastOwner(String id, UUID plot, String member, String from, String to) {
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from, valid_to) values (?::uuid, ?, ?, ?::uuid, 1, 1, ?::date, ?::date)",
                id, DEMO_TENANT, plot, member, from, to);
    }

    private String treasurer() {
        return bearerWithBaseRole("TREASURER");
    }

    private String chairman() {
        return bearerWithBaseRole("CHAIRMAN");
    }

    private String member() {
        return bearerWithBaseRole("MEMBER");
    }

    /** A token for a fresh demo-cooperative member that holds the given base role. */
    private String bearerWithBaseRole(String baseCode) {
        String email = "own-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A signed-in cooperative member who holds no role at all. */
    private String bearerWithoutPermissions() {
        String email = "own-norole-" + UUID.randomUUID() + "@demo.local";
        newMember(DEMO_TENANT, email);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private UUID plotId(String number) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from plot where number = ?", UUID.class, number));
    }

    private ResultActions ownershipsAs(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot + "/ownerships").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions cardAs(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions listAs(String bearer, String query) throws Exception {
        // the query is passed already percent-encoded, so "%25" really arrives as "%"
        return mvc.perform(get(URI.create(PLOTS + (query.isEmpty() ? "" : "?" + query)))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
