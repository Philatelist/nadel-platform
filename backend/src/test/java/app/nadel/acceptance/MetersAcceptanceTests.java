package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoMeterData;
import app.nadel.DemoReadingCharges;
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
 * Acceptance tests of the whole feature "Meters and readings" (functional-spec.md of 010) through the public HTTP API:
 * one or more tests per section 2.1-2.10, criteria named {@code §2.x #n} in the order of the spec, and one test per
 * row of the worked examples of section 2.5. The people are fresh members with a base role or a custom role (so the
 * demo users keep their unlinked state); plots are created per test; every meter of a test has a serial starting with
 * {@code MA-} and is removed with its readings, tree periods and audit entries afterwards. Dates are fixed in the past
 * (2025), except where the spec talks about today. The per-case rules stay in the tests of the {@code meters} module.
 * <pre>
 * @layer: integration
 * @spec: context/spec/010-meters-and-readings
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(180)
class MetersAcceptanceTests extends IntegrationTest {

    private static final String METERS = "/api/v1/meters";
    private static final String READINGS = "/api/v1/readings";
    private static final String PLOTS = "/api/v1/plots";
    private static final String TREE = "/api/v1/meter-tree";
    private static final String INSTALLED = "2025-01-01";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();
    private final List<UUID> ownerships = new ArrayList<>();
    private String chairman;
    private UUID chairmanMember;

    @BeforeEach
    void signInTheChairman() {
        chairmanMember = newMember(DEMO_TENANT, "ma-chairman-" + UUID.randomUUID() + "@example.com");
        giveBaseRole(DEMO_TENANT, chairmanMember, "CHAIRMAN");
        chairman = tokenOf(DEMO_TENANT, chairmanMember);
    }

    @AfterEach
    void removeEverythingTheTestCreated() {
        JdbcTemplate owner = ownerJdbc();
        // the readings of 2025 are before the demo electricity tariff: the ledger queues them (the items go with the
        // meters) and charges nothing; wait for its listener before the meters go
        DemoReadingCharges.awaitListeners(owner);
        owner.update("delete from meter_reading where meter_id in (select id from meter where serial like 'MA-%')");
        owner.update("delete from meter_parent where meter_id in (select id from meter where serial like 'MA-%') "
                + "or parent_meter_id in (select id from meter where serial like 'MA-%')");
        owner.update("delete from audit_entry where entity_type in ('METER', 'METER_READING') "
                + "and entity_id in (select id from meter where serial like 'MA-%')");
        owner.update("delete from meter where serial like 'MA-%' and replaces_meter_id is not null");
        owner.update("delete from meter where serial like 'MA-%'");
        for (UUID ownership : ownerships) {
            owner.update("delete from ownership where id = ?", ownership);
        }
        ownerships.clear();
        for (UUID plot : plots) {
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        plots.clear();
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
    }

    // ---- §2.1 Meters

    /** §2.1 #1: plot 7, serial "ЕМ-104523", installed 01.06.2026, initial reading 8 000: listed under the plot, active. */
    @Test
    void section21_aPlotMeterIsRegisteredAndListedUnderItsPlotAsActive() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");

        JsonNode meter = body(send(chairman, post(METERS), plotMeter(plot, "ELECTRICITY", "MA-ЕМ-104523", "2026-06-01",
                "8000")).andExpect(status().isCreated()));

        assertThat(meter.get("active").asBoolean()).isTrue();
        assertThat(meter.get("plot").get("id").asString()).isEqualTo(plot.toString());
        assertThat(meter.get("serial").asString()).isEqualTo("MA-ЕМ-104523");
        assertThat(meter.get("installedOn").asString()).isEqualTo("2026-06-01");
        assertThat(meter.get("initialReading").asString()).isEqualTo("8000.000");
        send(chairman, get(PLOTS + "/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(meter.get("id").asString()))
                .andExpect(jsonPath("$[0].active").value(true));
        assertThat(ids(send(chairman, get(METERS).param("street", "MA вул. Вишнева"), null)))
                .containsExactly(meter.get("id").asString());
        // negative: a meter needs a serial number and a plot of the cooperative
        send(chairman, post(METERS), plotMeter(plot, "WATER", " ", "2026-06-01", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("serial"));
        send(chairman, post(METERS), plotMeter(UUID.randomUUID(), "WATER", "MA-X", "2026-06-01", "0"))
                .andExpect(status().is4xxClientError());
    }

    /** §2.1 #2: a second active electricity meter on the plot is refused (the water meter is another resource). */
    @Test
    void section21_aSecondActiveMeterOfTheResourceIsRefused() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        register(plotMeter(plot, "ELECTRICITY", "MA-1", "2026-06-01", "8000"));

        send(chairman, post(METERS), plotMeter(plot, "ELECTRICITY", "MA-2", "2026-07-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_ACTIVE_EXISTS"));
        send(chairman, post(METERS), plotMeter(plot, "WATER", "MA-3", "2026-07-01", "0")).andExpect(status().isCreated());
        assertThat(meterCountOnPlot(plot)).isEqualTo(2);
    }

    /** §2.1 #3: common land has no plot meters. */
    @Test
    void section21_commonLandHasNoPlotMeters() throws Exception {
        UUID road = newPlot(DEMO_TENANT, "MA вул. Вишнева", "COMMON");

        send(chairman, post(METERS), plotMeter(road, "ELECTRICITY", "MA-1", "2026-06-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_COMMON_LAND"))
                .andExpect(jsonPath("$.errors[0].field").value("plotId"));
        assertThat(meterCountOnPlot(road)).isZero();
    }

    /** §2.1 #4: a common consumer "Свердловина №1" appears in the register with its name; it needs one. */
    @Test
    void section21_aCommonConsumerAppearsInTheRegisterWithItsName() throws Exception {
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MA Свердловина №1", "MA-1", INSTALLED, "100.5"));

        JsonNode list = body(send(chairman, get(METERS).param("kind", "COMMON_CONSUMER"), null)
                .andExpect(status().isOk()));
        JsonNode found = null;
        for (JsonNode item : list) {
            if (item.get("id").asString().equals(pump.toString())) {
                found = item;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.get("name").asString()).isEqualTo("MA Свердловина №1");
        assertThat(found.get("kind").asString()).isEqualTo("COMMON_CONSUMER");
        // negative: a non-plot meter without a name is refused
        Map<String, Object> nameless = namedMeter("COMMON_CONSUMER", "x", "MA-2", INSTALLED, "0");
        nameless.remove("name");
        send(chairman, post(METERS), nameless).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    /** §2.1 #5: a reading with 4 decimals is refused (at the boundary, as a whole), with 3 it is kept exactly. */
    @Test
    void section21_aReadingWithFourDecimalsIsRefusedAndThreeAreKeptExactly() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "WATER", "MA-1", INSTALLED, "12.345"));

        send(chairman, post(READINGS), Map.of("rows", List.of(row(meter, "2025-03-01", "13.1234"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("rows[0].value"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        for (String bad : new String[] {"-1", "1,5", "abc"}) {
            send(chairman, post(READINGS), Map.of("rows", List.of(row(meter, "2025-03-01", bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        }
        assertThat(readingCount(meter)).isZero();

        JsonNode saved = entered(meter, "2025-03-01", "13.125");
        assertThat(saved.get("reading").get("value").asString()).isEqualTo("13.125");
        assertThat(saved.get("reading").get("interval").get("consumption").asString()).isEqualTo("0.780");
        // and the initial reading follows the same rule
        send(chairman, post(METERS), plotMeter(newPlot("MA вул. Вишнева"), "WATER", "MA-2", INSTALLED, "1.2345"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
    }

    /** §2.1 #6: a meter without readings is deleted; with readings there is no delete and a request is refused. */
    @Test
    void section21_aMeterWithReadingsIsRemovedInsteadOfDeleted() throws Exception {
        UUID empty = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", INSTALLED, "0"));
        UUID read = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-2", INSTALLED, "0"));
        entered(read, "2025-03-01", "10");

        send(chairman, get(METERS + "/" + empty), null).andExpect(jsonPath("$.deletable").value(true));
        send(chairman, get(METERS + "/" + read), null).andExpect(jsonPath("$.deletable").value(false));
        send(chairman, delete(METERS + "/" + read), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_HAS_READINGS"));
        assertThat(meterCount(read)).isEqualTo(1);
        send(chairman, delete(METERS + "/" + empty), null).andExpect(status().isNoContent());
        send(chairman, get(METERS + "/" + empty), null).andExpect(status().isNotFound());
        assertThat(ids(send(chairman, get(METERS).param("street", "MA вул. Вишнева"), null)))
                .containsExactly(read.toString());
    }

    /** §2.1 #7: removed on 15.10 with final reading 8 420: shown as removed, keeps its readings, the plot gets a new one. */
    @Test
    void section21_aRemovedMeterKeepsItsReadingsAndThePlotCanGetANewMeter() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        entered(meter, "2025-09-03", "8140");

        send(chairman, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-10-15",
                "finalReading", "8420")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.removedOn").value("2025-10-15"))
                .andExpect(jsonPath("$.finalReading").value("8420.000"));

        JsonNode history = history(meter);
        assertThat(history.get("readings")).hasSize(1);
        assertThat(history.get("readings").get(0).get("value").asString()).isEqualTo("8140.000");
        send(chairman, get(PLOTS + "/" + plot + "/meters"), null).andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].removedOn").value("2025-10-15"));
        send(chairman, post(METERS), plotMeter(plot, "ELECTRICITY", "MA-2", "2025-10-15", "0"))
                .andExpect(status().isCreated());
        // negative: a final reading below the last one, and a second removal, are refused
        UUID other = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-3", "2025-06-01", "8000"));
        entered(other, "2025-09-03", "8140");
        send(chairman, post(METERS + "/" + other + "/remove"), Map.of("removedOn", "2025-10-15", "finalReading", "8100"))
                .andExpect(status().isConflict());
        send(chairman, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-10-16",
                "finalReading", "8421")).andExpect(status().isConflict());
    }

    /** §2.1 #8: a plot with an active meter is not archived and not turned into common land, nor deleted. */
    @Test
    void section21_aPlotWithAnActiveMeterIsNotArchivedOrTurnedIntoCommonLand() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", "2025-06-01", "0"));

        send(chairman, post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));
        send(chairman, put(PLOTS + "/" + plot), plotUpdate(plot, "COMMON")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        send(chairman, delete(PLOTS + "/" + plot), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));
        assertThat(plotRow(plot)).isEqualTo("PLOT false");

        send(chairman, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-10-15", "finalReading", "5"))
                .andExpect(status().isOk());
        send(chairman, post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isOk());
        assertThat(plotRow(plot)).isEqualTo("PLOT true");
    }

    // ---- §2.2 The meter tree

    /** §2.2 #1 and #2: "Квартал А" under the substation, plot 7's meter under it; a quarter under a quarter is refused. */
    @Test
    void section22_theChairmanBuildsTheTreeAndAQuarterIsNotUnderAQuarter() throws Exception {
        UUID substation = register(namedMeter("SUBSTATION", "MA Підстанція №1", "MA-S", INSTALLED, "0"));
        UUID quarterA = register(namedMeter("QUARTER", "MA Квартал А", "MA-QA", INSTALLED, "0"));
        UUID quarterB = register(namedMeter("QUARTER", "MA Квартал Б", "MA-QB", INSTALLED, "0"));
        UUID plotMeter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-P7", INSTALLED, "0"));

        move(chairman, quarterA, substation, INSTALLED).andExpect(status().isOk());
        move(chairman, plotMeter, quarterA, INSTALLED).andExpect(status().isOk());

        JsonNode tree = tree("2025-03-01", "2025-03-31");
        assertThat(parentsOf(tree, quarterA)).containsExactly(substation);
        assertThat(parentsOf(tree, plotMeter)).containsExactly(quarterA);
        move(chairman, quarterB, quarterA, INSTALLED).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_TREE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("parentMeterId"))
                .andExpect(jsonPath("$.errors[0].code").value("TREE_QUARTER_UNDER_QUARTER"));
        assertThat(parentsOf(tree("2025-03-01", "2025-03-31"), quarterB)).containsExactly((UUID) null);
        // water meters have no tree
        UUID water = register(plotMeter(newPlot("MA вул. Вишнева"), "WATER", "MA-W", INSTALLED, "0"));
        move(chairman, water, quarterA, INSTALLED).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_TREE_INVALID"));
    }

    /**
     * §2.2 #3 and #4, with the worked example "Plot moved to another quarter": moved A to B on 15.10, range 01.10-31.10:
     * 01.10-15.10 under A, 15.10-31.10 under B (the parts add up to the whole); before the move the plot is under A.
     */
    @Test
    void section22_aMovedPlotIsCountedUnderEachQuarterForItsPartOfTheRangeAndThePastKeepsTheOldTree()
            throws Exception {
        UUID quarterA = register(namedMeter("QUARTER", "MA Квартал А", "MA-QA", INSTALLED, "0"));
        UUID quarterB = register(namedMeter("QUARTER", "MA Квартал Б", "MA-QB", INSTALLED, "0"));
        UUID plotMeter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-P7", INSTALLED, "0"));
        entered(plotMeter, "2025-10-01", "1000");
        entered(plotMeter, "2025-10-31", "1300");
        move(chairman, plotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        move(chairman, plotMeter, quarterB, "2025-10-15").andExpect(status().isOk());

        JsonNode october = tree("2025-10-01", "2025-10-31");
        JsonNode underA = placeUnder(october, plotMeter, quarterA);
        JsonNode underB = placeUnder(october, plotMeter, quarterB);
        assertThat(underA.get("slices").get(0).get("from").asString()).isEqualTo("2025-10-01");
        assertThat(underA.get("slices").get(0).get("to").asString()).isEqualTo("2025-10-15");
        assertThat(underB.get("slices").get(0).get("from").asString()).isEqualTo("2025-10-15");
        assertThat(underB.get("slices").get(0).get("to").asString()).isEqualTo("2025-10-31");
        BigDecimal partA = new BigDecimal(underA.get("total").get("consumption").asString());
        BigDecimal partB = new BigDecimal(underB.get("total").get("consumption").asString());
        assertThat(partA).isEqualByComparingTo("140.000");
        assertThat(partB).isEqualByComparingTo("160.000");
        assertThat(partA.add(partB)).isEqualByComparingTo("300.000");
        assertThat(underA.get("total").get("calculated").asBoolean()).isTrue();

        // a past range before the move shows the tree as it was
        assertThat(parentsOf(tree("2025-10-01", "2025-10-14"), plotMeter)).containsExactly(quarterA);
        assertThat(parentsOf(tree("2025-11-01", "2025-11-30"), plotMeter)).containsExactly(quarterB);
        // negative: the range must not end before it starts
        send(chairman, get(TREE).param("from", "2025-10-31").param("to", "2025-10-01"), null)
                .andExpect(status().isBadRequest());
    }

    // ---- §2.3 Replacement

    /** §2.3 #1: replaced on 20.09, final 8 205, new initial 3: old removed, new active from 20.09, both in the card. */
    @Test
    void section23_aReplacementRemovesTheOldMeterAndInstallsTheNewOneInOneAction() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID old = register(plotMeter(plot, "ELECTRICITY", "MA-ЕМ-1", "2025-06-01", "8000"));
        entered(old, "2025-09-03", "8140");

        JsonNode created = body(send(chairman, post(METERS + "/" + old + "/replace"),
                replacement("2025-09-20", "8205", "MA-ЕМ-2", "3")).andExpect(status().isCreated()));

        String replacementId = created.get("id").asString();
        assertThat(created.get("installedOn").asString()).isEqualTo("2025-09-20");
        assertThat(created.get("active").asBoolean()).isTrue();
        send(chairman, get(METERS + "/" + old), null).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.removedOn").value("2025-09-20"))
                .andExpect(jsonPath("$.finalReading").value("8205.000"));
        send(chairman, get(PLOTS + "/" + plot + "/meters"), null).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(replacementId))
                .andExpect(jsonPath("$[1].id").value(old.toString()));
        // negative: a final reading below the last reading, and a replacement of a removed meter, are refused
        send(chairman, post(METERS + "/" + replacementId + "/replace"), replacement("2025-10-01", "2", "MA-ЕМ-3", "0"))
                .andExpect(status().isConflict());
        send(chairman, post(METERS + "/" + old + "/replace"), replacement("2025-10-01", "9000", "MA-ЕМ-4", "0"))
                .andExpect(status().isConflict());
    }

    /** §2.3 #2: a wrap-around of plot 9's meter (same serial, final 99 999, initial 0) keeps counting from 0. */
    @Test
    void section23_aWrapAroundIsAReplacementOfTheMeterByItself() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-ЕМ-9", "2025-06-01", "99000"));
        entered(meter, "2025-09-03", "99950");

        UUID wrapped = replace(meter, "2025-09-15", "99999", "MA-ЕМ-9", "0");
        entered(wrapped, "2025-10-05", "12");

        send(chairman, get(METERS + "/" + wrapped), null).andExpect(jsonPath("$.serial").value("MA-ЕМ-9"))
                .andExpect(jsonPath("$.initialReading").value("0.000"));
        JsonNode range = consumption(wrapped, "2025-09-03", "2025-10-05");
        assertThat(range.get("known").asBoolean()).isTrue();
        assertThat(range.get("consumption").asString()).isEqualTo("61.000");
        // negative: without the wrap a lower reading is refused
        UUID plain = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-ЕМ-10", "2025-06-01", "99000"));
        entered(plain, "2025-09-03", "99950");
        assertThat(enter(chairman, plain, "2025-10-05", "12").get("error").get("code").asString())
                .isEqualTo("READING_OUT_OF_ORDER");
    }

    // ---- §2.4 Readings

    /** §2.4 #1 and #2: no date means today; the date of one row can differ from the others. */
    @Test
    void section24_aReadingWithoutADateIsDatedTodayAndOneRowMayDifferInDate() throws Exception {
        UUID first = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-01-01", "0"));
        UUID second = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-2", "2025-01-01", "0"));
        LocalDate today = LocalDate.now(KYIV);

        JsonNode rows = enterRows(chairman, List.of(row(first, null, "5"), row(second, today.minusDays(3).toString(), "7")));

        assertThat(rows.get(0).get("reading").get("takenOn").asString()).isEqualTo(today.toString());
        assertThat(rows.get(1).get("reading").get("takenOn").asString()).isEqualTo(today.minusDays(3).toString());
    }

    /** §2.4 #3 and #4: a future date and a date outside the working period are refused; today and the edges are fine. */
    @Test
    void section24_theDateIsNotInTheFutureAndInsideTheWorkingPeriod() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        LocalDate today = LocalDate.now(KYIV);

        JsonNode future = enter(chairman, meter, today.plusDays(1).toString(), "8100");
        assertThat(future.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(future.get("error").get("code").asString()).isEqualTo("READING_DATE_IN_FUTURE");
        assertThat(enter(chairman, meter, "2025-05-31", "8100").get("error").get("code").asString())
                .isEqualTo("READING_DATE_OUTSIDE_METER");
        send(chairman, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-09-01", "finalReading", "8100"))
                .andExpect(status().isOk());
        assertThat(enter(chairman, meter, "2025-09-02", "8100").get("error").get("code").asString())
                .isEqualTo("READING_DATE_OUTSIDE_METER");
        assertThat(enter(chairman, meter, "2025-06-01", "8000").get("outcome").asString()).isEqualTo("SAVED");
        assertThat(enter(chairman, meter, "2025-09-01", "8100").get("outcome").asString()).isEqualTo("SAVED");
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    /** §2.4 #5 and #6 (+ table rows "Lower reading", "Reading between two others"): ordering and the split interval. */
    @Test
    void section24_aLowerReadingIsRefusedAndAReadingBetweenTwoOthersSplitsTheInterval() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        entered(meter, "2025-09-03", "8140");

        JsonNode refused = enter(chairman, meter, "2025-10-05", "8100");
        assertThat(refused.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(refused.get("error").get("code").asString()).isEqualTo("READING_OUT_OF_ORDER");
        assertThat(readingCount(meter)).isEqualTo(1);

        entered(meter, "2025-10-05", "8205");
        entered(meter, "2025-09-20", "8170");
        assertThat(intervals(history(meter))).containsExactly("2025-09-20..2025-10-05=35.000",
                "2025-09-03..2025-09-20=30.000", "2025-06-01..2025-09-03=140.000");
        // negative: between the two a value above the next one does not fit
        assertThat(enter(chairman, meter, "2025-09-25", "8206").get("error").get("code").asString())
                .isEqualTo("READING_OUT_OF_ORDER");
    }

    /** §2.4 #7 (+ table row "Same date twice"): the first reading shows as superseded, the interval uses the new one. */
    @Test
    void section24_aSecondReadingOfTheSameDateSupersedesTheFirst() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        JsonNode first = entered(meter, "2025-10-05", "8205");

        JsonNode second = entered(meter, "2025-10-05", "8250");

        assertThat(second.get("supersededReadingIds").get(0).asString()).isEqualTo(first.get("reading").get("id").asString());
        JsonNode history = history(meter);
        assertThat(history.get("readings").get(0).get("status").asString()).isEqualTo("APPROVED");
        assertThat(history.get("readings").get(1).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(intervals(history)).containsExactly("2025-06-01..2025-10-05=250.000");
    }

    /** §2.4 #8 (+ table row "Unusual consumption"): usual 2 kWh a day, 400 kWh in 10 days is saved with the warning. */
    @Test
    void section24_anUnusualConsumptionIsSavedWithAWarning() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-07-01", "1000"));

        assertThat(entered(meter, "2025-08-01", "1062").get("warnings")).isEmpty(); // 2 kWh a day
        JsonNode unusual = entered(meter, "2025-08-11", "1462"); // 40 kWh a day

        assertThat(unusual.get("outcome").asString()).isEqualTo("SAVED");
        assertThat(unusual.get("warnings").get(0).asString()).isEqualTo("UNUSUAL_CONSUMPTION");
        assertThat(readingCount(meter)).isEqualTo(2);
        // negative: the usual consumption again gives no warning
        assertThat(entered(meter, "2025-08-21", "1482").get("warnings")).isEmpty();
    }

    /** §2.4 #9 and #10 (+ table row "Member reading"): pending does not count; approving counts, rejecting shows the reason. */
    @Test
    void section24_aMemberReadingWaitsForApprovalThenCountsOrIsRejectedWithAReason() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID member = newMember(DEMO_TENANT, "ma-owner-" + UUID.randomUUID() + "@example.com");
        own(member, plot);
        String owner = tokenOf(DEMO_TENANT, member);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        entered(meter, "2025-10-05", "8205");

        JsonNode submitted = body(send(owner, post(PLOTS + "/" + plot + "/readings"),
                row(meter, "2025-10-28", "8210")).andExpect(status().isCreated()));
        UUID pending = UUID.fromString(submitted.get("reading").get("id").asString());
        assertThat(submitted.get("reading").get("status").asString()).isEqualTo("PENDING");
        assertThat(intervals(history(meter))).containsExactly("2025-06-01..2025-10-05=205.000");
        assertThat(consumption(meter, "2025-10-05", "2025-10-28").get("known").asBoolean()).isFalse();

        // someone without the approve permission cannot decide (the member, the treasurer, an electrician-like role)
        for (String denied : new String[] {owner, tokenWithBaseRole("TREASURER"),
                customRoleToken("METERS_VIEW", "READINGS_ENTER")}) {
            send(denied, post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isForbidden());
            send(denied, post(READINGS + "/" + pending + "/reject"), Map.of("reason", "No")).andExpect(status().isForbidden());
        }
        assertThat(readingStatus(pending)).isEqualTo("PENDING");

        // the chairman sees it on "To approve", approves it, and a new interval appears
        assertThat(pendingIds(chairman)).contains(pending.toString());
        send(chairman, post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.reading.status").value("APPROVED"));
        assertThat(intervals(history(meter))).containsExactly("2025-10-05..2025-10-28=5.000",
                "2025-06-01..2025-10-05=205.000");
        assertThat(pendingIds(chairman)).doesNotContain(pending.toString());

        // a rejection needs a reason and the member sees "rejected" with it
        UUID second = UUID.fromString(body(send(owner, post(PLOTS + "/" + plot + "/readings"),
                row(meter, "2025-10-29", "8215")).andExpect(status().isCreated())).get("reading").get("id").asString());
        send(chairman, post(READINGS + "/" + second + "/reject"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        send(chairman, post(READINGS + "/" + second + "/reject"), Map.of("reason", "Фото нечітке"))
                .andExpect(status().isOk());
        JsonNode memberView = body(send(owner, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk()));
        assertThat(memberView.get("readings").get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(memberView.get("readings").get(0).get("rejectReason").asString()).isEqualTo("Фото нечітке");
        assertThat(intervals(memberView)).hasSize(2);
    }

    /** §2.4 #11: correcting a value or a date, and deleting a reading, recalculates the intervals around it. */
    @Test
    void section24_aCorrectionOrADeletionRecalculatesTheIntervalsAround() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        JsonNode first = entered(meter, "2025-09-03", "8140");
        JsonNode middle = entered(meter, "2025-09-20", "8170");
        entered(meter, "2025-10-05", "8205");

        send(chairman, put(READINGS + "/" + middle.get("reading").get("id").asString()),
                Map.of("takenOn", "2025-09-20", "value", "8180")).andExpect(status().isOk());
        assertThat(intervals(history(meter))).containsExactly("2025-09-20..2025-10-05=25.000",
                "2025-09-03..2025-09-20=40.000", "2025-06-01..2025-09-03=140.000");
        UUID corrected = approvedOn(meter, "2025-09-20");

        send(chairman, put(READINGS + "/" + first.get("reading").get("id").asString()),
                Map.of("takenOn", "2025-09-10", "value", "8140")).andExpect(status().isOk());
        assertThat(intervals(history(meter))).contains("2025-09-10..2025-09-20=40.000",
                "2025-06-01..2025-09-10=140.000");

        send(chairman, delete(READINGS + "/" + corrected), null).andExpect(status().isNoContent());
        assertThat(intervals(history(meter))).containsExactly("2025-09-10..2025-10-05=65.000",
                "2025-06-01..2025-09-10=140.000");
        // negative: a correction that breaks the order is refused and changes nothing
        send(chairman, put(READINGS + "/" + first.get("reading").get("id").asString()), Map.of("takenOn", "2025-09-10",
                "value", "8140")).andExpect(status().is4xxClientError());
        UUID current = approvedOn(meter, "2025-09-10");
        send(chairman, put(READINGS + "/" + current), Map.of("takenOn", "2025-09-10", "value", "9999"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"));
    }

    // ---- §2.5 Consumption: every row of the worked examples

    /** Row "Normal interval" and "Long interval": 03.09: 8 140 and 05.10: 8 205 give 65; the next on 28.01: 8 552 gives 412. */
    @Test
    void section25_normalAndLongIntervals() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-01-01", "8000"));
        entered(meter, "2025-09-03", "8140");
        entered(meter, "2025-10-05", "8205");
        entered(meter, "2026-01-28", "8552");

        assertThat(consumption(meter, "2025-09-03", "2025-10-05").get("consumption").asString()).isEqualTo("65.000");
        assertThat(consumption(meter, "2025-09-03", "2026-01-28").get("consumption").asString())
                .isEqualTo("412.000");
        assertThat(intervals(history(meter)).getFirst()).isEqualTo("2025-10-05..2026-01-28=347.000");
    }

    /** Row "Replacement": old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 with 3, 05.10: 69 gives 65 + 66 = 131. */
    @Test
    void section25_replacementAddsUpBothMeters() throws Exception {
        UUID old = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "8000"));
        entered(old, "2025-09-03", "8140");
        UUID replacement = replace(old, "2025-09-20", "8205", "MA-2", "3");
        entered(replacement, "2025-10-05", "69");

        assertThat(consumption(replacement, "2025-09-03", "2025-10-05").get("consumption").asString())
                .isEqualTo("131.000");
    }

    /** Row "Wrap-around": 03.09: 99 950, self-replacement 15.09 final 99 999 initial 0, 05.10: 12 gives 49 + 12 = 61. */
    @Test
    void section25_wrapAroundAddsUpBothParts() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-06-01", "99000"));
        entered(meter, "2025-09-03", "99950");
        UUID wrapped = replace(meter, "2025-09-15", "99999", "MA-1", "0");
        entered(wrapped, "2025-10-05", "12");

        assertThat(consumption(wrapped, "2025-09-03", "2025-10-05").get("consumption").asString()).isEqualTo("61.000");
    }

    /** Row "Installed recently": installed 10.10 with 0, 31.10: 120 gives 120 kWh. */
    @Test
    void section25_aRecentlyInstalledMeterCountsFromItsInitialReading() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", "2025-10-10", "0"));
        entered(meter, "2025-10-31", "120");

        assertThat(intervals(history(meter))).containsExactly("2025-10-10..2025-10-31=120.000");
        assertThat(consumption(meter, "2025-10-10", "2025-10-31").get("consumption").asString()).isEqualTo("120.000");
    }

    /** Rows "Range with calculation" and "Range without a later reading", and the two tree criteria of §2.5. */
    @Test
    void section25_aRangeIsCalculatedBetweenReadingsAndUnknownAfterTheLastOne() throws Exception {
        UUID meter = register(plotMeter(newPlot("MA вул. Вишнева"), "ELECTRICITY", "MA-1", INSTALLED, "900"));
        entered(meter, "2025-10-01", "1000");
        entered(meter, "2025-10-31", "1300");

        JsonNode range = consumption(meter, "2025-10-01", "2025-10-16");
        assertThat(range.get("consumption").asString()).isEqualTo("150.000");
        assertThat(range.get("calculated").asBoolean()).isTrue();
        JsonNode node = nodeOf(tree("2025-10-01", "2025-10-16"), meter);
        assertThat(node.get("slices").get(0).get("end").get("kind").asString()).isEqualTo("CALCULATED");
        assertThat(node.get("slices").get(0).get("end").get("value").asString()).isEqualTo("1150.000");
        assertThat(node.get("total").get("consumption").asString()).isEqualTo("150.000");
        // both ends are readings: nothing is calculated
        assertThat(nodeOf(tree("2025-10-01", "2025-10-31"), meter).get("total").get("calculated").asBoolean()).isFalse();

        // a range ending after the last reading: unknown, "no reading after 31 Oct"
        JsonNode unknown = consumption(meter, "2025-10-01", LocalDate.now(KYIV).toString());
        assertThat(unknown.get("known").asBoolean()).isFalse();
        assertThat(unknown.get("lastReadingOn").asString()).isEqualTo("2025-10-31");
        JsonNode unknownNode = nodeOf(tree("2025-10-01", LocalDate.now(KYIV).toString()), meter);
        assertThat(unknownNode.get("slices").get(0).get("end").get("kind").asString()).isEqualTo("UNKNOWN");
        assertThat(unknownNode.get("total").get("known").asBoolean()).isFalse();
        assertThat(unknownNode.get("total").get("lastReadingOn").asString()).isEqualTo("2025-10-31");
    }

    /** Row "Plot without a meter": not on the round, no consumption, not "without a reading". */
    @Test
    void section25_aPlotWithoutAMeterIsNotOnTheRoundAndNeverWithoutAReading() throws Exception {
        String street = "MA вул. Тиха " + UUID.randomUUID().toString().substring(0, 4);
        UUID bare = newPlot(street);
        UUID metered = newPlot(street);
        UUID meter = register(plotMeter(metered, "ELECTRICITY", "MA-1", INSTALLED, "0"));

        List<String> onRound = ids(send(chairman, get(READINGS + "/round").param("street", street), null)
                .andExpect(status().isOk()));
        assertThat(onRound).containsExactly(meter.toString());
        List<String> stale = ids(send(chairman, get(READINGS + "/round").param("street", street)
                .param("noReadingSince", LocalDate.now(KYIV).minusDays(60).toString()), null));
        assertThat(stale).containsExactly(meter.toString());
        send(chairman, get(PLOTS + "/" + bare + "/meters"), null).andExpect(jsonPath("$", hasSize(0)));
        assertThat(ids(send(chairman, get(METERS).param("street", street), null))).doesNotContain(bare.toString());
    }

    // ---- §2.6 Who can see and do what

    /** §2.6 #2: the treasurer reads the register and cards, every change is refused with the permission code. */
    @Test
    void section26_theTreasurerOnlyViews() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", INSTALLED, "0"));
        String treasurer = tokenWithBaseRole("TREASURER");

        send(treasurer, get(METERS), null).andExpect(status().isOk());
        send(treasurer, get(METERS + "/" + meter), null).andExpect(status().isOk());
        send(treasurer, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk());
        send(treasurer, get(TREE).param("from", "2025-03-01").param("to", "2025-03-31"), null).andExpect(status().isOk());
        send(treasurer, post(METERS), plotMeter(plot, "WATER", "MA-2", INSTALLED, "0")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(treasurer, put(METERS + "/" + meter), Map.of("serial", "MA-3")).andExpect(status().isForbidden());
        send(treasurer, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-03-01", "finalReading", "1"))
                .andExpect(status().isForbidden());
        send(treasurer, post(METERS + "/" + meter + "/replace"), replacement("2025-03-01", "1", "MA-4", "0"))
                .andExpect(status().isForbidden());
        send(treasurer, delete(METERS + "/" + meter), null).andExpect(status().isForbidden());
        move(treasurer, meter, null, "2025-03-01").andExpect(status().isForbidden());
        send(treasurer, post(READINGS), Map.of("rows", List.of(row(meter, "2025-03-01", "1"))))
                .andExpect(status().isForbidden());
        assertThat(meterCount(meter)).isEqualTo(1);
        assertThat(readingCount(meter)).isZero();
    }

    /** §2.6 (electrician): the custom role enters readings and sees meters, but manages and approves nothing. */
    @Test
    void section26_theElectricianEntersReadingsAndManagesNothing() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", INSTALLED, "0"));
        String electrician = customRoleToken("METERS_VIEW", "READINGS_ENTER");

        send(electrician, get(READINGS + "/round").param("street", "MA вул. Вишнева"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        assertThat(enter(electrician, meter, "2025-03-01", "10").get("outcome").asString()).isEqualTo("SAVED");
        send(electrician, post(METERS), plotMeter(plot, "WATER", "MA-2", INSTALLED, "0")).andExpect(status().isForbidden());
        send(electrician, get(READINGS).param("status", "PENDING"), null).andExpect(status().isForbidden());
    }

    /** §2.6 #3: "Мої лічильники" lists only own plots' meters; another plot is "not found". */
    @Test
    void section26_aMemberSeesOnlyTheMetersOfTheirOwnPlots() throws Exception {
        UUID own = newPlot("MA вул. Вишнева");
        UUID other = newPlot("MA вул. Вишнева");
        UUID member = newMember(DEMO_TENANT, "ma-owner-" + UUID.randomUUID() + "@example.com");
        own(member, own);
        String token = tokenOf(DEMO_TENANT, member);
        UUID ownMeter = register(plotMeter(own, "ELECTRICITY", "MA-1", INSTALLED, "0"));
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MA-2", INSTALLED, "0"));
        entered(ownMeter, "2025-03-01", "10");

        send(token, get(PLOTS + "/" + own + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(ownMeter.toString()));
        send(token, get(METERS + "/" + ownMeter + "/readings"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.intervals[0].consumption").value("10.000"));
        send(token, get(PLOTS + "/" + other + "/meters"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(token, get(METERS + "/" + otherMeter + "/readings"), null).andExpect(status().isNotFound());
        send(token, post(PLOTS + "/" + other + "/readings"), row(otherMeter, "2025-03-01", "5"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        // the register, the round, the tree and the consumption are for the board
        send(token, get(METERS), null).andExpect(status().isForbidden());
        send(token, get(READINGS + "/round"), null).andExpect(status().isForbidden());
        send(token, get(TREE).param("from", "2025-03-01").param("to", "2025-03-31"), null).andExpect(status().isForbidden());
    }

    /** §2.6 #5: a user of another cooperative gets "not found" for this cooperative's meter; the operator sees none. */
    @Test
    void section26_anotherCooperativeAndThePlatformOperatorSeeNothing() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MA-1", INSTALLED, "0"));
        UUID reading = UUID.fromString(entered(meter, "2025-03-01", "10").get("reading").get("id").asString());
        UUID otherMember = newMember(OTHER_TENANT, "ma-other-" + UUID.randomUUID() + "@example.com");
        giveBaseRole(OTHER_TENANT, otherMember, "CHAIRMAN");
        String other = tokenOf(OTHER_TENANT, otherMember);

        send(other, get(METERS + "/" + meter), null).andExpect(status().isNotFound());
        send(other, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isNotFound());
        send(other, get(PLOTS + "/" + plot + "/meters"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(other, delete(READINGS + "/" + reading), null).andExpect(status().isNotFound());
        send(other, post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2025-03-01", "finalReading", "10"))
                .andExpect(status().isNotFound());
        assertThat(ids(send(other, get(METERS), null).andExpect(status().isOk()))).doesNotContain(meter.toString());
        assertThat(readingStatus(reading)).isEqualTo("APPROVED");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        send(operator, get(METERS), null).andExpect(status().isForbidden());
        send(operator, get(METERS + "/" + meter), null).andExpect(status().isForbidden());
        mvc.perform(get(METERS)).andExpect(status().isUnauthorized());
    }

    // ---- §2.7 Screens (what the API gives them)

    /** §2.7 #2: "without a reading since" lists meters whose last counted reading is older than 60 days, never a bare plot. */
    @Test
    void section27_theStaleFilterListsMetersNotReadForSixtyDays() throws Exception {
        String street = "MA вул. Липова " + UUID.randomUUID().toString().substring(0, 4);
        LocalDate today = LocalDate.now(KYIV);
        UUID bare = newPlot(street);
        UUID fresh = register(plotMeter(newPlot(street), "ELECTRICITY", "MA-1", "2025-01-01", "0"));
        UUID stale = register(plotMeter(newPlot(street), "ELECTRICITY", "MA-2", "2025-01-01", "0"));
        entered(fresh, today.minusDays(10).toString(), "100");
        entered(stale, today.minusDays(75).toString(), "50");

        List<String> listed = ids(send(chairman, get(READINGS + "/round").param("street", street)
                .param("noReadingSince", today.minusDays(60).toString()), null).andExpect(status().isOk()));

        assertThat(listed).containsExactly(stale.toString());
        assertThat(ids(send(chairman, get(READINGS + "/round").param("street", street), null)))
                .containsExactlyInAnyOrder(fresh.toString(), stale.toString());
        send(chairman, get(PLOTS + "/" + bare + "/meters"), null).andExpect(jsonPath("$", hasSize(0)));
    }

    /** §2.7 #3: a per-kWh tariff is "by readings" for a plot with an electricity meter and "no meter" without, not counted. */
    @Test
    void section27_aMeterBasedTariffSaysPerPlotWhetherThereIsAMeter() throws Exception {
        UUID type = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = 'ELECTRICITY'",
                UUID.class, DEMO_TENANT);
        UUID plotWith = newPlot("MA вул. Вишнева");
        UUID plotWithout = newPlot("MA вул. Вишнева");
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("validFrom", "2025-01-01");
        version.put("basis", "PER_KWH");
        version.put("frequency", "MONTHLY");
        version.put("rate", "4.32");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chargeTypeId", type.toString());
        request.put("name", "MA by meter");
        request.put("firstVersion", version);
        UUID tariff = UUID.fromString(body(send(chairman, post("/api/v1/tariffs"), request)
                .andExpect(status().isCreated())).get("id").asString());
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("kind", "SELECTED_PLOTS");
        scope.put("plotIds", List.of(plotWith.toString(), plotWithout.toString()));
        send(chairman, put("/api/v1/tariffs/" + tariff + "/scope"), scope).andExpect(status().isOk());
        try {
            register(plotMeter(plotWith, "ELECTRICITY", "MA-1", "2025-01-10", "0"));

            send(chairman, get(PLOTS + "/" + plotWith + "/tariffs").param("month", "2025-11"), null)
                    .andExpect(jsonPath("$.items[?(@.tariffName == 'MA by meter')].status").value("BY_READINGS"))
                    .andExpect(jsonPath("$.items[?(@.tariffName == 'MA by meter')].amount").isEmpty());
            send(chairman, get(PLOTS + "/" + plotWithout + "/tariffs").param("month", "2025-11"), null)
                    .andExpect(jsonPath("$.items[?(@.tariffName == 'MA by meter')].status").value("NO_METER"))
                    .andExpect(jsonPath("$.items[?(@.tariffName == 'MA by meter')].amount").isEmpty())
                    .andExpect(jsonPath("$.total").value("0.00"));
            JsonNode list = body(send(chairman, get("/api/v1/tariffs"), null).andExpect(status().isOk()));
            long count = -1;
            for (JsonNode item : list) {
                if (item.get("id").asString().equals(tariff.toString())) {
                    count = item.get("plotCount").asLong();
                }
            }
            assertThat(count).as("only the plot with a meter is counted").isEqualTo(1);
        } finally {
            DemoTariffData.clear(ownerJdbc());
            DemoTariffData.restore(ownerJdbc());
        }
    }

    // ---- §2.9 Audit

    /** §2.9 #1: replacing a meter and approving a reading are in the audit log with the actor and old/new values. */
    @Test
    void section29_theAuditLogShowsTheReplacementAndTheApprovalWithTheActor() throws Exception {
        UUID plot = newPlot("MA вул. Вишнева");
        UUID member = newMember(DEMO_TENANT, "ma-owner-" + UUID.randomUUID() + "@example.com");
        own(member, plot);
        UUID old = register(plotMeter(plot, "ELECTRICITY", "MA-ЕМ-1", "2025-06-01", "8000"));
        entered(old, "2025-09-03", "8140");
        UUID pending = UUID.fromString(body(send(tokenOf(DEMO_TENANT, member), post(PLOTS + "/" + plot + "/readings"),
                row(old, "2025-09-10", "8150")).andExpect(status().isCreated())).get("reading").get("id").asString());
        send(chairman, post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isOk());
        UUID replacement = replace(old, "2025-09-20", "8205", "MA-ЕМ-2", "3");

        JsonNode replaced = auditEntry(old, "METER_REPLACED");
        assertThat(replaced.get("actorMemberId").asString()).isEqualTo(chairmanMember.toString());
        assertThat(replaced.get("actorName").asString()).isNotBlank();
        assertThat(replaced.get("entityLabel").asString()).isEqualTo("MA-ЕМ-1");
        assertThat(change(replaced, "finalReading")).isEqualTo("null -> 8205.000");
        assertThat(change(replaced, "removedOn")).isEqualTo("null -> 2025-09-20");
        assertThat(change(replaced, "replacementMeter")).isEqualTo("null -> MA-ЕМ-2");
        JsonNode approved = auditEntry(old, "READING_APPROVED");
        assertThat(approved.get("actorMemberId").asString()).isEqualTo(chairmanMember.toString());
        assertThat(approved.get("entityType").asString()).isEqualTo("METER_READING");
        assertThat(change(approved, "value")).endsWith("8150.000");
        assertThat(auditEntry(replacement, "METER_REGISTERED").get("entityLabel").asString()).isEqualTo("MA-ЕМ-2");
        // negative: a refused change writes nothing
        send(chairman, post(METERS + "/" + old + "/replace"), replacement("2025-10-01", "9000", "MA-ЕМ-3", "0"))
                .andExpect(status().isConflict());
        assertThat(entriesOf(old, "METER_REPLACED")).hasSize(1);
    }

    // ---- §2.10 Demo data

    /** §2.10 #1: the demo chairman sees the substation, two quarters, the pump, the lighting, plot meters and pending readings and exactly one pending reading (plot 7). */
    @Test
    void section210_theDemoCooperativeHasTheNetworkAndOnePendingReading() throws Exception {
        JdbcTemplate owner = ownerJdbc();
        DemoMeterData.restore(owner);
        String demoChairman = TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
        try {
            JsonNode list = body(send(demoChairman, get(METERS), null).andExpect(status().isOk()));
            List<String> names = new ArrayList<>();
            int quarters = 0;
            int substations = 0;
            int plotMeters = 0;
            for (JsonNode meter : list) {
                String kind = meter.get("kind").asString();
                quarters += "QUARTER".equals(kind) ? 1 : 0;
                substations += "SUBSTATION".equals(kind) ? 1 : 0;
                plotMeters += "PLOT".equals(kind) ? 1 : 0;
                if (meter.has("name")) {
                    names.add(meter.get("name").asString());
                }
            }
            assertThat(substations).isEqualTo(1);
            assertThat(quarters).isEqualTo(2);
            assertThat(plotMeters).isGreaterThan(20);
            assertThat(names).contains("Свердловина №1", "Освітлення вул. Вишнева");
            JsonNode pendingList = body(send(demoChairman, get(READINGS).param("status", "PENDING"), null)
                    .andExpect(status().isOk()));
            long demoPending = owner.queryForObject("select count(*) from meter_reading where tenant_id = ? "
                    + "and status = 'PENDING' and meter_id in (select id from meter where serial not like 'MA-%')",
                    Long.class, DEMO_TENANT);
            assertThat(pendingList).hasSize((int) demoPending).hasSize(1);
            assertThat(pendingList.get(0).get("meter").get("plot").get("number").asString()).isEqualTo("7");
        } finally {
            unlinkByEmail(DEMO_TENANT, "chairman@demo.local");
        }
    }

    // ---- helpers

    private String tokenOf(UUID tenant, UUID member) {
        String email = memberEmail(tenant, member);
        return TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
    }

    private String tokenWithBaseRole(String baseCode) {
        UUID member = newMember(DEMO_TENANT, "ma-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return tokenOf(DEMO_TENANT, member);
    }

    /** A token of a fresh member whose only role is a custom role with the permissions (removed after the test). */
    private String customRoleToken(String... permissions) {
        UUID member = newMember(DEMO_TENANT, "ma-custom-" + UUID.randomUUID() + "@example.com");
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "MA " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", DEMO_TENANT,
                        role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", DEMO_TENANT,
                    member, role);
        });
        return tokenOf(DEMO_TENANT, member);
    }

    private UUID newPlot(String street) {
        return newPlot(DEMO_TENANT, street, "PLOT");
    }

    private UUID newPlot(UUID tenant, String street, String kind) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, ?, ?, ?)",
                id, tenant, "MA" + id.toString().substring(0, 8), street, kind,
                new BigDecimal("COMMON".equals(kind) ? "0.00" : "600.00"));
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        plots.add(id);
        return id;
    }

    /** Makes the member the only current owner of the plot since 2020 (removed after the test). */
    private void own(UUID member, UUID plot) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 1, date '2020-01-01')", id, DEMO_TENANT, plot,
                member);
        ownerships.add(id);
    }

    private static Map<String, Object> plotMeter(UUID plot, String resource, String serial, String installedOn,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "PLOT");
        body.put("resource", resource);
        body.put("plotId", plot.toString());
        body.put("serial", serial);
        body.put("installedOn", installedOn);
        body.put("initialReading", initialReading);
        return body;
    }

    private static Map<String, Object> namedMeter(String kind, String name, String serial, String installedOn,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("resource", "ELECTRICITY");
        body.put("serial", serial);
        body.put("name", name);
        body.put("installedOn", installedOn);
        body.put("initialReading", initialReading);
        return body;
    }

    private static Map<String, Object> replacement(String replacedOn, String finalReading, String serial,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replacedOn", replacedOn);
        body.put("finalReading", finalReading);
        body.put("serial", serial);
        body.put("initialReading", initialReading);
        return body;
    }

    private Map<String, Object> plotUpdate(UUID plot, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot));
        body.put("street", "MA вул. Вишнева");
        body.put("kind", kind);
        body.put("areaM2", "COMMON".equals(kind) ? 0 : 600);
        return body;
    }

    private static Map<String, Object> row(UUID meter, String takenOn, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meterId", meter.toString());
        if (takenOn != null) {
            row.put("takenOn", takenOn);
        }
        row.put("value", value);
        return row;
    }

    private UUID register(Map<String, Object> body) throws Exception {
        return UUID.fromString(body(send(chairman, post(METERS), body).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private UUID replace(UUID meter, String replacedOn, String finalReading, String serial, String initialReading)
            throws Exception {
        return UUID.fromString(body(send(chairman, post(METERS + "/" + meter + "/replace"),
                replacement(replacedOn, finalReading, serial, initialReading)).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private JsonNode enterRows(String bearer, List<Map<String, Object>> rows) throws Exception {
        return body(send(bearer, post(READINGS), Map.of("rows", rows)).andExpect(status().isOk())).get("rows");
    }

    private JsonNode enter(String bearer, UUID meter, String takenOn, String value) throws Exception {
        return enterRows(bearer, List.of(row(meter, takenOn, value))).get(0);
    }

    /** Enters one reading as the chairman and expects it saved; returns the row result. */
    private JsonNode entered(UUID meter, String takenOn, String value) throws Exception {
        JsonNode result = enter(chairman, meter, takenOn, value);
        assertThat(result.get("outcome").asString()).as("the reading %s on %s is saved: %s", value, takenOn, result)
                .isEqualTo("SAVED");
        return result;
    }

    private JsonNode history(UUID meter) throws Exception {
        return body(send(chairman, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk()));
    }

    /** The id of the counted reading of the meter on the day. */
    private UUID approvedOn(UUID meter, String takenOn) throws Exception {
        UUID found = null;
        for (JsonNode r : history(meter).get("readings")) {
            if (takenOn.equals(r.get("takenOn").asString()) && "APPROVED".equals(r.get("status").asString())) {
                found = UUID.fromString(r.get("id").asString());
            }
        }
        assertThat(found).as("a counted reading of %s on %s", meter, takenOn).isNotNull();
        return found;
    }

    private JsonNode consumption(UUID meter, String from, String to) throws Exception {
        return body(send(chairman, get(METERS + "/" + meter + "/consumption").param("from", from).param("to", to), null)
                .andExpect(status().isOk()));
    }

    private ResultActions move(String bearer, UUID meter, UUID parent, String effectiveFrom) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (parent != null) {
            body.put("parentMeterId", parent.toString());
        }
        body.put("effectiveFrom", effectiveFrom);
        return send(bearer, put(METERS + "/" + meter + "/parent"), body);
    }

    private JsonNode tree(String from, String to) throws Exception {
        return body(send(chairman, get(TREE).param("from", from).param("to", to), null).andExpect(status().isOk()));
    }

    /** The nodes of the tree whose chain holds the meter, with the shown meter of their parent (null among the roots). */
    private static List<Map.Entry<UUID, JsonNode>> places(JsonNode nodes, UUID parent, UUID meter) {
        List<Map.Entry<UUID, JsonNode>> found = new ArrayList<>();
        for (JsonNode node : nodes) {
            for (JsonNode id : node.get("meterIds")) {
                if (id.asString().equals(meter.toString())) {
                    found.add(new java.util.AbstractMap.SimpleEntry<>(parent, node));
                }
            }
            found.addAll(places(node.get("children"), UUID.fromString(node.get("meter").get("id").asString()), meter));
        }
        return found;
    }

    private static List<UUID> parentsOf(JsonNode tree, UUID meter) {
        List<UUID> parents = new ArrayList<>();
        places(tree.get("roots"), null, meter).forEach(place -> parents.add(place.getKey()));
        return parents;
    }

    private static JsonNode placeUnder(JsonNode tree, UUID meter, UUID parent) {
        List<JsonNode> nodes = places(tree.get("roots"), null, meter).stream()
                .filter(place -> parent.equals(place.getKey())).map(Map.Entry::getValue).toList();
        assertThat(nodes).hasSize(1);
        return nodes.getFirst();
    }

    private static JsonNode nodeOf(JsonNode tree, UUID meter) {
        List<Map.Entry<UUID, JsonNode>> found = places(tree.get("roots"), null, meter);
        assertThat(found).isNotEmpty();
        return found.getFirst().getValue();
    }

    /** The intervals of a history as {@code from..to=consumption}, newest first. */
    private static List<String> intervals(JsonNode history) {
        List<String> result = new ArrayList<>();
        history.get("intervals").forEach(interval -> result.add(interval.get("from").asString() + ".."
                + interval.get("to").asString() + "=" + interval.get("consumption").asString()));
        return result;
    }

    private List<String> pendingIds(String bearer) throws Exception {
        List<String> ids = new ArrayList<>();
        body(send(bearer, get(READINGS).param("status", "PENDING"), null).andExpect(status().isOk()))
                .forEach(item -> ids.add(item.get("reading").get("id").asString()));
        return ids;
    }

    /** The ids of a JSON array of objects with an {@code id}. */
    private List<String> ids(ResultActions actions) throws Exception {
        List<String> ids = new ArrayList<>();
        body(actions).forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private String readingStatus(UUID reading) {
        return ownerJdbc().queryForObject("select status from meter_reading where id = ?", String.class, reading);
    }

    private int readingCount(UUID meter) {
        return ownerJdbc().queryForObject("select count(*) from meter_reading where meter_id = ?", Integer.class, meter);
    }

    private int meterCount(UUID meter) {
        return ownerJdbc().queryForObject("select count(*) from meter where id = ?", Integer.class, meter);
    }

    private int meterCountOnPlot(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from meter where plot_id = ?", Integer.class, plot);
    }

    private String plotRow(UUID plot) {
        return ownerJdbc().queryForObject("select kind || ' ' || (archived_at is not null) from plot where id = ?",
                String.class, plot);
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

    // ---- audit

    private List<JsonNode> entriesOf(UUID entity, String action) throws Exception {
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
            found.addAll(entriesOf(entity, action));
            assertThat(found).hasSize(1);
        });
        return found.getFirst();
    }

    /** {@code old -> new} of a field of an audit entry ({@code null} for an absent value). */
    private static String change(JsonNode entry, String field) {
        for (JsonNode change : entry.get("changes")) {
            if (field.equals(change.get("field").asString())) {
                return text(change, "oldValue") + " -> " + text(change, "newValue");
            }
        }
        throw new AssertionError("The audit entry has no change of " + field + ": " + entry);
    }

    private static String text(JsonNode change, String key) {
        return change.has(key) && !change.get(key).isNull() ? change.get(key).asString() : "null";
    }
}
