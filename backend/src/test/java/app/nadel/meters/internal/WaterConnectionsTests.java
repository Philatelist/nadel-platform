package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.meters.WaterConnections;
import app.nadel.meters.WaterConnections.ConnectedPeriod;
import app.nadel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Water connections (feature 011 §2.8): a plot is connected to a water source (a common consumer meter or the
 * cooperative's water supply) from a date and disconnected on a date, with its history; one lasting connection and no
 * shared day (409 {@code WATER_ALREADY_CONNECTED}); bulk connection and disconnection from a source (all or nothing);
 * the source rules ({@code WATER_SOURCE_INVALID}); who may see and change them; the audit entries; the missing-readings
 * list that leaves out the water meters of plots that are not connected; the read facade the ledger uses; a well with
 * connections is not deleted.
 */
class WaterConnectionsTests extends MeterTestSupport {

    private static final String SOURCES = "/api/v1/water-sources";

    @Autowired
    WaterConnections facade;

    @Autowired
    PlatformTransactionManager transactionManager;

    private static String connections(UUID plot) {
        return "/api/v1/plots/" + plot + "/water-connections";
    }

    private static Map<String, Object> connection(UUID source, String from) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (source != null) {
            body.put("sourceMeterId", source.toString());
        }
        body.put("from", from);
        return body;
    }

    private static Map<String, Object> bulk(List<UUID> plots, String dateField, String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotIds", plots.stream().map(UUID::toString).toList());
        body.put(dateField, date);
        return body;
    }

    private UUID well(String serial) throws Exception {
        return register(namedMeter("COMMON_CONSUMER", "MT Свердловина " + serial, serial, "2020-01-01", "0"));
    }

    private JsonNode connect(UUID plot, UUID source, String from) throws Exception {
        return body(send(chairman(), post(connections(plot)), connection(source, from))
                .andExpect(status().isCreated()));
    }

    private JsonNode end(UUID plot, String connectionId, String until) throws Exception {
        return body(send(chairman(), post(connections(plot) + "/" + connectionId + "/end"), Map.of("until", until))
                .andExpect(status().isOk()));
    }

    // ---- the plot card

    @Test
    void theChairmanConnectsAPlotToAWellAndTheCardShowsIt() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        UUID well = well("MT-W1");

        JsonNode answer = connect(plot, well, "2020-05-01");

        assertThat(answer.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(answer.get("connected").asBoolean()).isTrue();
        JsonNode current = answer.get("current");
        assertThat(current.get("source").get("id").asString()).isEqualTo(well.toString());
        assertThat(current.get("source").get("kind").asString()).isEqualTo("METER");
        assertThat(current.get("source").get("name").asString()).isEqualTo("MT Свердловина MT-W1");
        assertThat(current.get("from").asString()).isEqualTo("2020-05-01");
        assertThat(current.has("until")).isFalse();
        assertThat(answer.get("connections")).hasSize(1);
        assertThat(body(send(chairman(), get(connections(plot)), null).andExpect(status().isOk()))).isEqualTo(answer);
    }

    @Test
    void withoutASourceThePlotIsConnectedToTheCooperativeSupply() throws Exception {
        UUID plot = newPlot("MT Вишнева");

        JsonNode source = connect(plot, null, "2026-01-01").get("current").get("source");

        assertThat(source.get("id").asString()).isEqualTo("cooperative");
        assertThat(source.get("kind").asString()).isEqualTo("COOPERATIVE_SUPPLY");
        assertThat(source.has("name")).isFalse();
    }

    @Test
    void aConnectedPlotIsNotConnectedAgain() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        connect(plot, null, "2020-05-01");

        send(chairman(), post(connections(plot)), connection(well("MT-W1"), "2026-10-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATER_ALREADY_CONNECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        assertThat(connectionRows(plot)).isEqualTo(1);
    }

    @Test
    void aDisconnectionKeepsTheHistoryAndAConnectionMayNotShareADayWithIt() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        String id = connect(plot, null, "2020-05-01").get("current").get("id").asString();

        JsonNode ended = end(plot, id, "2026-10-15");

        assertThat(ended.get("connections")).singleElement().satisfies(c -> {
            assertThat(c.get("from").asString()).isEqualTo("2020-05-01");
            assertThat(c.get("until").asString()).isEqualTo("2026-10-15");
        });
        // ended before today (the tests run after 15.10.2026): not connected now
        if (kyivToday().isAfter(LocalDate.parse("2026-10-15"))) {
            assertThat(ended.get("connected").asBoolean()).isFalse();
            assertThat(ended.has("current")).isFalse();
        }
        send(chairman(), post(connections(plot) + "/" + id + "/end"), Map.of("until", "2026-10-20"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATER_NOT_CONNECTED"));
        send(chairman(), post(connections(plot)), connection(null, "2026-10-15"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATER_ALREADY_CONNECTED"));

        JsonNode again = connect(plot, null, "2026-10-16");

        assertThat(again.get("connections")).hasSize(2);
        assertThat(again.get("connections").get(0).get("from").asString()).isEqualTo("2026-10-16");
        assertThat(again.get("connections").get(1).get("until").asString()).isEqualTo("2026-10-15");
    }

    @Test
    void anEndBeforeTheStartOrAnUnknownConnectionIsRefused() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        String id = connect(plot, null, "2026-05-01").get("current").get("id").asString();
        String otherId = connect(newPlot("MT Вишнева"), null, "2026-05-01").get("current").get("id").asString();

        send(chairman(), post(connections(plot) + "/" + id + "/end"), Map.of("until", "2026-04-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("until"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), post(connections(plot) + "/" + otherId + "/end"), Map.of("until", "2026-06-01"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), post(connections(plot) + "/" + id + "/end"), Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("until"));
    }

    @Test
    void onlyAnActiveCommonConsumerMeterIsAWaterSource() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        UUID plotMeter = register(plotMeter(newPlot("MT Вишнева"), "WATER", "MT-P1", "2026-01-01", "0"));
        UUID quarter = register(namedMeter("QUARTER", "MT Квартал", "MT-Q1", "2026-01-01", "0"));
        UUID removed = well("MT-W2");
        send(chairman(), post(METERS + "/" + removed + "/remove"),
                Map.of("removedOn", "2026-02-01", "finalReading", "10")).andExpect(status().isOk());

        for (UUID source : List.of(plotMeter, quarter, removed, UUID.randomUUID())) {
            send(chairman(), post(connections(plot)), connection(source, "2026-03-01"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WATER_SOURCE_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("sourceMeterId"));
        }
        assertThat(connectionRows(plot)).isZero();
    }

    @Test
    void archivedPlotsCommonLandAndUnknownPlotsAreNotConnected() throws Exception {
        send(chairman(), post(connections(newPlot(DEMO_TENANT, "MT", "PLOT", true))), connection(null, "2026-01-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        send(chairman(), post(connections(newPlot(DEMO_TENANT, "MT", "COMMON", false))),
                connection(null, "2026-01-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        send(chairman(), post(connections(UUID.randomUUID())), connection(null, "2026-01-01"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(chairman(), post(connections(newPlot("MT Вишнева"))), Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    // ---- a water source in bulk

    @Test
    void theChairmanConnectsFiveSelectedPlotsToTheWellAndTheSourceListsThem() throws Exception {
        UUID well = well("MT-W1");
        List<UUID> plots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            plots.add(newPlot("MT Вишнева"));
        }

        JsonNode source = body(send(chairman(), post(SOURCES + "/" + well + "/connections"),
                bulk(plots, "from", "2026-10-01")).andExpect(status().isOk()));

        assertThat(source.get("id").asString()).isEqualTo(well.toString());
        assertThat(source.get("kind").asString()).isEqualTo("METER");
        assertThat(source.get("plots")).hasSize(5);
        assertThat(source.get("plots").get(0).get("from").asString()).isEqualTo("2026-10-01");
        for (UUID plot : plots) {
            JsonNode card = body(send(chairman(), get(connections(plot)), null).andExpect(status().isOk()));
            assertThat(card.get("connections")).singleElement().satisfies(c -> {
                assertThat(c.get("source").get("id").asString()).isEqualTo(well.toString());
                assertThat(c.get("from").asString()).isEqualTo("2026-10-01");
            });
        }
        JsonNode sources = body(send(chairman(), get(SOURCES), null).andExpect(status().isOk()));
        assertThat(sources.get(0).get("id").asString()).isEqualTo("cooperative");
        JsonNode listed = null;
        for (JsonNode s : sources) {
            if (s.get("id").asString().equals(well.toString())) {
                listed = s;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("name").asString()).isEqualTo("MT Свердловина MT-W1");
        List<String> listedPlots = new ArrayList<>();
        listed.get("plots").forEach(p -> listedPlots.add(p.get("plotId").asString()));
        assertThat(listedPlots).containsExactlyInAnyOrderElementsOf(plots.stream().map(UUID::toString).toList());
    }

    @Test
    void aBulkConnectionIsAllOrNothing() throws Exception {
        UUID free = newPlot("MT Вишнева");
        UUID connected = newPlot("MT Вишнева");
        connect(connected, null, "2026-01-01");

        send(chairman(), post(SOURCES + "/cooperative/connections"), bulk(List.of(free, connected), "from",
                "2026-10-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATER_ALREADY_CONNECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"));
        send(chairman(), post(SOURCES + "/cooperative/connections"), bulk(List.of(free, free, UUID.randomUUID(),
                newPlot(DEMO_TENANT, "MT", "COMMON", false), newPlot(DEMO_TENANT, "MT", "PLOT", true)), "from",
                "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"))
                .andExpect(jsonPath("$.errors[1].code").value("UNKNOWN_PLOT"))
                .andExpect(jsonPath("$.errors[2].code").value("PLOT_COMMON_LAND"))
                .andExpect(jsonPath("$.errors[3].code").value("PLOT_ARCHIVED"));
        send(chairman(), post(SOURCES + "/cooperative/connections"), bulk(List.of(), "from", "2026-10-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plotIds"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        assertThat(connectionRows(free)).isZero();
    }

    @Test
    void theChairmanDisconnectsSelectedPlotsFromTheirSource() throws Exception {
        UUID well = well("MT-W1");
        UUID first = newPlot("MT Вишнева");
        UUID second = newPlot("MT Вишнева");
        UUID elsewhere = newPlot("MT Вишнева");
        send(chairman(), post(SOURCES + "/" + well + "/connections"), bulk(List.of(first, second), "from",
                "2026-01-01")).andExpect(status().isOk());
        connect(elsewhere, null, "2026-01-01");

        send(chairman(), post(SOURCES + "/" + well + "/disconnections"), bulk(List.of(first, elsewhere), "until",
                "2026-03-31"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATER_NOT_CONNECTED"))
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[1]"));
        send(chairman(), post(SOURCES + "/" + well + "/disconnections"), bulk(List.of(first), "until",
                "2025-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotIds[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));

        JsonNode source = body(send(chairman(), post(SOURCES + "/" + well + "/disconnections"),
                bulk(List.of(first, second), "until", "2026-03-31")).andExpect(status().isOk()));

        assertThat(source.get("plots")).isEmpty();
        assertThat(body(send(chairman(), get(connections(first)), null)).get("connections").get(0).get("until")
                .asString()).isEqualTo("2026-03-31");
        assertThat(body(send(chairman(), get(connections(elsewhere)), null)).get("connected").asBoolean()).isTrue();
    }

    @Test
    void aSourceIsTheCooperativeSupplyOrACommonConsumerMeter() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        UUID quarter = register(namedMeter("QUARTER", "MT Квартал", "MT-Q1", "2026-01-01", "0"));
        UUID removed = well("MT-W2");
        send(chairman(), post(METERS + "/" + removed + "/remove"),
                Map.of("removedOn", "2026-02-01", "finalReading", "10")).andExpect(status().isOk());

        send(chairman(), post(SOURCES + "/" + quarter + "/connections"), bulk(List.of(plot), "from", "2026-03-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATER_SOURCE_INVALID"));
        send(chairman(), post(SOURCES + "/" + removed + "/connections"), bulk(List.of(plot), "from", "2026-03-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATER_SOURCE_INVALID"));
        send(chairman(), post(SOURCES + "/" + UUID.randomUUID() + "/connections"),
                bulk(List.of(plot), "from", "2026-03-01")).andExpect(status().isNotFound());
        send(chairman(), post(SOURCES + "/well/connections"), bulk(List.of(plot), "from", "2026-03-01"))
                .andExpect(status().isNotFound());
        assertThat(connectionRows(plot)).isZero();
    }

    // ---- who may see and change

    @Test
    void viewingNeedsPlotsViewOrOwnershipAndChangingNeedsMetersManage() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        UUID other = newPlot("MT Вишнева");
        connect(plot, null, "2026-01-01");
        String treasurer = demo("treasurer@demo.local");
        RoleMember owner = customRoleMember();
        own(owner.id(), plot);
        String ownerToken = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());
        String manager = customRoleToken("METERS_MANAGE");

        send(treasurer, get(connections(plot)), null).andExpect(status().isOk());
        send(treasurer, get(SOURCES), null).andExpect(status().isOk());
        send(treasurer, post(connections(other)), connection(null, "2026-01-01")).andExpect(status().isForbidden());
        send(treasurer, post(SOURCES + "/cooperative/connections"), bulk(List.of(other), "from", "2026-01-01"))
                .andExpect(status().isForbidden());
        send(ownerToken, get(connections(plot)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
        send(ownerToken, get(connections(other)), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(ownerToken, get(SOURCES), null).andExpect(status().isForbidden());
        send(ownerToken, post(SOURCES + "/cooperative/disconnections"), bulk(List.of(plot), "until", "2026-02-01"))
                .andExpect(status().isForbidden());
        send(manager, post(connections(other)), connection(null, "2026-01-01")).andExpect(status().isCreated());
        send(otherChairman(), get(connections(plot)), null).andExpect(status().isNotFound());
        send(otherChairman(), post(connections(plot)), connection(null, "2026-01-01"))
                .andExpect(status().isNotFound());
        send(operator(), get(connections(plot)), null).andExpect(status().isForbidden());
        send(operator(), get(SOURCES), null).andExpect(status().isForbidden());
    }

    // ---- audit

    @Test
    void connectingAndDisconnectingAreAuditedAboutThePlot() throws Exception {
        UUID plot = newPlot("MT Вишнева");
        UUID well = well("MT-W1");
        String id = connect(plot, well, "2020-05-01").get("current").get("id").asString();
        end(plot, id, "2026-10-15");
        send(chairman(), post(SOURCES + "/cooperative/connections"), bulk(List.of(plot), "from", "2026-10-16"))
                .andExpect(status().isOk());

        List<Map<String, Object>> connected = awaitEntries(plot, "WATER_CONNECTED", 2);
        assertThat(connected.getFirst().get("entity_type")).isEqualTo("PLOT");
        assertThat(connected.getFirst().get("entity_label")).isEqualTo(plotNumber(plot));
        assertThat(connected.getFirst().get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(changes(connected.getFirst())).containsExactly(
                new Change("source", null, "MT Свердловина MT-W1"), new Change("from", null, "2020-05-01"));
        assertThat(changes(connected.get(1))).containsExactly(
                new Change("source", null, "base:COOPERATIVE_SUPPLY"), new Change("from", null, "2026-10-16"));
        assertThat(changes(awaitEntries(plot, "WATER_DISCONNECTED", 1).getFirst())).containsExactly(
                new Change("source", null, "MT Свердловина MT-W1"), new Change("from", null, "2020-05-01"),
                new Change("until", null, "2026-10-15"));
        // the audit listing reads the entries back
        send(chairman(), get("/api/v1/audit").param("entityId", plot.toString()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].entityType").value("PLOT"));
    }

    // ---- what other parts of the app see

    @Test
    void aWaterMeterOfAPlotThatIsNotConnectedIsNotMissingReadings() throws Exception {
        UUID dry = newPlot("MT Вишнева");
        UUID wet = newPlot("MT Вишнева");
        UUID dryMeter = register(plotMeter(dry, "WATER", "MT-D1", "2026-01-01", "0"));
        UUID wetMeter = register(plotMeter(wet, "WATER", "MT-D2", "2026-01-01", "0"));
        UUID dryElectricity = register(plotMeter(dry, "ELECTRICITY", "MT-D3", "2026-01-01", "0"));
        connect(wet, null, "2026-01-01");

        List<String> missing = ids(get(METERS).param("noReadingSince", "2026-03-01"));
        List<String> round = ids(get(READINGS + "/round").param("noReadingSince", "2026-03-01"));

        assertThat(missing).contains(wetMeter.toString(), dryElectricity.toString())
                .doesNotContain(dryMeter.toString());
        assertThat(round).contains(wetMeter.toString(), dryElectricity.toString())
                .doesNotContain(dryMeter.toString());
        // without the filter the meter is still in the register
        assertThat(ids(get(METERS).param("resource", "WATER"))).contains(dryMeter.toString());
    }

    @Test
    void theFacadeGivesTheConnectedDaysPerPlotAndPerSource() throws Exception {
        UUID well = well("MT-W1");
        UUID first = newPlot("MT Вишнева");
        UUID second = newPlot("MT Вишнева");
        UUID never = newPlot("MT Вишнева");
        String id = connect(first, well, "2026-09-10").get("current").get("id").asString();
        end(first, id, "2026-09-20");
        connect(first, null, "2026-10-16");
        connect(second, well, "2026-01-01");

        Map<UUID, List<ConnectedPeriod>> days = inCooperative(() -> facade.connectedDays(
                List.of(first, second, never), LocalDate.parse("2026-09-15"), LocalDate.parse("2026-10-31")));
        Map<UUID, List<ConnectedPeriod>> ofWell = inCooperative(() -> facade.connectedToSource(well,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")));
        Map<UUID, List<ConnectedPeriod>> ofSupply = inCooperative(() -> facade.connectedToSource(null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")));

        assertThat(days.get(first)).containsExactly(period("2026-09-15", "2026-09-20"),
                period("2026-10-16", "2026-10-31"));
        assertThat(days.get(second)).containsExactly(period("2026-09-15", "2026-10-31"));
        assertThat(days).doesNotContainKey(never);
        assertThat(ofWell.get(first)).containsExactly(period("2026-09-10", "2026-09-20"));
        assertThat(ofWell.get(second)).containsExactly(period("2026-09-01", "2026-09-30"));
        assertThat(ofSupply.get(first)).containsExactly(period("2026-10-16", "2026-10-31"));
        assertThat(ofSupply).doesNotContainKey(second);
    }

    @Test
    void aWellThatPlotsWereConnectedToIsNotDeleted() throws Exception {
        UUID well = well("MT-W1");
        connect(newPlot("MT Вишнева"), well, "2026-01-01");

        send(chairman(), delete(METERS + "/" + well), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATER_SOURCE_IN_USE"));
        assertThat(meterCount(well)).isEqualTo(1);
    }

    // ---- helpers

    private int connectionRows(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from plot_water_connection where plot_id = ?",
                Integer.class, plot);
    }

    private List<String> ids(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        List<String> ids = new ArrayList<>();
        body(send(chairman(), request, null).andExpect(status().isOk())).forEach(m -> ids.add(m.get("id").asString()));
        return ids;
    }

    private <T> T inCooperative(java.util.function.Supplier<T> work) {
        return TenantContext.runAs(DEMO_TENANT,
                () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private static ConnectedPeriod period(String from, String to) {
        return new ConnectedPeriod(LocalDate.parse(from), LocalDate.parse(to));
    }
}
