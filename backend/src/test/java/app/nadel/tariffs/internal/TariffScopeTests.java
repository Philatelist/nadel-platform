package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The scope of a tariff: all plots with exclusions, selected plots, switching between them, the plots that are refused,
 * the plot count, and who may change it.
 */
class TariffScopeTests extends TariffScopeSupport {

    private String scope(UUID tariff) {
        return API + "/" + tariff + "/scope";
    }

    private long chargeable() {
        return ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and archived_at is null "
                + "and kind <> 'COMMON'", Long.class, DEMO_TENANT);
    }

    /** The plotCount of the tariff in the list. */
    private long listedCount(UUID tariff) throws Exception {
        for (JsonNode node : body(send(chairman(), get(API), null).andExpect(status().isOk()))) {
            if (node.get("id").asString().equals(tariff.toString())) {
                return node.get("plotCount").asLong();
            }
        }
        throw new AssertionError("tariff not listed");
    }

    // ---- all plots with exclusions

    @Test
    void aNewTariffHasNoScopeRowsAndAppliesToEveryChargeablePlot() throws Exception {
        UUID tariff = newTariff("Members");

        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.selectedPlots", hasSize(0)))
                .andExpect(jsonPath("$.exclusions", hasSize(0)))
                .andExpect(jsonPath("$.plotCount").value(chargeable()));
    }

    @Test
    void anExclusionWithAReasonDropsTheCountByOneAndTheCardListsIt() throws Exception {
        UUID tariff = newTariff("Members");
        UUID seventeen = newPlot("TX-17", "PLOT", false);
        long before = listedCount(tariff);

        send(chairman(), put(scope(tariff)), allExcept(seventeen, "  Exempted by the general meeting "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.plotCount").value(before - 1))
                .andExpect(jsonPath("$.selectedPlots", hasSize(0)))
                .andExpect(jsonPath("$.exclusions", hasSize(1)))
                .andExpect(jsonPath("$.exclusions[0].plotId").value(seventeen.toString()))
                .andExpect(jsonPath("$.exclusions[0].number").value("TX-17"))
                .andExpect(jsonPath("$.exclusions[0].archived").value(false))
                .andExpect(jsonPath("$.exclusions[0].reason").value("Exempted by the general meeting"));

        assertThat(listedCount(tariff)).isEqualTo(before - 1);
        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plotCount").value(before - 1))
                .andExpect(jsonPath("$.exclusions[0].reason").value("Exempted by the general meeting"));
        assertThat(ownerJdbc().queryForObject("select tenant_id from tariff_scope_plot where tariff_id = ?",
                UUID.class, tariff)).isEqualTo(DEMO_TENANT);
    }

    @Test
    void aBlankReasonIsRefusedAndNothingIsStored() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        for (String reason : new String[] {"", "   ", "\t\n"}) {
            send(chairman(), put(scope(tariff)), allExcept(plot, reason)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        Map<String, Object> noReason = allExcept(plot, "x");
        ((List<Map<String, Object>>) noReason.get("exclusions")).getFirst().remove("reason");
        send(chairman(), put(scope(tariff)), noReason).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), put(scope(tariff)), allExcept(plot, "x".repeat(501))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].reason"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));

        assertThat(scopeRows(tariff, "EXCLUDED")).isZero();
    }

    @Test
    void aReasonOf500CharactersIsAccepted() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(chairman(), put(scope(tariff)), allExcept(plot, "x".repeat(500))).andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].reason").value("x".repeat(500)));
    }

    @Test
    void changingTheReasonReplacesTheExclusion() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        send(chairman(), put(scope(tariff)), allExcept(plot, "First reason")).andExpect(status().isOk());

        send(chairman(), put(scope(tariff)), allExcept(plot, "Second reason")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions", hasSize(1)))
                .andExpect(jsonPath("$.exclusions[0].reason").value("Second reason"));

        assertThat(scopeRows(tariff, "EXCLUDED")).isEqualTo(1);
    }

    @Test
    void anEmptyAllPlotsScopeRemovesTheExclusions() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        send(chairman(), put(scope(tariff)), allExcept(plot, "Exempt")).andExpect(status().isOk());

        send(chairman(), put(scope(tariff)), Map.of("kind", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions", hasSize(0)))
                .andExpect(jsonPath("$.plotCount").value(chargeable()));

        assertThat(scopeRows(tariff, "EXCLUDED")).isZero();
    }

    // ---- selected plots

    @Test
    void selectedPlotsThreeAndFiveGiveACountOfTwoAndAreListedByNumber() throws Exception {
        UUID tariff = newTariff("Members");
        UUID five = newPlot("TX-5", "PLOT", false);
        UUID three = newPlot("TX-3", "PLOT", false);

        send(chairman(), put(scope(tariff)), selected(five, three)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("SELECTED_PLOTS"))
                .andExpect(jsonPath("$.plotCount").value(2))
                .andExpect(jsonPath("$.exclusions", hasSize(0)))
                .andExpect(jsonPath("$.selectedPlots", hasSize(2)))
                .andExpect(jsonPath("$.selectedPlots[0].number").value("TX-3"))
                .andExpect(jsonPath("$.selectedPlots[0].plotId").value(three.toString()))
                .andExpect(jsonPath("$.selectedPlots[0].archived").value(false))
                .andExpect(jsonPath("$.selectedPlots[1].number").value("TX-5"));

        assertThat(listedCount(tariff)).isEqualTo(2);
        send(chairman(), get(API + "/" + tariff), null).andExpect(jsonPath("$.plotCount").value(2));
    }

    @Test
    void thePlotsOfTheScopeAreInNaturalOrderOfTheNumber() throws Exception {
        UUID tariff = newTariff("Members");
        UUID hundred = newPlot("TN-100", "PLOT", false);
        UUID tenB = newPlot("TN-10б", "PLOT", false);
        UUID nine = newPlot("TN-9", "PLOT", false);
        UUID tenA = newPlot("TN-10а", "PLOT", false);

        JsonNode selected = body(send(chairman(), put(scope(tariff)), selected(hundred, tenB, nine, tenA))
                .andExpect(status().isOk()));
        assertThat(numbers(selected.get("selectedPlots"))).containsExactly("TN-9", "TN-10а", "TN-10б", "TN-100");

        JsonNode excluded = body(send(chairman(), put(scope(tariff)),
                allExcept(hundred, "r", tenB, "r", nine, "r", tenA, "r")).andExpect(status().isOk()));
        assertThat(numbers(excluded.get("exclusions"))).containsExactly("TN-9", "TN-10а", "TN-10б", "TN-100");
    }

    private static List<String> numbers(JsonNode plots) {
        List<String> numbers = new ArrayList<>();
        plots.forEach(plot -> numbers.add(plot.get("number").asString()));
        return numbers;
    }

    @Test
    void selectingNoPlotsAtAllIsAllowed() throws Exception {
        UUID tariff = newTariff("Members");

        send(chairman(), put(scope(tariff)), Map.of("kind", "SELECTED_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("SELECTED_PLOTS"))
                .andExpect(jsonPath("$.plotCount").value(0))
                .andExpect(jsonPath("$.selectedPlots", hasSize(0)));
    }

    @Test
    void switchingToSelectedClearsTheExclusionsAndBackClearsTheSelection() throws Exception {
        UUID tariff = newTariff("Members");
        UUID excluded = newPlot("TX-1", "PLOT", false);
        UUID picked = newPlot("TX-2", "PLOT", false);
        send(chairman(), put(scope(tariff)), allExcept(excluded, "Exempt")).andExpect(status().isOk());
        assertThat(scopeRows(tariff, "EXCLUDED")).isEqualTo(1);

        send(chairman(), put(scope(tariff)), selected(picked)).andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions", hasSize(0)))
                .andExpect(jsonPath("$.selectedPlots", hasSize(1)));
        assertThat(scopeRows(tariff, "EXCLUDED")).isZero();
        assertThat(scopeRows(tariff, "SELECTED")).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select scope_kind from tariff where id = ?", String.class, tariff))
                .isEqualTo("SELECTED_PLOTS");

        send(chairman(), put(scope(tariff)), Map.of("kind", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.selectedPlots", hasSize(0)))
                .andExpect(jsonPath("$.plotCount").value(chargeable()));
        assertThat(scopeRows(tariff, "SELECTED")).isZero();
        assertThat(scopeRows(tariff, "EXCLUDED")).isZero();
        assertThat(ownerJdbc().queryForObject("select scope_kind from tariff where id = ?", String.class, tariff))
                .isEqualTo("ALL_PLOTS");
    }

    @Test
    void aPlotThatWasArchivedAfterItWasAddedIsListedMarkedAndNotCounted() throws Exception {
        UUID tariff = newTariff("Members");
        UUID stays = newPlot("TX-1", "PLOT", false);
        UUID goes = newPlot("TX-2", "PLOT", false);
        send(chairman(), put(scope(tariff)), selected(stays, goes)).andExpect(status().isOk());
        archivePlot(goes);

        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plotCount").value(1))
                .andExpect(jsonPath("$.selectedPlots", hasSize(2)))
                .andExpect(jsonPath("$.selectedPlots[1].number").value("TX-2"))
                .andExpect(jsonPath("$.selectedPlots[1].archived").value(true));
        assertThat(listedCount(tariff)).isEqualTo(1);

        // saving the scope the card shows again keeps the archived plot; adding another archived plot is refused
        send(chairman(), put(scope(tariff)), selected(stays, goes)).andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedPlots", hasSize(2)));
        UUID other = newPlot("TX-3", "PLOT", false);
        archivePlot(other);
        send(chairman(), put(scope(tariff)), selected(stays, goes, other)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[2]"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_ARCHIVED"));
    }

    @Test
    void anExcludedPlotThatWasArchivedLaterIsListedMarkedAndNotSubtracted() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        long before = listedCount(tariff);
        send(chairman(), put(scope(tariff)), allExcept(plot, "Exempt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plotCount").value(before - 1));
        archivePlot(plot);

        // the plot left the chargeable ones by itself, so the exclusion subtracts nothing more
        send(chairman(), get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.plotCount").value(before - 1))
                .andExpect(jsonPath("$.exclusions[0].archived").value(true));
        send(chairman(), put(scope(tariff)), allExcept(plot, "Exempt")).andExpect(status().isOk());
    }

    // ---- refused plots

    @Test
    void commonLandIsRefusedInBothLists() throws Exception {
        UUID tariff = newTariff("Members");
        UUID ordinary = newPlot("TX-1", "PLOT", false);
        UUID road = newPlot("TX-ROAD", "COMMON", false);

        send(chairman(), put(scope(tariff)), selected(ordinary, road)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_COMMON_LAND"));
        send(chairman(), put(scope(tariff)), allExcept(ordinary, "r", road, "r")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[1].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_COMMON_LAND"));

        assertThat(scopeRows(tariff, "SELECTED") + scopeRows(tariff, "EXCLUDED")).isZero();
    }

    @Test
    void anArchivedPlotIsRefusedInBothLists() throws Exception {
        UUID tariff = newTariff("Members");
        UUID old = newPlot("TX-OLD", "PLOT", true);

        send(chairman(), put(scope(tariff)), selected(old)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_ARCHIVED"));
        send(chairman(), put(scope(tariff)), allExcept(old, "r")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_ARCHIVED"));
    }

    @Test
    void anUnknownPlotOrOneOfAnotherCooperativeIsRefused() throws Exception {
        UUID tariff = newTariff("Members");
        UUID foreign = newPlot(OTHER_TENANT, "TX-F", "PLOT", false);

        send(chairman(), put(scope(tariff)), selected(UUID.randomUUID(), foreign)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"))
                .andExpect(jsonPath("$.errors[1].field").value("plotIds[1]"))
                .andExpect(jsonPath("$.errors[1].code").value("UNKNOWN_PLOT"));
        send(chairman(), put(scope(tariff)), allExcept(foreign, "r")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"));
        assertThat(scopeRows(tariff, "SELECTED") + scopeRows(tariff, "EXCLUDED")).isZero();
    }

    @Test
    void aListThatDoesNotGoWithTheKindIsRefused() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        Map<String, Object> exclusionsWithSelected = selected(plot);
        exclusionsWithSelected.put("exclusions", allExcept(plot, "r").get("exclusions"));
        send(chairman(), put(scope(tariff)), exclusionsWithSelected).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("exclusions"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        Map<String, Object> plotsWithAll = allExcept(plot, "r");
        plotsWithAll.put("plotIds", List.of(plot.toString()));
        send(chairman(), put(scope(tariff)), plotsWithAll).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        assertThat(scopeRows(tariff, "SELECTED") + scopeRows(tariff, "EXCLUDED")).isZero();
    }

    @Test
    void aPlotListedTwiceIsRefused() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(chairman(), put(scope(tariff)), selected(plot, plot)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        send(chairman(), put(scope(tariff)), allExcept(plot, "a", plot, "b")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[1].plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void theKindIsRequiredAndKnownAndTheListsAreLimited() throws Exception {
        UUID tariff = newTariff("Members");

        send(chairman(), put(scope(tariff)), Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), put(scope(tariff)), Map.of("kind", "SOME_PLOTS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "SELECTED_PLOTS");
        request.put("plotIds", tooMany);
        send(chairman(), put(scope(tariff)), request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void theCooperativeComesFromTheTokenNotTheBody() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        Map<String, Object> request = selected(plot);
        request.put("tenantId", OTHER_TENANT.toString());

        send(chairman(), put(scope(tariff)), request).andExpect(status().isOk());

        assertThat(ownerJdbc().queryForObject("select tenant_id from tariff_scope_plot where tariff_id = ?",
                UUID.class, tariff)).isEqualTo(DEMO_TENANT);
    }

    // ---- other states of the tariff

    @Test
    void aTariffUnderAnArchivedChargeTypeCannotChangeItsScope() throws Exception {
        UUID type = UUID.fromString(body(send(chairman(), post("/api/v1/charge-types"), Map.of("name", "Roads"))
                .andExpect(status().isCreated())).get("id").asString());
        UUID tariff = newTariff(type, "Roads", "PER_PLOT", "MONTHLY");
        UUID plot = newPlot("TX-1", "PLOT", false);
        send(chairman(), post("/api/v1/charge-types/" + type + "/archive"), null).andExpect(status().isNoContent());

        send(chairman(), put(scope(tariff)), selected(plot)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));

        assertThat(scopeRows(tariff, "SELECTED")).isZero();
    }

    @Test
    void aMeterBasedTariffHasAScopeButNoPlotCount() throws Exception {
        UUID tariff = newTariff(typeId(DEMO_TENANT, "ELECTRICITY"), "Power", "PER_KWH", "MONTHLY");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(chairman(), put(scope(tariff)), selected(plot)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKind").value("SELECTED_PLOTS"))
                .andExpect(jsonPath("$.selectedPlots", hasSize(1)))
                .andExpect(jsonPath("$.plotCount").doesNotExist());
        send(chairman(), get(API + "/" + tariff), null).andExpect(jsonPath("$.plotCount").doesNotExist());
    }

    @Test
    void theCardOfANewTariffAnswersWithTheCountToo() throws Exception {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", "2026-01-01");
        version.put("basis", "PER_PLOT");
        version.put("frequency", "MONTHLY");
        version.put("rate", "10");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", typeId(DEMO_TENANT, "MEMBERSHIP").toString());
        request.put("name", "Members");
        request.put("firstVersion", version);

        send(chairman(), post(API), request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.plotCount").value(chargeable()))
                .andExpect(jsonPath("$.selectedPlots", hasSize(0)))
                .andExpect(jsonPath("$.exclusions", hasSize(0)));
    }

    // ---- other cooperatives

    @Test
    void aTariffOfAnotherCooperativeOrAnUnknownOneIsNotFound() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(otherChairman(), put(scope(tariff)), selected(plot)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), put(scope(UUID.randomUUID())), selected(plot)).andExpect(status().isNotFound());

        assertThat(scopeRows(tariff, "SELECTED")).isZero();
        assertThat(ownerJdbc().queryForObject("select scope_kind from tariff where id = ?", String.class, tariff))
                .isEqualTo("ALL_PLOTS");
    }

    // ---- who may do what

    @Test
    void theChairmanMayChangeTheScope() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(chairman(), put(scope(tariff)), selected(plot)).andExpect(status().isOk());
    }

    @Test
    void theTreasurerReadsTheScopeButCannotChangeIt() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        send(chairman(), put(scope(tariff)), selected(plot)).andExpect(status().isOk());
        String treasurer = demo("treasurer@demo.local");

        send(treasurer, get(API + "/" + tariff), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedPlots", hasSize(1)));
        send(treasurer, put(scope(tariff)), Map.of("kind", "ALL_PLOTS")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        assertThat(scopeRows(tariff, "SELECTED")).isEqualTo(1);
    }

    @Test
    void aMemberTheElectricianAndTheOperatorAreRefused() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String token : List.of(demo("member@demo.local"), demo("electrician@demo.local"), operator)) {
            send(token, put(scope(tariff)), selected(plot)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        assertThat(scopeRows(tariff, "SELECTED")).isZero();
    }

    @Test
    void aWriterNeedsViewToo() throws Exception {
        UUID tariff = newTariff("Members");
        UUID plot = newPlot("TX-1", "PLOT", false);

        send(customRoleToken("TARIFFS_MANAGE"), put(scope(tariff)), selected(plot)).andExpect(status().isForbidden());
        send(customRoleToken("TARIFFS_VIEW"), put(scope(tariff)), selected(plot)).andExpect(status().isForbidden());
        assertThat(scopeRows(tariff, "SELECTED")).isZero();

        send(customRoleToken("TARIFFS_VIEW", "TARIFFS_MANAGE"), put(scope(tariff)), selected(plot))
                .andExpect(status().isOk());
    }

    @Test
    void withoutATokenTheScopeIsUnauthorised() throws Exception {
        UUID tariff = newTariff("Members");

        mvc.perform(put(scope(tariff)).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ALL_PLOTS\"}"))
                .andExpect(status().isUnauthorized());
    }
}
