package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoTariffData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Charge types and tariffs" (functional-spec.md of 009) through the public HTTP
 * API: one test per section 2.1-2.7, criteria named {@code §2.x #n} in the order of the spec. People are fresh members
 * with a base role; plots are created per test; the demo tariffs are removed for the duration of each test and put back
 * afterwards (see {@link DemoTariffData}), so no assertion depends on demo data or on counts of it. Per-case rules stay
 * in the tests of the {@code tariffs} module.
 * <pre>
 * @layer: integration
 * @spec: context/spec/009-charge-types-and-tariffs
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(120)
class TariffsAcceptanceTests extends IntegrationTest {

    private static final String TYPES = "/api/v1/charge-types";
    private static final String TARIFFS = "/api/v1/tariffs";
    private static final String PLOTS = "/api/v1/plots";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> createdPlots = new ArrayList<>();
    private String chairman;
    private UUID chairmanMember;
    private String treasurer;

    @BeforeEach
    void startFromTheSeededState() {
        DemoTariffData.clear(ownerJdbc());
        chairman = tokenWithBaseRole("CHAIRMAN", true);
        treasurer = tokenWithBaseRole("TREASURER", false);
    }

    @AfterEach
    void putTheDemoStateBack() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : createdPlots) {
            owner.update("delete from meter where plot_id = ?", plot);
            owner.update("delete from ownership where plot_id = ?", plot);
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot); // scope rows cascade
        }
        createdPlots.clear();
        DemoTariffData.clear(owner);
        DemoTariffData.restore(owner);
    }

    // ---- §2.1 Charge types

    /** §2.1 #1: the four starting types, Water inactive; their Ukrainian names are taken (shown by the client). */
    @Test
    void section21_theFourStartingTypesAreThereWaterInactive() throws Exception {
        JsonNode list = body(send(chairman, get(TYPES), null).andExpect(status().isOk()));
        assertThat(codes(list)).containsExactly("MEMBERSHIP", "ELECTRICITY", "LAND_TAX", "WATER");
        assertThat(texts(list, "name")).containsExactly("Membership fee", "Electricity", "Land tax", "Water");
        assertThat(list.get(3).get("active").asBoolean()).isFalse();
        assertThat(list.get(0).get("active").asBoolean()).isTrue();
        assertThat(list).allSatisfy(t -> assertThat(t.get("nameCustomized").asBoolean()).isFalse());
        // negative: the Ukrainian default is as taken as the English one
        send(chairman, post(TYPES), Map.of("name", "Членський внесок")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
    }

    /** §2.1 #2: a custom type is added at the end, active. */
    @Test
    void section21_aCustomTypeIsAddedAtTheEndActive() throws Exception {
        UUID road = newType("Ремонт дороги");
        JsonNode list = body(send(chairman, get(TYPES), null).andExpect(status().isOk()));
        assertThat(list).hasSize(5);
        assertThat(list.get(4).get("id").asString()).isEqualTo(road.toString());
        assertThat(list.get(4).get("name").asString()).isEqualTo("Ремонт дороги");
        assertThat(list.get(4).get("active").asBoolean()).isTrue();
    }

    /** §2.1 #3: a renamed type shows the new name everywhere (types, tariff card, tariffs list, plot amounts). */
    @Test
    void section21_aRenamedTypeShowsTheNewNameEverywhere() throws Exception {
        UUID membership = typeId("MEMBERSHIP");
        UUID tariff = newTariff(membership, "For members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID plot = newPlot("600");
        send(chairman, put(TYPES + "/" + membership), Map.of("name", "Членські внески 2026", "active", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Членські внески 2026"))
                .andExpect(jsonPath("$.nameCustomized").value(true));

        send(chairman, get(TYPES), null).andExpect(jsonPath("$[0].name").value("Членські внески 2026"));
        send(chairman, get(TARIFFS + "/" + tariff), null)
                .andExpect(jsonPath("$.chargeType.name").value("Членські внески 2026"));
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[0].chargeTypeId").value(membership.toString()));
        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.items[0].chargeTypeName").value("Членські внески 2026"))
                .andExpect(jsonPath("$.items[0].chargeTypeNameCustomized").value(true));
    }

    /** §2.1 #4: a name another type already has is refused on add and on rename, ignoring letter case. */
    @Test
    void section21_aDuplicateNameIsRefusedOnAddAndOnRename() throws Exception {
        UUID road = newType("Ремонт дороги");
        send(chairman, post(TYPES), Map.of("name", "ремонт ДОРОГИ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        send(chairman, put(TYPES + "/" + road), Map.of("name", "Electricity", "active", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_TYPE_NAME_TAKEN"));
        assertThat(texts(body(send(chairman, get(TYPES), null)), "name")).hasSize(5).contains("Ремонт дороги");
    }

    /** §2.1 #5: moving "Land tax" up swaps it with the type above it and the order is kept after reloading. */
    @Test
    void section21_movingATypeUpChangesPlacesAndTheOrderIsKept() throws Exception {
        List<String> ids = ids(body(send(chairman, get(TYPES), null)));
        send(chairman, put(TYPES + "/order"), Map.of("ids", List.of(ids.get(0), ids.get(2), ids.get(1), ids.get(3))))
                .andExpect(status().isNoContent());
        assertThat(codes(body(send(chairman, get(TYPES), null))))
                .containsExactly("MEMBERSHIP", "LAND_TAX", "ELECTRICITY", "WATER");
        // negative: a stale list is refused and changes nothing
        send(chairman, put(TYPES + "/order"), Map.of("ids", List.of(ids.get(0), ids.get(1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_TYPE_ORDER_STALE"));
        assertThat(codes(body(send(chairman, get(TYPES), null))))
                .containsExactly("MEMBERSHIP", "LAND_TAX", "ELECTRICITY", "WATER");
    }

    /** §2.1 #6: an archived type leaves the list, gets no tariffs and its tariffs leave the expected amounts. */
    @Test
    void section21_anArchivedTypeDisappearsGetsNoTariffsAndLeavesTheAmounts() throws Exception {
        UUID road = newType("Ремонт дороги");
        newTariff(road, "Road", version("2026-01-01", "PER_PLOT", "MONTHLY", "10"));
        newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_PLOT", "MONTHLY", "5"));
        UUID plot = newPlot("600");
        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.total").value("15.00"));

        send(chairman, post(TYPES + "/" + road + "/archive"), null).andExpect(status().isNoContent());

        assertThat(ids(body(send(chairman, get(TYPES), null)))).doesNotContain(road.toString()).hasSize(4);
        send(chairman, post(TARIFFS), tariffBody(road, "Again", version("2026-01-01", "PER_PLOT", "MONTHLY", "1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"));
        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total").value("5.00"));
    }

    // ---- §2.2 Tariffs and versions

    /** §2.2 #1, #2, #7: the first version, a second one newest first with the derived end, rate with 4 decimals. */
    @Test
    void section22_aTariffWithTwoVersionsListsThemNewestFirstWithFourDecimals() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Для членів кооперативу",
                version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[0].name").value("Для членів кооперативу"))
                .andExpect(jsonPath("$[0].version.rate").value("50.0000"))
                .andExpect(jsonPath("$[0].version.basis").value("PER_AREA_SOTKA"))
                .andExpect(jsonPath("$[0].version.frequency").value("YEARLY"))
                .andExpect(jsonPath("$[0].version.validFrom").value("2027-01-01"));

        send(chairman, post(TARIFFS + "/" + tariff + "/versions"),
                version("2027-07-01", "PER_AREA_SOTKA", "YEARLY", "60")).andExpect(status().isCreated());
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[0].validFrom").value("2027-07-01"))
                .andExpect(jsonPath("$.versions[0].validTo").doesNotExist())
                .andExpect(jsonPath("$.versions[1].validFrom").value("2027-01-01"))
                .andExpect(jsonPath("$.versions[1].validTo").value("2027-06-30"));

        // §2.2 #7: 4.32 is 4.3200 in the card, the list and the plot amounts
        UUID electricity = newTariff(typeId("MEMBERSHIP"), "Cents", version("2026-01-01", "PER_PLOT", "MONTHLY", "4.32"));
        send(chairman, get(TARIFFS + "/" + electricity), null).andExpect(jsonPath("$.versions[0].rate").value("4.3200"));
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[?(@.id == '" + electricity + "')].version.rate")
                .value("4.3200"));
        preview(chairman, newPlot("600"), "2026-11").andExpect(jsonPath("$.items[?(@.tariffName == 'Cents')].rate")
                .value("4.3200"));
    }

    /** §2.2 #3: a version dated in the past is saved and flagged (the form shows the warning before saving). */
    @Test
    void section22_aPastDatedVersionIsSavedAndFlagged() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members",
                version("2020-01-01", "PER_PLOT", "MONTHLY", "10"));
        String yesterday = LocalDate.now(KYIV).minusDays(1).toString();
        send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version(yesterday, "PER_PLOT", "MONTHLY", "12"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pastDated").value(true));
        String tomorrow = LocalDate.now(KYIV).plusDays(1).toString();
        send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version(tomorrow, "PER_PLOT", "MONTHLY", "13"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pastDated").value(false));
    }

    /** §2.2 #4: the same start date twice is refused and nothing is added. */
    @Test
    void section22_theSameStartDateTwiceIsRefused() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2027-01-01", "PER_PLOT", "MONTHLY", "10"));
        send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version("2027-01-01", "PER_PLOT", "MONTHLY", "11"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_DATE_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"));
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(1)));
    }

    /** §2.2 #5: more than 4 decimals, a negative rate and a non-number are refused with RATE_INVALID. */
    @Test
    void section22_aBadRateIsRefused() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2027-01-01", "PER_PLOT", "MONTHLY", "10"));
        for (String bad : new String[] {"4.32001", "-1", "abc"}) {
            send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version("2028-01-01", "PER_PLOT", "MONTHLY", bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("rate"))
                    .andExpect(jsonPath("$.errors[0].code").value("RATE_INVALID"));
        }
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(1)));
    }

    /** §2.2 #6: a fixed one-time amount is one-off and needs a due date; one-off goes with nothing else. */
    @Test
    void section22_aFixedOneTimeAmountIsOneOffWithADueDate() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2027-01-01", "PER_PLOT", "MONTHLY", "10"));
        String path = TARIFFS + "/" + tariff + "/versions";
        send(chairman, post(path), version("2028-01-01", "FIXED_ONE_TIME", "MONTHLY", "1200", "2028-02-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("frequency"))
                .andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        send(chairman, post(path), version("2028-01-01", "FIXED_ONE_TIME", "ONE_OFF", "1200", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("dueDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, post(path), version("2028-01-01", "PER_PLOT", "ONE_OFF", "10", "2028-02-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FREQUENCY_BASIS_MISMATCH"));
        send(chairman, post(path), version("2028-01-01", "FIXED_ONE_TIME", "ONE_OFF", "1200", "2028-02-01"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.frequency").value("ONE_OFF"))
                .andExpect(jsonPath("$.dueDate").value("2028-02-01"));
    }

    // ---- §2.3 Which plots a tariff applies to

    /** §2.3 #1, #2: an exclusion needs a reason, lowers the plot count by one and shows on the plot's amounts. */
    @Test
    void section23_anExclusionNeedsAReasonAndShowsOnThePlot() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID seventeen = newPlot("600");
        UUID other = newPlot("600");
        int before = body(send(chairman, get(TARIFFS + "/" + tariff), null)).get("plotCount").asInt();

        // #2 no reason: refused, nothing changes
        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), allExcept(seventeen, "  ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("exclusions[0].reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.plotCount").value(before))
                .andExpect(jsonPath("$.exclusions", hasSize(0)));

        // #1 with a reason
        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), allExcept(seventeen, "звільнено рішенням загальних зборів"))
                .andExpect(status().isOk());
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[0].plotCount").value(before - 1));
        preview(chairman, seventeen, "2026-11").andExpect(jsonPath("$.items[0].status").value("EXCLUDED"))
                .andExpect(jsonPath("$.items[0].reason").value("звільнено рішенням загальних зборів"))
                .andExpect(jsonPath("$.items[0].amount").doesNotExist());
        preview(chairman, other, "2026-11").andExpect(jsonPath("$.items[0].amount").value("25.00"));
    }

    /** §2.3 #3: selected plots 3 and 5 give "2 plots" and only those two show the tariff. */
    @Test
    void section23_selectedPlotsAreCountedAndTheOnlyOnesWithTheTariff() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID three = newPlot("600");
        UUID five = newPlot("600");
        UUID other = newPlot("600");
        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), selected(three, five)).andExpect(status().isOk());

        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[0].plotCount").value(2));
        preview(chairman, three, "2026-11").andExpect(jsonPath("$.items", hasSize(1)));
        preview(chairman, five, "2026-11").andExpect(jsonPath("$.items", hasSize(1)));
        preview(chairman, other, "2026-11").andExpect(jsonPath("$.items", hasSize(0)));
    }

    /** §2.3 #4: common land is not offered by the plot search and a request to add it is refused. */
    @Test
    void section23_commonLandIsNotOfferedAndIsRefused() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID road = newPlot("COMMON", "0");
        UUID ordinary = newPlot("600");
        String roadNumber = ownerJdbc().queryForObject("select number from plot where id = ?", String.class, road);

        // the scope editor searches ordinary plots only (kind=PLOT)
        send(chairman, get(PLOTS).param("q", roadNumber).param("kind", "PLOT"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), selected(ordinary, road)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_COMMON_LAND"));
        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), allExcept(road, "reason")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TARIFF_COMMON_LAND"));
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"))
                .andExpect(jsonPath("$.exclusions", hasSize(0)));
    }

    /**
     * §2.3 #5, as changed by feature 010 (spec 010 §2.7): a per-kWh tariff applies to the plots with a meter, so its
     * number of plots counts only the plots with an active electricity meter.
     */
    @Test
    void section23_aPerKwhTariffCountsOnlyThePlotsWithAnElectricityMeter() throws Exception {
        UUID tariff = newTariff(typeId("ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32"));
        long before = meteredPlots("ELECTRICITY");
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.plotCount").value(before));

        meter(newPlot("600"), "ELECTRICITY");
        meter(newPlot("600"), "WATER");
        newPlot("600");

        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.plotCount").value(before + 1));
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$[0].plotCount").value(before + 1));
    }

    // ---- §2.4 Expected amount for a plot and a month

    /**
     * §2.4 #1-#6 and #8: every row of the worked-examples table, each as its own plot, through the API: 25.00, 25.50,
     * 19.44 (rounded once), 100.00, 27.50 (split by days), 1,200.00 in May and 0.00 in June, the excluded plot,
     * 17.50 land tax, common land never, "by readings" without an amount, and a month before the first version.
     */
    @Test
    void section24_everyRowOfTheWorkedExamplesTable() throws Exception {
        UUID members = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID p600 = newPlot("600");
        UUID p612 = newPlot("612");
        preview(chairman, p600, "2026-11").andExpect(jsonPath("$.items[0].amount").value("25.00"))
                .andExpect(jsonPath("$.total").value("25.00"));
        preview(chairman, p612, "2026-11").andExpect(jsonPath("$.items[0].amount").value("25.50"));
        // #6 a month before any version starts: the tariff is not listed
        preview(chairman, p600, "2025-12").andExpect(jsonPath("$.items", hasSize(0)));

        // version change mid-month: 50 until 15.11, 60 from 16.11 -> 12.50 + 15.00
        send(chairman, post(TARIFFS + "/" + members + "/versions"), version("2026-11-16", "PER_AREA_SOTKA", "YEARLY", "60"))
                .andExpect(status().isCreated());
        preview(chairman, p600, "2026-11").andExpect(jsonPath("$.items[0].amount").value("27.50"));

        // rounding once at the end: 33.3333 x 7 / 12 = 19.444425 -> 19.44
        send(chairman, post(TARIFFS + "/" + members + "/versions"), version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "33.3333"))
                .andExpect(status().isCreated());
        preview(chairman, newPlot("700"), "2027-02").andExpect(jsonPath("$.items[0].amount").value("19.44"));

        // per plot, quarterly 300 -> 100.00
        newTariff(typeId("LAND_TAX"), "Quarterly", version("2026-01-01", "PER_PLOT", "QUARTERLY", "300"));
        preview(chairman, p600, "2026-10").andExpect(jsonPath("$.items[?(@.tariffName == 'Quarterly')].amount").value("100.00"));

        // special-purpose fee: 1 200.00 in May 2026, 0.00 in June
        newTariff(typeId("ELECTRICITY"), "Road repair", version("2026-01-01", "FIXED_ONE_TIME", "ONE_OFF", "1200", "2026-05-01"));
        preview(chairman, p600, "2026-05").andExpect(jsonPath("$.items[?(@.tariffName == 'Road repair')].amount").value("1200.00"));
        preview(chairman, p600, "2026-06").andExpect(jsonPath("$.items[?(@.tariffName == 'Road repair')].amount").value("0.00"));

        // common land: never any tariff or amount
        preview(chairman, newPlot("COMMON", "0"), "2026-11").andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    /**
     * §2.4 land tax row and the by-readings criterion, in one cooperative state; as changed by feature 010 (spec 010
     * §2.7) a plot without an electricity meter is "no meter" and one with a meter "by readings", neither with an amount.
     */
    @Test
    void section24_landTaxAndMeterBasedTariffs() throws Exception {
        newTariff(typeId("LAND_TAX"), "Land", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "35"));
        newTariff(typeId("ELECTRICITY"), "By meter", version("2026-01-01", "PER_KWH", "MONTHLY", "4.32"));
        UUID plot = newPlot("600");
        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.tariffName == 'Land')].amount").value("17.50"))
                .andExpect(jsonPath("$.items[?(@.tariffName == 'By meter')].status").value("NO_METER"))
                .andExpect(jsonPath("$.items[?(@.tariffName == 'By meter')].amount").isEmpty())
                .andExpect(jsonPath("$.total").value("17.50"));

        meter(plot, "ELECTRICITY");
        preview(chairman, plot, "2026-11")
                .andExpect(jsonPath("$.items[?(@.tariffName == 'By meter')].status").value("BY_READINGS"))
                .andExpect(jsonPath("$.items[?(@.tariffName == 'By meter')].amount").isEmpty())
                .andExpect(jsonPath("$.total").value("17.50"));
    }

    /** §2.4 #7: two co-owners at 1/2 each: the plot's whole amount, not half. */
    @Test
    void section24_coOwnersDoNotSplitTheAmount() throws Exception {
        newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID plot = newPlot("600");
        UUID anna = newMember(DEMO_TENANT, "co-a-" + UUID.randomUUID() + "@example.com");
        UUID boris = newMember(DEMO_TENANT, "co-b-" + UUID.randomUUID() + "@example.com");
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("effectiveFrom", "2026-01-01");
        change.put("owners", List.of(Map.of("memberId", anna, "share", "1/2"), Map.of("memberId", boris, "share", "1/2")));
        send(chairman, post(PLOTS + "/" + plot + "/ownership-changes"), change).andExpect(status().isCreated());

        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.items[0].amount").value("25.00"))
                .andExpect(jsonPath("$.total").value("25.00"));
    }

    // ---- §2.5 Who can see and change tariffs

    /** §2.5 #1, #2: the treasurer reads everything, changes nothing; a refused change leaves the data as it was. */
    @Test
    void section25_theTreasurerReadsButCannotChange() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_PLOT", "MONTHLY", "10"));
        send(treasurer, get(TYPES), null).andExpect(jsonPath("$", hasSize(4)));
        send(treasurer, get(TARIFFS), null).andExpect(jsonPath("$", hasSize(1)));
        send(treasurer, get(TARIFFS + "/" + tariff), null).andExpect(status().isOk());
        send(treasurer, get("/api/v1/me"), null).andExpect(jsonPath("$.permissions[?(@ == 'TARIFFS_VIEW')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'TARIFFS_MANAGE')]").doesNotExist());

        send(treasurer, post(TARIFFS), tariffBody(typeId("MEMBERSHIP"), "Denied", version("2026-01-01", "PER_PLOT", "MONTHLY", "1")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(treasurer, post(TARIFFS + "/" + tariff + "/versions"), version("2027-01-01", "PER_PLOT", "MONTHLY", "99"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(treasurer, put(TARIFFS + "/" + tariff + "/scope"), selected()).andExpect(status().isForbidden());
        send(treasurer, post(TYPES), Map.of("name", "Denied")).andExpect(status().isForbidden());
        send(treasurer, post(TYPES + "/" + typeId("WATER") + "/archive"), null).andExpect(status().isForbidden());

        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$", hasSize(1)));
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.scopeKind").value("ALL_PLOTS"));
        send(chairman, get(TYPES), null).andExpect(jsonPath("$", hasSize(4)));
    }

    /** §2.5 #3: a member has no view permission (no menu item) and the tariffs API is closed to them. */
    @Test
    void section25_aMemberHasNoTariffsPermissionAndNoAccess() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_PLOT", "MONTHLY", "10"));
        String member = tokenWithBaseRole("MEMBER", false);
        send(member, get("/api/v1/me"), null).andExpect(jsonPath("$.permissions[?(@ == 'TARIFFS_VIEW')]").doesNotExist());
        for (String path : new String[] {TARIFFS, TARIFFS + "/" + tariff, TYPES}) {
            send(member, get(path), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    /** §2.5 #4, #5: a member sees amounts of own plots only; any other plot is "not found" with no amounts. */
    @Test
    void section25_aMemberSeesOnlyTheAmountsOfOwnPlots() throws Exception {
        newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID own = newPlot("600");
        UUID foreign = newPlot("600");
        String email = "tar-member-" + UUID.randomUUID() + "@example.com";
        UUID memberId = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, memberId, "MEMBER");
        String member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
        Map<String, Object> change = Map.of("effectiveFrom", "2026-01-01",
                "owners", List.of(Map.of("memberId", memberId, "share", "1/1")));
        send(chairman, post(PLOTS + "/" + own + "/ownership-changes"), change).andExpect(status().isCreated());

        JsonNode me = body(send(member, get("/api/v1/me"), null).andExpect(status().isOk()));
        assertThat(me.get("ownedPlots")).hasSize(1);
        assertThat(me.get("ownedPlots").get(0).get("plotId").asString()).isEqualTo(own.toString());
        preview(member, own, "2026-11").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].amount").value("25.00"));
        preview(member, foreign, "2026-11").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist());
    }

    /** §2.5 #6: a chairman of another cooperative gets "not found" and sees nothing of this cooperative's tariffs. */
    @Test
    void section25_anotherCooperativeSeesNothing() throws Exception {
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_PLOT", "MONTHLY", "10"));
        UUID plot = newPlot("600");
        String email = "tar-other-" + UUID.randomUUID() + "@example.com";
        UUID other = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, other, "CHAIRMAN");
        String foreign = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);

        send(foreign, get(TARIFFS + "/" + tariff), null).andExpect(status().isNotFound());
        send(foreign, post(TARIFFS + "/" + tariff + "/versions"), version("2027-01-01", "PER_PLOT", "MONTHLY", "1"))
                .andExpect(status().isNotFound());
        send(foreign, get(TARIFFS), null).andExpect(jsonPath("$[?(@.id == '" + tariff + "')]").isEmpty());
        preview(foreign, plot, "2026-11").andExpect(status().isNotFound());
        send(chairman, get(TARIFFS + "/" + tariff), null).andExpect(jsonPath("$.versions", hasSize(1)));
    }

    // ---- §2.6 Screens (API side)

    /** §2.6 #1, #2: no tariffs yet gives an empty list; changing the month changes amounts and the total. */
    @Test
    void section26_anEmptyListAndAMonthThatChangesTheAmounts() throws Exception {
        send(chairman, get(TARIFFS), null).andExpect(jsonPath("$", hasSize(0)));
        UUID tariff = newTariff(typeId("MEMBERSHIP"), "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version("2026-12-01", "PER_AREA_SOTKA", "YEARLY", "60"))
                .andExpect(status().isCreated());
        UUID plot = newPlot("600");
        preview(chairman, plot, "2026-11").andExpect(jsonPath("$.total").value("25.00"));
        preview(chairman, plot, "2026-12").andExpect(jsonPath("$.total").value("30.00"));
    }

    // ---- §2.7 Audit

    /**
     * §2.7 #1: a rename, a new version and an exclusion are three audit entries by the chairman, each with old and
     * new values and pointing at the charge type or the tariff.
     */
    @Test
    void section27_renameVersionAndExclusionAreThreeAuditEntries() throws Exception {
        UUID membership = newType("Road repair " + UUID.randomUUID().toString().substring(0, 8)); // new id: audit rows of other tests stay out
        UUID tariff = newTariff(membership, "Members", version("2026-01-01", "PER_AREA_SOTKA", "YEARLY", "50"));
        UUID plot = newPlot("600");
        send(chairman, put(TYPES + "/" + membership), Map.of("name", "Членські внески 2026", "active", true))
                .andExpect(status().isOk());
        send(chairman, post(TARIFFS + "/" + tariff + "/versions"), version("2027-01-01", "PER_AREA_SOTKA", "YEARLY", "60"))
                .andExpect(status().isCreated());
        send(chairman, put(TARIFFS + "/" + tariff + "/scope"), allExcept(plot, "звільнено рішенням загальних зборів"))
                .andExpect(status().isOk());

        JsonNode rename = auditEntry(membership, "CHARGE_TYPE_UPDATED");
        assertThat(rename.get("entityType").asString()).isEqualTo("CHARGE_TYPE");
        assertThat(rename.get("actorMemberId").asString()).isEqualTo(chairmanMember.toString());
        assertThat(rename.get("actorName").asString()).isNotBlank();
        assertThat(rename.get("changes").get(0).get("field").asString()).isEqualTo("name");
        assertThat(rename.get("changes").get(0).get("oldValue").asString()).startsWith("Road repair ");
        assertThat(rename.get("changes").get(0).get("newValue").asString()).isEqualTo("Членські внески 2026");

        JsonNode added = auditEntry(tariff, "TARIFF_VERSION_ADDED");
        assertThat(added.get("entityType").asString()).isEqualTo("TARIFF");
        assertThat(added.get("actorMemberId").asString()).isEqualTo(chairmanMember.toString());
        assertThat(texts(added.get("changes"), "field")).contains("validFrom", "rate");
        assertThat(texts(added.get("changes"), "newValue")).contains("2027-01-01");

        JsonNode excluded = auditEntry(tariff, "TARIFF_EXCLUSION_ADDED");
        assertThat(excluded.get("entityType").asString()).isEqualTo("TARIFF");
        assertThat(texts(excluded.get("changes"), "field")).containsExactly("plot", "reason");
        assertThat(texts(excluded.get("changes"), "newValue")).contains("звільнено рішенням загальних зборів");
        // negative: a change the treasurer is refused writes no audit entry
        send(treasurer, put(TYPES + "/" + membership), Map.of("name", "Hacked", "active", true))
                .andExpect(status().isForbidden());
        assertThat(entries(membership, "CHARGE_TYPE_UPDATED")).hasSize(1);
    }

    // ---- helpers

    private String tokenWithBaseRole(String baseCode, boolean isChairman) {
        String email = "tar-acceptance-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        if (isChairman) {
            chairmanMember = member;
        }
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private ResultActions preview(String bearer, UUID plot, String month) throws Exception {
        return send(bearer, get(PLOTS + "/" + plot + "/tariffs").param("month", month), null);
    }

    private UUID typeId(String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                DEMO_TENANT, code);
    }

    private UUID newType(String name) throws Exception {
        String id = body(send(chairman, post(TYPES), Map.of("name", name)).andExpect(status().isCreated()))
                .get("id").asString();
        return UUID.fromString(id);
    }

    private static Map<String, Object> version(String validFrom, String basis, String frequency, String rate) {
        return version(validFrom, basis, frequency, rate, null);
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

    private static Map<String, Object> tariffBody(UUID type, String name, Map<String, Object> firstVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", type.toString());
        request.put("name", name);
        request.put("firstVersion", firstVersion);
        return request;
    }

    private UUID newTariff(UUID type, String name, Map<String, Object> firstVersion) throws Exception {
        String id = body(send(chairman, post(TARIFFS), tariffBody(type, name, firstVersion))
                .andExpect(status().isCreated())).get("id").asString();
        return UUID.fromString(id);
    }

    private UUID newPlot(String areaM2) throws Exception {
        return newPlot("PLOT", areaM2);
    }

    /** An active plot meter of the resource on a test plot, installed in January 2026 (removed with the plot). */
    private void meter(UUID plot, String resource) {
        ownerJdbc().update("insert into meter (tenant_id, kind, resource, serial, plot_id, installed_on, initial_value) "
                + "select tenant_id, 'PLOT', ?, 'TA-1', id, date '2026-01-10', 0 from plot where id = ?", resource, plot);
    }

    /** Active, non-common plots of the demo cooperative with an active meter of the resource. */
    private long meteredPlots(String resource) {
        return ownerJdbc().queryForObject("select count(distinct p.id) from plot p join meter m on m.plot_id = p.id "
                + "where p.tenant_id = ? and p.archived_at is null and p.kind <> 'COMMON' and m.resource = ? "
                + "and m.removed_on is null", Long.class, DEMO_TENANT, resource);
    }

    private UUID newPlot(String kind, String areaM2) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("number", "TA-" + UUID.randomUUID().toString().substring(0, 8));
        input.put("kind", kind);
        input.put("areaM2", new BigDecimal(areaM2));
        String id = body(send(chairman, post(PLOTS), input).andExpect(status().isCreated())).get("id").asString();
        UUID plot = UUID.fromString(id);
        createdPlots.add(plot);
        return plot;
    }

    private static Map<String, Object> selected(UUID... plotIds) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "SELECTED_PLOTS");
        request.put("plotIds", java.util.Arrays.stream(plotIds).map(UUID::toString).toList());
        return request;
    }

    private static Map<String, Object> allExcept(UUID plot, String reason) {
        Map<String, Object> exclusion = new LinkedHashMap<>();
        exclusion.put("plotId", plot.toString());
        exclusion.put("reason", reason);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", "ALL_PLOTS");
        request.put("exclusions", List.of(exclusion));
        return request;
    }

    private static List<String> codes(JsonNode list) {
        return texts(list, "code");
    }

    private static List<String> ids(JsonNode list) {
        return texts(list, "id");
    }

    private static List<String> texts(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(node -> out.add(node.get(field).asString()));
        return out;
    }

    private List<JsonNode> entries(UUID entity, String action) throws Exception {
        JsonNode items = body(send(chairman, get("/api/v1/audit").param("entityId", entity.toString())
                .param("size", "50"), null).andExpect(status().isOk())).get("items");
        List<JsonNode> out = new ArrayList<>();
        items.forEach(item -> {
            if (action.equals(item.get("action").asString())) {
                out.add(item);
            }
        });
        return out;
    }

    /** The audit entries are written from events, so wait for the one entry of the action. */
    private JsonNode auditEntry(UUID entity, String action) {
        List<JsonNode> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            found.clear();
            found.addAll(entries(entity, action));
            assertThat(found).hasSize(1);
        });
        return found.getFirst();
    }
}
