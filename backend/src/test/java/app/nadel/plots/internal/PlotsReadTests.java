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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET /plots and GET /plots/{id}: paging, natural order, filters, search and who sees which fields. */
class PlotsReadTests extends IntegrationTest {

    private static final String PLOTS = "/api/v1/plots";

    /** The demo cooperative's plots in natural order: 1..12, 12а (Cyrillic а), 13..29 and the road R-1. */
    private static final List<String> DEMO_ORDER = demoOrder();

    @Autowired
    MockMvc mvc;

    private static List<String> demoOrder() {
        var order = new ArrayList<String>();
        IntStream.rangeClosed(1, 12).forEach(n -> order.add(String.valueOf(n)));
        order.add("12а");
        IntStream.rangeClosed(13, 29).forEach(n -> order.add(String.valueOf(n)));
        order.add("R-1");
        return order;
    }

    // ---- paging and order

    @Test
    void listsEveryPlotInNaturalOrder() throws Exception {
        listAs(treasurer(), "size=100")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[*].number", contains(DEMO_ORDER.toArray(String[]::new))))
                .andExpect(jsonPath("$.totalItems").value(31))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void firstPageHasTwentyPlotsByDefaultAndTheSecondTheRest() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].number", contains(DEMO_ORDER.subList(0, 20).toArray(String[]::new))))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(31))
                .andExpect(jsonPath("$.totalPages").value(2));
        listAs(treasurer, "page=1")
                .andExpect(jsonPath("$.items[*].number", contains(DEMO_ORDER.subList(20, 31).toArray(String[]::new))))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void pagesOfTenWalkTheWholeListWithoutGapsOrRepeats() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "size=10&page=0")
                .andExpect(jsonPath("$.items", hasSize(10)))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.items[0].number").value("1"));
        listAs(treasurer, "size=10&page=2")
                .andExpect(jsonPath("$.items[*].number", contains(DEMO_ORDER.subList(20, 30).toArray(String[]::new))));
        listAs(treasurer, "size=10&page=3")
                .andExpect(jsonPath("$.items[*].number", contains("R-1")));
        listAs(treasurer, "size=10&page=4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(31));
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "size=0").andExpect(status().isBadRequest());
        listAs(treasurer, "size=101").andExpect(status().isBadRequest());
        listAs(treasurer, "page=-1").andExpect(status().isBadRequest());
        listAs(treasurer, "q=" + "x".repeat(101)).andExpect(status().isBadRequest());
        listAs(treasurer, "kind=ROAD").andExpect(status().isBadRequest());
    }

    // ---- filters

    @Test
    void kindFilterSelectsCommonLandOrPlots() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "kind=COMMON&size=100")
                .andExpect(jsonPath("$.items[*].number", contains("R-1")))
                .andExpect(jsonPath("$.items[0].kind").value("COMMON"))
                .andExpect(jsonPath("$.totalItems").value(1));
        listAs(treasurer, "kind=PLOT&size=100")
                .andExpect(jsonPath("$.totalItems").value(30))
                .andExpect(jsonPath("$.items[*].kind", everyItem(is("PLOT"))));
    }

    @Test
    void statusFilterUsesTheDisplayedStatus() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "status=GREY&size=100")
                .andExpect(jsonPath("$.totalItems").value(31))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("GREY"))));
        listAs(treasurer, "status=RED").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "status=GREEN").andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void statusFilterAppliesTheCachedStatusOfAPlotButNeverOfCommonLand() throws Exception {
        String treasurer = treasurer();
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update plot_status set status = 'RED' where plot_id in "
                    + "(select id from plot where number in ('3', 'R-1'))");
            return null;
        });
        try {
            listAs(treasurer, "status=RED")
                    .andExpect(jsonPath("$.items[*].number", contains("3")))
                    .andExpect(jsonPath("$.items[0].status").value("RED"));
            listAs(treasurer, "status=GREY&size=100")
                    .andExpect(jsonPath("$.totalItems").value(30))
                    .andExpect(jsonPath("$.items[?(@.number == 'R-1')].status").value("GREY"));
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot_status set status = 'GREY'"));
        }
    }

    @Test
    void archivedPlotsAreListedOnlyOnRequest() throws Exception {
        String treasurer = treasurer();
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where number = '3'"));
        try {
            listAs(treasurer, "size=100")
                    .andExpect(jsonPath("$.totalItems").value(30))
                    .andExpect(jsonPath("$.items[?(@.number == '3')]").isEmpty());
            listAs(treasurer, "includeArchived=false&size=100")
                    .andExpect(jsonPath("$.totalItems").value(30));
            listAs(treasurer, "includeArchived=true&size=100")
                    .andExpect(jsonPath("$.totalItems").value(31))
                    .andExpect(jsonPath("$.items[2].number").value("3"))
                    .andExpect(jsonPath("$.items[2].archived").value(true))
                    .andExpect(jsonPath("$.items[0].archived").value(false));
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where number = '3'"));
        }
    }

    // ---- search

    @Test
    void searchMatchesTheNumberAndKeepsNaturalOrder() throws Exception {
        listAs(treasurer(), "q=12")
                .andExpect(jsonPath("$.items[*].number", contains("12", "12а")))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void searchIgnoresLetterCaseAndSurroundingSpaces() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=12%D0%90").andExpect(jsonPath("$.items[*].number", contains("12а")));
        listAs(treasurer, "q=%20r-1%20").andExpect(jsonPath("$.items[*].number", contains("R-1")));
        listAs(treasurer, "q=GARDEN&size=100").andExpect(jsonPath("$.totalItems").value(16));
    }

    @Test
    void searchMatchesTheStreet() throws Exception {
        listAs(treasurer(), "q=orchard&size=100")
                .andExpect(jsonPath("$.totalItems").value(15))
                .andExpect(jsonPath("$.items[*].street", everyItem(is("Orchard St"))))
                .andExpect(jsonPath("$.items[0].number").value("15"));
    }

    @Test
    void searchTreatsLikeWildcardsAsPlainText() throws Exception {
        String treasurer = treasurer();

        listAs(treasurer, "q=%25").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=_").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=!").andExpect(jsonPath("$.items", empty()));
        listAs(treasurer, "q=1%25").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void searchWithNoMatchReturnsAnEmptyPage() throws Exception {
        listAs(treasurer(), "q=nothing-like-this")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void fullViewersCanSearchByCadastralNumber() throws Exception {
        listAs(treasurer(), "q=4810136300:02:002&size=100")
                .andExpect(jsonPath("$.items[*].number", contains("17", "21", "25")))
                .andExpect(jsonPath("$.items[0].cadastralNumber").value("4810136300:02:002:0017"));
        listAs(chairman(), "q=0021")
                .andExpect(jsonPath("$.items[*].number", contains("21")));
    }

    @Test
    void cadastralSearchFindsNothingForAMember() throws Exception {
        listAs(member(), "q=4810136300:02:002&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0));
        listAs(member(), "q=0021").andExpect(jsonPath("$.items", empty()));
    }

    // ---- visibility

    @Test
    void memberSeesNumberStreetKindStatusAndNothingElseInTheList() throws Exception {
        listAs(member(), "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(31)))
                .andExpect(jsonPath("$.items[*].id", hasSize(31)))
                .andExpect(jsonPath("$.items[*].number", hasSize(31)))
                .andExpect(jsonPath("$.items[*].kind", hasSize(31)))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("GREY"))))
                .andExpect(jsonPath("$.items[*].archived", everyItem(is(false))))
                .andExpect(jsonPath("$.items[?(@.number == '9')].street").value("Garden St"))
                .andExpect(jsonPath("$.items[*].areaM2").doesNotExist())
                .andExpect(jsonPath("$.items[*].cadastralNumber").doesNotExist())
                .andExpect(jsonPath("$.items[*].hasBuilding").doesNotExist())
                .andExpect(content().string(not(containsString("areaM2"))))
                .andExpect(content().string(not(containsString("cadastralNumber"))))
                .andExpect(content().string(not(containsString("hasBuilding"))))
                .andExpect(content().string(not(containsString("4810136300"))));
    }

    @Test
    void memberDoesNotSeeArchivedPlotsEvenWhenAsking() throws Exception {
        String member = member();
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where number = '3'"));
        try {
            listAs(member, "includeArchived=true&size=100")
                    .andExpect(jsonPath("$.totalItems").value(30))
                    .andExpect(jsonPath("$.items[?(@.number == '3')]").isEmpty());
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where number = '3'"));
        }
    }

    @Test
    void treasurerAndChairmanSeeEveryFieldInTheList() throws Exception {
        for (String bearer : List.of(treasurer(), chairman())) {
            listAs(bearer, "size=100")
                    .andExpect(jsonPath("$.items[*].areaM2", hasSize(31)))
                    .andExpect(jsonPath("$.items[*].hasBuilding", hasSize(31)))
                    .andExpect(jsonPath("$.items[*].cadastralNumber", hasSize(8)))
                    .andExpect(jsonPath("$.items[?(@.number == '9')].areaM2").value(1500.0))
                    .andExpect(jsonPath("$.items[?(@.number == '9')].hasBuilding").value(true))
                    .andExpect(jsonPath("$.items[?(@.number == '1')].cadastralNumber")
                            .value("4810136300:02:001:0001"))
                    .andExpect(jsonPath("$.items[?(@.number == 'R-1')].areaM2").value(2970.0));
        }
    }

    @Test
    void treasurerOpensACardWithEveryField() throws Exception {
        cardAs(treasurer(), plotId("9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("9"))
                .andExpect(jsonPath("$.street").value("Garden St"))
                .andExpect(jsonPath("$.kind").value("PLOT"))
                .andExpect(jsonPath("$.status").value("GREY"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.areaM2").value(1500.0))
                .andExpect(jsonPath("$.hasBuilding").value(true))
                .andExpect(jsonPath("$.notes").value("Corner plot next to the entrance gate"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        cardAs(chairman(), plotId("1"))
                .andExpect(jsonPath("$.cadastralNumber").value("4810136300:02:001:0001"));
    }

    @Test
    void memberOpensARestrictedCard() throws Exception {
        cardAs(member(), plotId("9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("9"))
                .andExpect(jsonPath("$.street").value("Garden St"))
                .andExpect(jsonPath("$.kind").value("PLOT"))
                .andExpect(jsonPath("$.status").value("GREY"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.areaM2").doesNotExist())
                .andExpect(jsonPath("$.cadastralNumber").doesNotExist())
                .andExpect(jsonPath("$.hasBuilding").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(content().string(not(containsString("Corner plot"))));
    }

    @Test
    void commonLandCardShowsTheCommonLandStatus() throws Exception {
        cardAs(member(), plotId("R-1"))
                .andExpect(jsonPath("$.kind").value("COMMON"))
                .andExpect(jsonPath("$.status").value("GREY"));
    }

    @Test
    void archivedCardIsHiddenFromAMemberButOpenForTheTreasurer() throws Exception {
        String member = member();
        String treasurer = treasurer();
        UUID id = plotId("3");
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where number = '3'"));
        try {
            cardAs(member, id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            cardAs(treasurer, id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.archived").value(true))
                    .andExpect(jsonPath("$.archivedAt").exists());
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where number = '3'"));
        }
    }

    @Test
    void aPlotOfAnotherCooperativeAndAnUnknownIdAreNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject("select id from plot where tenant_id = ? limit 1", UUID.class,
                OTHER_TENANT);
        String treasurer = treasurer();
        String member = member();

        for (String bearer : List.of(treasurer, member)) {
            cardAs(bearer, foreign)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            cardAs(bearer, UUID.randomUUID()).andExpect(status().isNotFound());
        }
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mvc.perform(get(PLOTS + "/not-a-uuid").header(HttpHeaders.AUTHORIZATION, treasurer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherCooperativeListsOnlyItsOwnPlots() throws Exception {
        UUID other = newMember(OTHER_TENANT, "plots-other@other.local");
        giveBaseRole(OTHER_TENANT, other, "MEMBER");

        listAs(TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), "plots-other@other.local"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].street", everyItem(is("Forest St"))));
    }

    // ---- access

    @Test
    void platformOperatorHasNoAccess() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        mvc.perform(get(PLOTS).header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(get(PLOTS + "/" + plotId("9")).header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void readingRequiresAToken() throws Exception {
        mvc.perform(get(PLOTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(PLOTS + "/" + plotId("9")))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers

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
        String email = "plots-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private UUID plotId(String number) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from plot where number = ?", UUID.class, number));
    }

    private ResultActions listAs(String bearer, String query) throws Exception {
        // the query is passed already percent-encoded, so "%25" really arrives as "%"
        return mvc.perform(get(URI.create(PLOTS + (query.isEmpty() ? "" : "?" + query)))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions cardAs(String bearer, UUID id) throws Exception {
        return mvc.perform(get(PLOTS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
