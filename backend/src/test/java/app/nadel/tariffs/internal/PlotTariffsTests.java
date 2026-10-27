package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The expected amounts of a plot for a month ({@code GET /plots/{id}/tariffs}): the worked examples through the API,
 * scopes, exclusions, meter-based tariffs, charge types that are archived or inactive, and who may ask for which plot.
 * The demo tariffs are removed for the duration of each test (see {@link TariffScopeSupport}), so the totals are those
 * of the tariffs the test creates.
 */
class PlotTariffsTests extends TariffScopeSupport {

    private UUID demoPlot(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ? "
                + "and archived_at is null", UUID.class, DEMO_TENANT, number);
    }

    private ResultActions ask(String bearer, UUID plot, String month) throws Exception {
        var request = get("/api/v1/plots/" + plot + "/tariffs");
        if (month != null) {
            request.param("month", month);
        }
        return send(bearer, request, null);
    }

    private static Map<String, Object> version(String validFrom, String basis, String frequency, String rate,
            String dueDate) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", validFrom);
        version.put("basis", basis);
        version.put("frequency", frequency);
        version.put("rate", rate);
        if (dueDate != null) {
            version.put("dueDate", dueDate);
        }
        return version;
    }

    /** A tariff of all plots under the charge type with its first version. */
    private UUID tariff(UUID type, String name, Map<String, Object> firstVersion) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", type.toString());
        request.put("name", name);
        request.put("firstVersion", firstVersion);
        String id = body(send(chairman(), post(API), request).andExpect(status().isCreated())).get("id").asString();
        return UUID.fromString(id);
    }

    private UUID membership(String name, String rate) throws Exception {
        return tariff(typeId(DEMO_TENANT, "MEMBERSHIP"), name, version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", rate,
                null));
    }

    // ---- amounts

    @Test
    void aSixHundredSquareMetrePlotGetsTheAmountsOfTheWorkedExamples() throws Exception {
        membership("For members", "50");
        tariff(typeId(DEMO_TENANT, "LAND_TAX"), "Land tax", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "35",
                null));
        UUID plot = newPlot("PT-1", "PLOT", false);

        ask(chairman(), plot, "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].tariffName").value("For members"))
                .andExpect(jsonPath("$.items[0].chargeTypeCode").value("MEMBERSHIP"))
                .andExpect(jsonPath("$.items[0].chargeTypeName").value("Membership fee"))
                .andExpect(jsonPath("$.items[0].chargeTypeNameCustomized").value(false))
                .andExpect(jsonPath("$.items[0].basis").value("PER_AREA_SOTKA"))
                .andExpect(jsonPath("$.items[0].frequency").value("YEARLY"))
                .andExpect(jsonPath("$.items[0].rate").value("50.0000"))
                .andExpect(jsonPath("$.items[0].status").value("AMOUNT"))
                .andExpect(jsonPath("$.items[0].amount").value("25.00"))
                .andExpect(jsonPath("$.items[0].reason").doesNotExist())
                .andExpect(jsonPath("$.items[1].chargeTypeCode").value("LAND_TAX"))
                .andExpect(jsonPath("$.items[1].amount").value("17.50"))
                .andExpect(jsonPath("$.total").value("42.50"));
    }

    @Test
    void anOddAreaIsCalculatedExactly() throws Exception {
        membership("For members", "50");
        UUID plot = newPlot("PT-1", "PLOT", false);
        ownerJdbc().update("update plot set area_m2 = 612.00 where id = ?", plot);

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items[0].amount").value("25.50"))
                .andExpect(jsonPath("$.total").value("25.50"));
    }

    @Test
    void aMidMonthRateChangeSplitsTheMonthByDays() throws Exception {
        UUID tariff = membership("For members", "50");
        send(chairman(), post(API + "/" + tariff + "/versions"), version("2026-11-16", "PER_AREA_SOTKA", "YEARLY",
                "60", null)).andExpect(status().isCreated());
        UUID plot = newPlot("PT-1", "PLOT", false);

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items[0].amount").value("27.50"))
                .andExpect(jsonPath("$.items[0].rate").value("60.0000"))
                .andExpect(jsonPath("$.total").value("27.50"));
        ask(chairman(), plot, "2026-10").andExpect(jsonPath("$.items[0].amount").value("25.00"))
                .andExpect(jsonPath("$.items[0].rate").value("50.0000"));
        ask(chairman(), plot, "2026-12").andExpect(jsonPath("$.items[0].amount").value("30.00"));
    }

    @Test
    void aSpecialPurposeFeeIsDueInItsMonthAndZeroInTheOthers() throws Exception {
        tariff(typeId(DEMO_TENANT, "MEMBERSHIP"), "Road repair fee", version("2026-01-01", "FIXED_ONE_TIME", "ONE_OFF",
                "1200", "2026-05-01"));
        UUID plot = newPlot("PT-1", "PLOT", false);

        ask(chairman(), plot, "2026-05").andExpect(jsonPath("$.items[0].status").value("AMOUNT"))
                .andExpect(jsonPath("$.items[0].basis").value("FIXED_ONE_TIME"))
                .andExpect(jsonPath("$.items[0].frequency").value("ONE_OFF"))
                .andExpect(jsonPath("$.items[0].rate").value("1200.0000"))
                .andExpect(jsonPath("$.items[0].amount").value("1200.00"))
                .andExpect(jsonPath("$.total").value("1200.00"));
        ask(chairman(), plot, "2026-06").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value("0.00"))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    @Test
    void aMeterBasedTariffIsByReadingsWithoutAnAmountForAPlotWithAMeter() throws Exception {
        membership("For members", "50");
        tariff(typeId(DEMO_TENANT, "ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32",
                null));
        UUID plot = newPlot("PT-1", "PLOT", false);
        meter(plot, "ELECTRICITY", "2026-01-15", null);

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].chargeTypeCode").value("ELECTRICITY"))
                .andExpect(jsonPath("$.items[1].status").value("BY_READINGS"))
                .andExpect(jsonPath("$.items[1].rate").value("4.3200"))
                .andExpect(jsonPath("$.items[1].amount").doesNotExist())
                .andExpect(jsonPath("$.total").value("25.00"));
    }

    @Test
    void aMeterBasedTariffIsNoMeterForAPlotWithoutAMeterOfItsResource() throws Exception {
        membership("For members", "50");
        tariff(typeId(DEMO_TENANT, "ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32",
                null));
        UUID water = typeId(DEMO_TENANT, "WATER");
        ownerJdbc().update("update charge_type set active = true where id = ?", water); // reseeded after the test
        tariff(water, "Water by meter", version("2026-01-01", "PER_M3", "MONTHLY", "30", null));
        UUID plot = newPlot("PT-1", "PLOT", false);
        meter(plot, "WATER", "2026-01-15", null); // a water meter does not count for electricity

        ask(chairman(), plot, "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[1].chargeTypeCode").value("ELECTRICITY"))
                .andExpect(jsonPath("$.items[1].status").value("NO_METER"))
                .andExpect(jsonPath("$.items[1].basis").value("PER_KWH"))
                .andExpect(jsonPath("$.items[1].rate").value("4.3200"))
                .andExpect(jsonPath("$.items[1].amount").doesNotExist())
                .andExpect(jsonPath("$.items[2].chargeTypeCode").value("WATER"))
                .andExpect(jsonPath("$.items[2].status").value("BY_READINGS"))
                .andExpect(jsonPath("$.items[2].amount").doesNotExist())
                .andExpect(jsonPath("$.total").value("25.00"));
    }

    @Test
    void aMeterCountsForTheMonthsItWorkedOnAtLeastOneDay() throws Exception {
        tariff(typeId(DEMO_TENANT, "ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32",
                null));
        UUID plot = newPlot("PT-1", "PLOT", false);
        meter(plot, "ELECTRICITY", "2026-03-31", "2026-09-01");

        ask(chairman(), plot, "2026-02").andExpect(jsonPath("$.items[0].status").value("NO_METER"));
        ask(chairman(), plot, "2026-03").andExpect(jsonPath("$.items[0].status").value("BY_READINGS"));
        ask(chairman(), plot, "2026-09").andExpect(jsonPath("$.items[0].status").value("BY_READINGS"));
        ask(chairman(), plot, "2026-10").andExpect(jsonPath("$.items[0].status").value("NO_METER"))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    @Test
    void anOwnerSeesNoMeterForTheirOwnPlotWithoutAMeter() throws Exception {
        tariff(typeId(DEMO_TENANT, "ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32",
                null));
        UUID plot = newPlot("PT-1", "PLOT", false);
        UUID member = memberIdByEmail(DEMO_TENANT, "member@demo.local");
        ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", DEMO_TENANT, plot, member);

        ask(demo("member@demo.local"), plot, "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("NO_METER"));
    }

    @Test
    void aTariffIsNotListedForAMonthBeforeItsFirstVersion() throws Exception {
        membership("For members", "50");
        UUID plot = newPlot("PT-1", "PLOT", false);

        ask(chairman(), plot, "2025-12").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value("0.00"));
        ask(chairman(), plot, "2026-01").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void theItemsAreOrderedByChargeTypeThenTariffName() throws Exception {
        UUID land = typeId(DEMO_TENANT, "LAND_TAX");
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");
        tariff(land, "Land tax", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        tariff(membership, "beta", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        tariff(membership, "Alpha", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        UUID plot = newPlot("PT-1", "PLOT", false);

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items[0].tariffName").value("Alpha"))
                .andExpect(jsonPath("$.items[1].tariffName").value("beta"))
                .andExpect(jsonPath("$.items[2].tariffName").value("Land tax"))
                .andExpect(jsonPath("$.total").value("30.00"));
    }

    // ---- scope

    @Test
    void anExcludedPlotShowsTheReasonWithoutAnAmount() throws Exception {
        UUID tariff = membership("For members", "50");
        tariff(typeId(DEMO_TENANT, "LAND_TAX"), "Land tax", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "35",
                null));
        UUID plot = newPlot("PT-17", "PLOT", false);
        UUID other = newPlot("PT-18", "PLOT", false);
        send(chairman(), put(API + "/" + tariff + "/scope"), allExcept(plot, "Exempted by the general meeting"))
                .andExpect(status().isOk());

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].status").value("EXCLUDED"))
                .andExpect(jsonPath("$.items[0].reason").value("Exempted by the general meeting"))
                .andExpect(jsonPath("$.items[0].amount").doesNotExist())
                .andExpect(jsonPath("$.items[1].status").value("AMOUNT"))
                .andExpect(jsonPath("$.total").value("17.50"));
        ask(chairman(), other, "2026-11").andExpect(jsonPath("$.items[0].status").value("AMOUNT"))
                .andExpect(jsonPath("$.items[0].reason").doesNotExist())
                .andExpect(jsonPath("$.total").value("42.50"));
    }

    @Test
    void anExcludedPlotIsNotListedForAMonthBeforeTheTariffStarts() throws Exception {
        UUID tariff = membership("For members", "50");
        UUID plot = newPlot("PT-17", "PLOT", false);
        send(chairman(), put(API + "/" + tariff + "/scope"), allExcept(plot, "Exempt")).andExpect(status().isOk());

        ask(chairman(), plot, "2025-12").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void selectedPlotsGetTheTariffAndTheOthersDoNot() throws Exception {
        UUID tariff = membership("For members", "50");
        UUID selected = newPlot("PT-3", "PLOT", false);
        UUID notSelected = newPlot("PT-4", "PLOT", false);
        send(chairman(), put(API + "/" + tariff + "/scope"), selected(selected)).andExpect(status().isOk());

        ask(chairman(), selected, "2026-11").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value("25.00"));
        ask(chairman(), notSelected, "2026-11").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    @Test
    void commonLandAndAnArchivedPlotHaveNoTariffs() throws Exception {
        membership("For members", "50");
        UUID road = newPlot("PT-R", "COMMON", false);
        UUID archived = newPlot("PT-A", "PLOT", true);

        for (UUID plot : new UUID[] {road, archived}) {
            ask(chairman(), plot, "2026-11").andExpect(status().isOk()).andExpect(jsonPath("$.month").value("2026-11"))
                    .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.total").value("0.00"));
        }
    }

    @Test
    void coOwnersDoNotSplitTheAmount() throws Exception {
        membership("For members", "50");
        UUID sharedPlot = demoPlot("7"); // 800 m2, owned 1/2 by the demo member and 1/2 by another member

        ask(demo("member@demo.local"), sharedPlot, "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value("33.33")) // 50 x 8 / 12, not half of it
                .andExpect(jsonPath("$.total").value("33.33"));
    }

    // ---- charge types

    @Test
    void tariffsOfInactiveAndArchivedChargeTypesAreLeftOut() throws Exception {
        UUID land = typeId(DEMO_TENANT, "LAND_TAX");
        UUID water = typeId(DEMO_TENANT, "WATER"); // inactive from the start
        UUID custom = UUID.fromString(body(send(chairman(), post("/api/v1/charge-types"),
                Map.of("name", "Fence")).andExpect(status().isCreated())).get("id").asString());
        membership("For members", "50");
        tariff(land, "Land tax", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        tariff(water, "Water", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        tariff(custom, "Fence fee", version("2026-01-01", "PER_PLOT", "MONTHLY", "10", null));
        UUID plot = newPlot("PT-1", "PLOT", false);
        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[2].tariffName").value("Fence fee"))
                .andExpect(jsonPath("$.items[2].chargeTypeCode").doesNotExist())
                .andExpect(jsonPath("$.items[2].chargeTypeNameCustomized").value(true));

        ownerJdbc().update("update charge_type set active = false where id = ?", land);
        ownerJdbc().update("update charge_type set archived_at = now() where id = ?", custom);

        ask(chairman(), plot, "2026-11").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].tariffName").value("For members"))
                .andExpect(jsonPath("$.total").value("25.00"));
    }

    // ---- who may ask

    @Test
    void theChairmanAndTheTreasurerMayAskForAnyPlot() throws Exception {
        membership("For members", "50");
        UUID plot = demoPlot("1");

        for (String user : new String[] {"chairman@demo.local", "treasurer@demo.local"}) {
            ask(demo(user), plot, "2026-11").andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].amount").value("25.00"));
        }
    }

    @Test
    void aRoleWithTariffsViewMayAskForAnyPlot() throws Exception {
        membership("For members", "50");

        ask(customRoleToken("TARIFFS_VIEW"), demoPlot("1"), "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("25.00"));
    }

    @Test
    void aMemberSeesTheirOwnPlotAndGetsNotFoundForAnyOther() throws Exception {
        membership("For members", "50");
        String member = demo("member@demo.local"); // owns plot 15 alone and half of plot 7

        ask(member, demoPlot("15"), "2026-11").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value("25.00"));
        ask(member, demoPlot("1"), "2026-11").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void aMemberWithoutPlotsAndWithoutTheViewPermissionGetsNotFound() throws Exception {
        membership("For members", "50");

        ask(demo("electrician@demo.local"), demoPlot("15"), "2026-11").andExpect(status().isNotFound());
        ask(customRoleToken("PLOTS_VIEW"), demoPlot("15"), "2026-11").andExpect(status().isNotFound());
    }

    @Test
    void anUnknownPlotIsNotFoundForEveryone() throws Exception {
        ask(chairman(), UUID.randomUUID(), "2026-11").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        ask(demo("member@demo.local"), UUID.randomUUID(), "2026-11").andExpect(status().isNotFound());
    }

    @Test
    void aPlotOfAnotherCooperativeIsNotFound() throws Exception {
        membership("For members", "50");
        UUID otherPlot = ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = '1'",
                UUID.class, OTHER_TENANT);

        ask(chairman(), otherPlot, "2026-11").andExpect(status().isNotFound());
        ask(otherChairman(), demoPlot("1"), "2026-11").andExpect(status().isNotFound());
        // the other cooperative's own chairman gets an answer for its own plot and sees nothing of the demo tariffs
        ask(otherChairman(), otherPlot, "2026-11").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        ask(operator, demoPlot("1"), "2026-11").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void withoutATokenTheAnswerIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/plots/" + demoPlot("1") + "/tariffs")).andExpect(status().isUnauthorized());
    }

    // ---- the month

    @Test
    void aMonthThatIsNotYearDashMonthIsRefused() throws Exception {
        UUID plot = newPlot("PT-1", "PLOT", false);

        for (String bad : new String[] {"2026-13", "2026-00", "2026-1", "26-11", "abc", "2026-11-01", "202611", " "}) {
            ask(chairman(), plot, bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("month"));
        }
    }

    @Test
    void withoutAMonthTheCurrentKyivMonthIsUsed() throws Exception {
        membership("For members", "50");
        UUID plot = newPlot("PT-1", "PLOT", false);
        YearMonth before = YearMonth.now(ZoneId.of("Europe/Kyiv"));

        String month = body(ask(chairman(), plot, null).andExpect(status().isOk())).get("month").asString();

        assertThat(month).isIn(before.toString(), YearMonth.now(ZoneId.of("Europe/Kyiv")).toString());
    }
}
