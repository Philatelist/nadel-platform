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

import app.nadel.TestTokens;
import app.nadel.settings.PlotStatusVisibility;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What each kind of viewer sees of plots, under both values of the cooperative setting: the demo member (owns plot 7
 * with a co-owner, and plot 15), a member who used to own plot 9, the treasurer (PLOTS_VIEW), in the list, the card,
 * the map, the status filter and the owners of a plot.
 */
class OwnershipVisibilityTests extends OwnershipTestSupport {

    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final String DEMO_MEMBER_EMAIL = "member@demo.local";

    @Autowired
    MockMvc mvc;

    @AfterEach
    void restoreDemoState() {
        // the demo cooperative goes back to "never saved" (own plots only); the first sign-in linked the demo member
        ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);
        ownerJdbc().update("update member set keycloak_subject = null where id = ?", DEMO_MEMBER);
    }

    // ---- list

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void listForAnOwnerShowsDetailsAndStatusOfOwnPlotsAndTheStatusOfOthersOnlyWhenAllowed(
            PlotStatusVisibility setting) throws Exception {
        setVisibility(setting);

        ResultActions result = listAs(demoMemberToken(), "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(31)))
                // own plots: details and colour, never owners
                .andExpect(jsonPath("$.items[?(@.number == '7')].status").value("GREEN"))
                .andExpect(jsonPath("$.items[?(@.number == '7')].areaM2").value(800.0))
                .andExpect(jsonPath("$.items[?(@.number == '7')].cadastralNumber").value("4810136300:02:001:0007"))
                .andExpect(jsonPath("$.items[?(@.number == '7')].hasBuilding").value(false))
                .andExpect(jsonPath("$.items[?(@.number == '15')].status").value("GREEN"))
                .andExpect(jsonPath("$.items[?(@.number == '15')].areaM2").value(600.0))
                .andExpect(jsonPath("$.items[?(@.number == '15')].hasBuilding").value(true))
                // other plots: number, street and kind, never area, cadastral number or building
                .andExpect(jsonPath("$.items[?(@.number == '9')].street").value("Garden St"))
                .andExpect(jsonPath("$.items[?(@.number == '9')].areaM2").isEmpty())
                .andExpect(jsonPath("$.items[?(@.number == '9')].cadastralNumber").isEmpty())
                .andExpect(jsonPath("$.items[?(@.number == '9')].hasBuilding").isEmpty())
                .andExpect(jsonPath("$.items[?(@.number == '1')].areaM2").isEmpty())
                .andExpect(jsonPath("$.items[?(@.number == 'R-1')].areaM2").isEmpty())
                .andExpect(jsonPath("$.items[*].owners").doesNotExist())
                .andExpect(content().string(not(containsString("owners"))))
                .andExpect(content().string(not(containsString("Moroz"))))
                .andExpect(content().string(not(containsString("Lytvynenko"))));
        if (setting == PlotStatusVisibility.ALL_PLOTS) {
            result.andExpect(jsonPath("$.items[?(@.number == '9')].status").value("GREEN"))
                    .andExpect(jsonPath("$.items[?(@.number == '25')].status").value("GREY"))
                    .andExpect(jsonPath("$.items[?(@.number == 'R-1')].status").value("GREY"))
                    .andExpect(jsonPath("$.items[*].status", hasSize(31)));
        } else {
            result.andExpect(jsonPath("$.items[?(@.number == '9')].status").isEmpty())
                    .andExpect(jsonPath("$.items[?(@.number == '25')].status").isEmpty())
                    .andExpect(jsonPath("$.items[?(@.number == 'R-1')].status").isEmpty())
                    .andExpect(jsonPath("$.items[*].status", hasSize(2)));
        }
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void listForAFormerOwnerIsTheOrdinaryLimitedView(PlotStatusVisibility setting) throws Exception {
        setVisibility(setting);
        String former = formerOwnerOfPlot9();

        ResultActions result = listAs(former, "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(31)))
                .andExpect(jsonPath("$.items[*].areaM2").doesNotExist())
                .andExpect(jsonPath("$.items[*].cadastralNumber").doesNotExist())
                .andExpect(jsonPath("$.items[*].owners").doesNotExist());
        if (setting == PlotStatusVisibility.ALL_PLOTS) {
            result.andExpect(jsonPath("$.items[*].status", hasSize(31)));
        } else {
            result.andExpect(jsonPath("$.items[*].status").doesNotExist());
        }
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void listForTheTreasurerShowsEveryStatusAreaAndOwnerUnderBothSettings(PlotStatusVisibility setting)
            throws Exception {
        setVisibility(setting);

        listAs(treasurer(), "size=100")
                .andExpect(jsonPath("$.items[*].status", hasSize(31)))
                .andExpect(jsonPath("$.items[*].areaM2", hasSize(31)))
                .andExpect(jsonPath("$.items[?(@.number == '9')].status").value("GREEN"))
                .andExpect(jsonPath("$.items[?(@.number == '25')].status").value("GREY"))
                .andExpect(jsonPath("$.items[?(@.number == '7')].owners[*].name",
                        contains("Bondarenko Andrii", "Moroz Halyna")));
    }

    @Test
    void anOwnerNeverFindsPlotsByCadastralNumberOrOwnerNameAndNeverSeesArchivedPlots() throws Exception {
        String owner = demoMemberToken();

        // own plot 7 has this cadastral number, yet a search never widens beyond number and street
        listAs(owner, "q=4810136300:02:001:0007").andExpect(jsonPath("$.items", empty()));
        listAs(owner, "q=Moroz").andExpect(jsonPath("$.items", empty()));
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where number = '3'"));
        try {
            listAs(owner, "includeArchived=true&size=100")
                    .andExpect(jsonPath("$.totalItems").value(30))
                    .andExpect(jsonPath("$.items[?(@.number == '3')]").isEmpty());
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where number = '3'"));
        }
    }

    // ---- card

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void cardOfOwnPlotShowsEverythingExceptTheBoardsNotes(PlotStatusVisibility setting) throws Exception {
        setVisibility(setting);
        UUID plot = demoPlot("7");
        ownerJdbc().update("update plot set notes = 'Board only remark' where id = ?", plot);
        try {
            cardAs(demoMemberToken(), plot)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.number").value("7"))
                    .andExpect(jsonPath("$.street").value("Garden St"))
                    .andExpect(jsonPath("$.kind").value("PLOT"))
                    .andExpect(jsonPath("$.status").value("GREEN"))
                    .andExpect(jsonPath("$.areaM2").value(800.0))
                    .andExpect(jsonPath("$.cadastralNumber").value("4810136300:02:001:0007"))
                    .andExpect(jsonPath("$.hasBuilding").value(false))
                    .andExpect(jsonPath("$.notes").doesNotExist())
                    .andExpect(jsonPath("$.createdAt").doesNotExist())
                    .andExpect(jsonPath("$.updatedAt").doesNotExist())
                    .andExpect(content().string(not(containsString("Board only"))));
            cardAs(treasurer(), plot)
                    .andExpect(jsonPath("$.notes").value("Board only remark"))
                    .andExpect(jsonPath("$.createdAt").exists());
        } finally {
            ownerJdbc().update("update plot set notes = null where id = ?", plot);
        }
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void cardOfAnotherPlotShowsNumberStreetAndKindAndTheStatusOnlyWhenAllowed(PlotStatusVisibility setting)
            throws Exception {
        setVisibility(setting);
        UUID plot = demoPlot("9");

        for (String viewer : new String[] {demoMemberToken(), formerOwnerOfPlot9()}) {
            ResultActions result = cardAs(viewer, plot)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.number").value("9"))
                    .andExpect(jsonPath("$.street").value("Garden St"))
                    .andExpect(jsonPath("$.kind").value("PLOT"))
                    .andExpect(jsonPath("$.archived").value(false))
                    .andExpect(jsonPath("$.areaM2").doesNotExist())
                    .andExpect(jsonPath("$.cadastralNumber").doesNotExist())
                    .andExpect(jsonPath("$.hasBuilding").doesNotExist())
                    .andExpect(jsonPath("$.notes").doesNotExist())
                    .andExpect(jsonPath("$.createdAt").doesNotExist())
                    .andExpect(jsonPath("$.updatedAt").doesNotExist())
                    .andExpect(content().string(not(containsString("Corner plot"))))
                    .andExpect(content().string(not(containsString("Lytvynenko"))));
            if (setting == PlotStatusVisibility.ALL_PLOTS) {
                result.andExpect(jsonPath("$.status").value("GREEN"));
            } else {
                result.andExpect(jsonPath("$.status").doesNotExist());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void cardForTheTreasurerShowsEverythingUnderBothSettings(PlotStatusVisibility setting) throws Exception {
        setVisibility(setting);

        cardAs(treasurer(), demoPlot("9"))
                .andExpect(jsonPath("$.status").value("GREEN"))
                .andExpect(jsonPath("$.areaM2").value(1500.0))
                .andExpect(jsonPath("$.notes").value("Corner plot next to the entrance gate"));
    }

    @Test
    void anArchivedPlotsCardStaysHiddenFromAMemberUnderEitherSetting() throws Exception {
        UUID plot = demoPlot("3");
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where id = ?", plot));
        try {
            for (PlotStatusVisibility setting : PlotStatusVisibility.values()) {
                setVisibility(setting);
                cardAs(demoMemberToken(), plot).andExpect(status().isNotFound());
                cardAs(treasurer(), plot).andExpect(status().isOk());
            }
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = null where id = ?", plot));
        }
    }

    // ---- map

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void mapShowsAreaDebtAndStatusOfOwnPlotsAndTheStatusOfOthersOnlyWhenAllowed(PlotStatusVisibility setting)
            throws Exception {
        setVisibility(setting);

        ResultActions result = mapAs(demoMemberToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features", hasSize(31)))
                .andExpect(jsonPath("$.features[?(@.properties.number == '7')].properties.status").value("GREEN"))
                .andExpect(jsonPath("$.features[?(@.properties.number == '7')].properties.areaM2").value(800.0))
                .andExpect(jsonPath("$.features[?(@.properties.number == '7')].properties.debt").value("0.00"))
                .andExpect(jsonPath("$.features[?(@.properties.number == '15')].properties.areaM2").value(600.0))
                .andExpect(jsonPath("$.features[?(@.properties.number == '15')].properties.debt").value("0.00"))
                .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.areaM2").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.debt").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.number == 'R-1')].properties.areaM2").isEmpty())
                .andExpect(jsonPath("$.features[*].properties.areaM2", hasSize(2)))
                .andExpect(jsonPath("$.features[*].properties.debt", hasSize(2)));
        if (setting == PlotStatusVisibility.ALL_PLOTS) {
            result.andExpect(jsonPath("$.features[*].properties.status", hasSize(31)))
                    .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.status").value("GREEN"))
                    .andExpect(jsonPath("$.features[?(@.properties.number == 'R-1')].properties.status")
                            .value("GREY"));
        } else {
            result.andExpect(jsonPath("$.features[*].properties.status", hasSize(2)))
                    .andExpect(jsonPath("$.features[?(@.properties.number == '9')].properties.status").isEmpty())
                    .andExpect(jsonPath("$.features[?(@.properties.number == 'R-1')].properties.status").isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void mapForTheTreasurerShowsEveryStatusAreaAndDebtUnderBothSettings(PlotStatusVisibility setting)
            throws Exception {
        setVisibility(setting);

        mapAs(treasurer())
                .andExpect(jsonPath("$.features[*].properties.status", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.areaM2", hasSize(31)))
                .andExpect(jsonPath("$.features[*].properties.debt", hasSize(31)));
    }

    // ---- status filter

    @Test
    void statusFilterOfAnOwnerMatchesOnlyOwnPlotsUnderOwnOnly() throws Exception {
        String owner = demoMemberToken();

        listAs(owner, "status=GREEN&size=100")
                .andExpect(jsonPath("$.items[*].number", contains("7", "15")))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("GREEN"))));
        // the grey plots exist, but their colour is hidden from this member, so the filter must not reveal it
        listAs(owner, "status=GREY&size=100")
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0));
        listAs(owner, "status=GREEN&q=15").andExpect(jsonPath("$.items[*].number", contains("15")));
        listAs(owner, "status=GREEN&kind=COMMON").andExpect(jsonPath("$.items", empty()));
        // paging counts only the own plots
        listAs(owner, "status=GREEN&size=1&page=1")
                .andExpect(jsonPath("$.items[*].number", contains("15")))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void statusFilterOfAMemberWhoOwnsNothingIsAnEmptyPageUnderOwnOnly() throws Exception {
        String former = formerOwnerOfPlot9();

        listAs(former, "status=GREEN&size=50")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        listAs(former, "status=GREY").andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void statusFilterWorksForEveryMemberWhenAllPlotsAreVisible() throws Exception {
        setVisibility(PlotStatusVisibility.ALL_PLOTS);

        for (String viewer : new String[] {demoMemberToken(), formerOwnerOfPlot9()}) {
            listAs(viewer, "status=GREEN&size=100")
                    .andExpect(jsonPath("$.totalItems").value(25))
                    .andExpect(jsonPath("$.items[*].status", everyItem(is("GREEN"))));
            listAs(viewer, "status=GREY&size=100")
                    .andExpect(jsonPath("$.items[*].number", contains("25", "26", "27", "28", "29", "R-1")));
        }
    }

    @Test
    void statusFilterOfTheTreasurerIsNotLimitedUnderOwnOnly() throws Exception {
        listAs(treasurer(), "status=GREEN&size=100").andExpect(jsonPath("$.totalItems").value(25));
        listAs(treasurer(), "status=GREY&size=100").andExpect(jsonPath("$.totalItems").value(6));
    }

    // ---- owners of a plot

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void aCurrentOwnerSeesTheOwnersOfTheirPlotWithSharesDatesAndBasis(PlotStatusVisibility setting)
            throws Exception {
        setVisibility(setting);

        ownershipsAs(demoMemberToken(), plotId("7"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.current[*].memberName", contains("Bondarenko Andrii", "Moroz Halyna")))
                .andExpect(jsonPath("$.current[*].share", contains("1/2", "1/2")))
                .andExpect(jsonPath("$.current[*].validFrom", everyItem(is("2019-04-15"))))
                .andExpect(jsonPath("$.current[0].basis").value("Purchase, contract of 2019-04-10"))
                .andExpect(jsonPath("$.history", empty()));
        ownershipsAs(demoMemberToken(), plotId("15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[*].memberName", contains("Bondarenko Andrii")));
    }

    @Test
    void aCurrentOwnerSeesTheHistoryOfTheirPlotToo() throws Exception {
        UUID plot = demoPlot("9");
        UUID coOwner = demoMember("Newcomer", "Nina");
        seedOwner(plot, coOwner, "1/2", "2026-01-01", null);
        String bearer = tokenOf(coOwner);

        ownershipsAs(bearer, plot)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current", hasSize(2)))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].memberName").value("Honcharenko Mykola"))
                .andExpect(jsonPath("$.history[0].validFrom").value("2015-06-01"))
                .andExpect(jsonPath("$.history[0].validTo").value("2025-05-31"))
                .andExpect(jsonPath("$.history[0].basis").value("Initial allocation"));
    }

    @ParameterizedTest
    @EnumSource(PlotStatusVisibility.class)
    void ownersOfAPlotThatIsNotTheirsAreNotFoundForMembersAndFormerOwnersUnderEitherSetting(
            PlotStatusVisibility setting) throws Exception {
        setVisibility(setting);

        for (String viewer : new String[] {demoMemberToken(), formerOwnerOfPlot9()}) {
            ownershipsAs(viewer, plotId("9"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(content().string(not(containsString("Lytvynenko"))))
                    .andExpect(content().string(not(containsString("Honcharenko"))));
        }
        // the plot the former owner used to own looks like any plot that does not exist
        ownershipsAs(demoMemberToken(), UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void theTreasurerSeesTheOwnersOfAnyPlot() throws Exception {
        ownershipsAs(treasurer(), plotId("9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current[0].memberName").value("Lytvynenko Svitlana"))
                .andExpect(jsonPath("$.history[0].memberName").value("Honcharenko Mykola"));
    }

    @Test
    void anOwnerOfAPlotOfAnotherCooperativeIsNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '1'", UUID.class, OTHER_TENANT);

        ownershipsAs(demoMemberToken(), foreign).andExpect(status().isNotFound());
    }

    // ---- helpers

    /** Sets the cooperative setting as the database owner; the cleanup deletes the row ("never saved"). */
    private void setVisibility(PlotStatusVisibility setting) {
        ownerJdbc().update("insert into cooperative_settings (tenant_id, member_plot_status_visibility) "
                + "values (?, ?) on conflict (tenant_id) do update set "
                + "member_plot_status_visibility = excluded.member_plot_status_visibility",
                DEMO_TENANT, setting.name());
    }

    /** The demo member as Keycloak would sign them in; owns plot 7 (1/2) and plot 15. */
    private String demoMemberToken() {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + DEMO_MEMBER_EMAIL, DEMO_MEMBER_EMAIL);
    }

    /** A member who owned plot 9 in the past and owns nothing now. */
    private String formerOwnerOfPlot9() {
        UUID former = demoMember("Former", "Owner");
        seedOwner(demoPlot("9"), former, "1/1", "2010-01-01", "2012-12-31");
        return tokenOf(former);
    }

    /** A token of an existing member of the demo cooperative who holds the MEMBER role. */
    private String tokenOf(UUID member) {
        String email = ownerJdbc().queryForObject("select email from member where id = ?", String.class, member);
        giveBaseRole(DEMO_TENANT, member, "MEMBER");
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private UUID plotId(String number) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select id from plot where number = ?", UUID.class, number));
    }

    private ResultActions listAs(String bearer, String query) throws Exception {
        return mvc.perform(get(URI.create(PLOTS + (query.isEmpty() ? "" : "?" + query)))
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions cardAs(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions mapAs(String bearer) throws Exception {
        return mvc.perform(get(PLOTS + "/map").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions ownershipsAs(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot + "/ownerships").header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
