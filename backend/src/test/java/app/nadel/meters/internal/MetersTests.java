package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The meter register through the API (spec 010, section 2.1): every kind and resource, one active plot meter per plot
 * and resource, common land, removal versus deletion, the list order and filters, and who may call which endpoint
 * (the board, the treasurer, an electrician-like role, a member for their own and another plot, another cooperative,
 * the platform operator).
 */
class MetersTests extends MeterTestSupport {

    private static final String STREET = "MT вул. Садова";

    // ---- registration

    @Test
    void aPlotMeterIsRegisteredAndListedUnderItsPlotAsActive() throws Exception {
        UUID plot = newPlot(STREET);

        JsonNode meter = body(send(chairman(), post(METERS), withNote(plotMeter(plot, "ELECTRICITY",
                "  MT-ЕМ-104523 ", "2026-06-01", "8000"), "Seal 77")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PLOT"))
                .andExpect(jsonPath("$.resource").value("ELECTRICITY"))
                .andExpect(jsonPath("$.serial").value("MT-ЕМ-104523"))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.plot.id").value(plot.toString()))
                .andExpect(jsonPath("$.plot.number").value(plotNumber(plot)))
                .andExpect(jsonPath("$.plot.street").value(STREET))
                .andExpect(jsonPath("$.installedOn").value("2026-06-01"))
                .andExpect(jsonPath("$.initialReading").value("8000.000"))
                .andExpect(jsonPath("$.note").value("Seal 77"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.deletable").value(true))
                .andExpect(jsonPath("$.removedOn").doesNotExist())
                .andExpect(jsonPath("$.finalReading").doesNotExist())
                .andExpect(jsonPath("$.lastReading.takenOn").value("2026-06-01"))
                .andExpect(jsonPath("$.lastReading.value").value("8000.000")));
        String id = meter.get("id").asString();

        send(chairman(), get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].active").value(true));
        send(chairman(), get(METERS + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value("MT-ЕМ-104523"));
    }

    @Test
    void aPlotHasOneActiveMeterOfEachResource() throws Exception {
        UUID plot = newPlot(STREET);
        register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "8000"));

        send(chairman(), post(METERS), plotMeter(plot, "WATER", "MT-2", "2026-06-01", "12.345"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resource").value("WATER"))
                .andExpect(jsonPath("$.initialReading").value("12.345"));
        send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", "MT-3", "2026-07-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_ACTIVE_EXISTS"));
        send(chairman(), post(METERS), plotMeter(plot, "WATER", "MT-4", "2026-07-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_ACTIVE_EXISTS"));

        assertThat(ownerJdbc().queryForObject("select count(*) from meter where plot_id = ?", Integer.class, plot))
                .isEqualTo(2);
    }

    @Test
    void twoRegistrationsOfTheSamePlotAndResourceAtOnceGiveOneMeter() throws Exception {
        UUID plot = newPlot(STREET);
        String chairman = chairman();
        Callable<Integer> registration = () -> send(chairman, post(METERS),
                plotMeter(plot, "ELECTRICITY", "MT-race", "2026-06-01", "1")).andReturn().getResponse().getStatus();

        List<Integer> statuses = concurrently(registration, registration);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(ownerJdbc().queryForObject("select count(*) from meter where plot_id = ?", Integer.class, plot))
                .isEqualTo(1);
    }

    @Test
    void namedMetersOfEveryKindAreRegisteredWithTheirName() throws Exception {
        for (String kind : new String[] {"SUBSTATION", "QUARTER", "COMMON_CONSUMER"}) {
            send(chairman(), post(METERS), namedMeter(kind, "MT " + kind + " Свердловина №1", "MT-" + kind,
                    "2026-01-15", "100.5")).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.kind").value(kind))
                    .andExpect(jsonPath("$.resource").value("ELECTRICITY"))
                    .andExpect(jsonPath("$.name").value("MT " + kind + " Свердловина №1"))
                    .andExpect(jsonPath("$.plot").doesNotExist())
                    .andExpect(jsonPath("$.initialReading").value("100.500"));
        }
    }

    @Test
    void commonLandHasNoPlotMeters() throws Exception {
        UUID road = newPlot(DEMO_TENANT, STREET, "COMMON", false);

        send(chairman(), post(METERS), plotMeter(road, "ELECTRICITY", "MT-1", "2026-06-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_COMMON_LAND"))
                .andExpect(jsonPath("$.errors[0].field").value("plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_COMMON_LAND"));
        send(chairman(), post(METERS), plotMeter(road, "WATER", "MT-2", "2026-06-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_COMMON_LAND"));
    }

    @Test
    void anArchivedUnknownOrForeignPlotGetsNoMeter() throws Exception {
        UUID archived = newPlot(DEMO_TENANT, STREET, "PLOT", true);
        UUID foreign = newPlot(OTHER_TENANT, STREET, "PLOT", false);

        send(chairman(), post(METERS), plotMeter(archived, "ELECTRICITY", "MT-1", "2026-06-01", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"))
                .andExpect(jsonPath("$.errors[0].field").value("plotId"));
        for (UUID plot : new UUID[] {foreign, UUID.randomUUID()}) {
            send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", "MT-2", "2026-06-01", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("plotId"))
                    .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"));
        }
    }

    @Test
    void theKindDecidesBetweenAPlotAndAName() throws Exception {
        UUID plot = newPlot(STREET);
        Map<String, Object> plotWithoutPlot = plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0");
        plotWithoutPlot.remove("plotId");
        Map<String, Object> plotWithName = plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0");
        plotWithName.put("name", "Name");
        Map<String, Object> namedWithPlot = namedMeter("QUARTER", "MT Квартал", "MT-1", "2026-06-01", "0");
        namedWithPlot.put("plotId", plot.toString());

        expectFieldProblem(plotWithoutPlot, "plotId", "REQUIRED");
        expectFieldProblem(plotWithName, "name", "INVALID");
        expectFieldProblem(namedWithPlot, "plotId", "INVALID");
        expectFieldProblem(namedMeter("QUARTER", "  ", "MT-1", "2026-06-01", "0"), "name", "REQUIRED");
        Map<String, Object> water = namedMeter("COMMON_CONSUMER", "MT Колонка", "MT-1", "2026-06-01", "0");
        water.put("resource", "WATER");
        expectFieldProblem(water, "resource", "INVALID");
    }

    @Test
    void theSerialAndTheReadingAreValidatedAtTheBoundary() throws Exception {
        UUID plot = newPlot(STREET);

        expectFieldProblem(plotMeter(plot, "ELECTRICITY", "   ", "2026-06-01", "0"), "serial", "REQUIRED");
        expectFieldProblem(plotMeter(plot, "ELECTRICITY", "", "2026-06-01", "0"), "serial", "REQUIRED");
        expectFieldProblem(plotMeter(plot, "ELECTRICITY", "MT-" + "x".repeat(48), "2026-06-01", "0"), "serial",
                "TOO_LONG");
        for (String bad : new String[] {"8000.1234", "-1", "1,5", "1e3", "abc", "123456789012"}) {
            expectFieldProblem(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", bad), "initialReading",
                    "METER_VALUE_INVALID");
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from meter where plot_id = ?", Integer.class, plot))
                .isZero();
    }

    @Test
    void aMeterIsNotInstalledInTheFuture() throws Exception {
        UUID plot = newPlot(STREET);
        String tomorrow = today().plusDays(1).toString();

        send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", "MT-1", tomorrow, "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_DATE_IN_FUTURE"))
                .andExpect(jsonPath("$.errors[0].field").value("installedOn"));
        send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", "MT-1", today().toString(), "0"))
                .andExpect(status().isCreated());
    }

    // ---- list

    @Test
    void theRegisterListsPlotMetersByStreetAndNumberThenNamedMetersByName() throws Exception {
        UUID sadova = newPlot("MT Яблунева");
        UUID vyshneva = newPlot("MT Вишнева");
        UUID noStreet = newPlot(null);
        UUID sadovaMeter = register(plotMeter(sadova, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID sadovaWater = register(plotMeter(sadova, "WATER", "MT-2", "2026-06-01", "0"));
        UUID vyshnevaMeter = register(plotMeter(vyshneva, "ELECTRICITY", "MT-3", "2026-06-01", "0"));
        UUID noStreetMeter = register(plotMeter(noStreet, "ELECTRICITY", "MT-4", "2026-06-01", "0"));
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина", "MT-5", "2026-06-01", "0"));
        UUID quarter = register(namedMeter("QUARTER", "MT Квартал А", "MT-6", "2026-06-01", "0"));

        List<String> ids = ids(send(chairman(), get(METERS), null).andExpect(status().isOk()));

        assertThat(ids.stream().filter(id -> List.of(sadovaMeter, sadovaWater, vyshnevaMeter, noStreetMeter, pump,
                quarter).stream().map(UUID::toString).toList().contains(id)).toList())
                .containsExactly(vyshnevaMeter.toString(), sadovaMeter.toString(), sadovaWater.toString(),
                        noStreetMeter.toString(), quarter.toString(), pump.toString());
    }

    @Test
    void theFiltersCombine() throws Exception {
        UUID plot = newPlot(STREET);
        UUID other = newPlot("MT Інша");
        UUID electricity = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-01-01", "0"));
        UUID water = register(plotMeter(plot, "WATER", "MT-2", "2026-01-01", "0"));
        UUID elsewhere = register(plotMeter(other, "ELECTRICITY", "MT-3", "2026-01-01", "0"));
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина", "MT-4", "2026-01-01", "0"));
        reading(elsewhere, "2026-06-01", "50", "APPROVED");
        // only a plot connected to water owes a water reading (feature 011 §2.8)
        ownerJdbc().update("insert into plot_water_connection (tenant_id, plot_id, valid_from) "
                + "values (?, ?, date '2026-01-01')", DEMO_TENANT, plot);

        assertThat(ids(send(chairman(), get(METERS).param("resource", "WATER"), null)))
                .contains(water.toString()).doesNotContain(electricity.toString(), pump.toString());
        assertThat(ids(send(chairman(), get(METERS).param("kind", "COMMON_CONSUMER"), null)))
                .contains(pump.toString()).doesNotContain(electricity.toString(), water.toString());
        assertThat(ids(send(chairman(), get(METERS).param("street", "  mt вул. садова "), null)))
                .containsExactly(electricity.toString(), water.toString());
        assertThat(ids(send(chairman(), get(METERS).param("street", STREET).param("resource", "ELECTRICITY"), null)))
                .containsExactly(electricity.toString());
        // the initial reading counts as the last reading of a meter without readings
        assertThat(ids(send(chairman(), get(METERS).param("noReadingSince", "2026-03-01"), null)))
                .contains(electricity.toString(), water.toString(), pump.toString())
                .doesNotContain(elsewhere.toString());
        assertThat(ids(send(chairman(), get(METERS).param("noReadingSince", "2026-01-01"), null)))
                .doesNotContain(electricity.toString(), water.toString(), pump.toString(), elsewhere.toString());
    }

    @Test
    void removedMetersAreListedOnlyWhenAskedFor() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-01-01", "0"));
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-02-01", "10"))
                .andExpect(status().isOk());

        assertThat(ids(send(chairman(), get(METERS), null))).doesNotContain(meter.toString());
        assertThat(ids(send(chairman(), get(METERS).param("includeRemoved", "true"), null)))
                .contains(meter.toString());
        // a removed meter is never "without a reading since"
        assertThat(ids(send(chairman(), get(METERS).param("includeRemoved", "true")
                .param("noReadingSince", "2026-10-01"), null))).doesNotContain(meter.toString());
    }

    @Test
    void theLastReadingIsTheLatestApprovedReading() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-01-01", "8000"));
        reading(meter, "2026-03-03", "8140", "APPROVED");
        reading(meter, "2026-02-01", "8100", "APPROVED");
        reading(meter, "2026-04-01", "8300", "PENDING");
        reading(meter, "2026-04-02", "8301", "REJECTED");

        send(chairman(), get(METERS + "/" + meter), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReading.takenOn").value("2026-03-03"))
                .andExpect(jsonPath("$.lastReading.value").value("8140.000"))
                .andExpect(jsonPath("$.deletable").value(false));
    }

    // ---- update

    @Test
    void theSerialNameAndNoteAreCorrected() throws Exception {
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина", "MT-1", "2026-01-01", "0"));

        send(chairman(), put(METERS + "/" + pump), Map.of("serial", "MT-2", "name", " MT Свердловина №1 ",
                "note", "Pump house")).andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value("MT-2"))
                .andExpect(jsonPath("$.name").value("MT Свердловина №1"))
                .andExpect(jsonPath("$.note").value("Pump house"))
                .andExpect(jsonPath("$.kind").value("COMMON_CONSUMER"));
        send(chairman(), put(METERS + "/" + pump), Map.of("serial", "MT-2", "name", "MT Свердловина №1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").doesNotExist());
        send(chairman(), put(METERS + "/" + pump), Map.of("serial", "MT-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void aPlotMeterHasNoName() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-01-01", "0"));

        send(chairman(), put(METERS + "/" + meter), Map.of("serial", "MT-1", "name", "Kitchen"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), put(METERS + "/" + meter), Map.of("serial", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("serial"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), put(METERS + "/" + UUID.randomUUID()), Map.of("serial", "MT-1"))
                .andExpect(status().isNotFound());
    }

    // ---- remove

    @Test
    void aRemovedMeterKeepsItsReadingsAndThePlotCanGetANewMeter() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        reading(meter, "2026-09-03", "8140", "APPROVED");

        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-10-15", "8420"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.deletable").value(false))
                .andExpect(jsonPath("$.removedOn").value("2026-10-15"))
                .andExpect(jsonPath("$.finalReading").value("8420.000"))
                .andExpect(jsonPath("$.lastReading.takenOn").value("2026-10-15"))
                .andExpect(jsonPath("$.lastReading.value").value("8420.000"));

        assertThat(ownerJdbc().queryForObject("select count(*) from meter_reading where meter_id = ?",
                Integer.class, meter)).isEqualTo(1);
        UUID replacement = register(plotMeter(plot, "ELECTRICITY", "MT-2", "2026-10-15", "3"));
        send(chairman(), get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(replacement.toString()))
                .andExpect(jsonPath("$[1].id").value(meter.toString()))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test
    void aMeterIsRemovedOnce() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-07-01", "10")).andExpect(status().isOk());

        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-08-01", "20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_REMOVED"));
    }

    @Test
    void theRemovalDateAndTheFinalReadingFollowTheReadings() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        reading(meter, "2026-09-03", "8140", "APPROVED");
        reading(meter, "2026-09-20", "9999", "REJECTED"); // not counted

        expectRemovalConflict(meter, removal(today().plusDays(1).toString(), "9000"), "READING_DATE_IN_FUTURE",
                "removedOn");
        expectRemovalConflict(meter, removal("2026-05-31", "9000"), "READING_DATE_OUTSIDE_METER", "removedOn");
        expectRemovalConflict(meter, removal("2026-09-02", "9000"), "READING_OUT_OF_ORDER", "removedOn");
        expectRemovalConflict(meter, removal("2026-09-10", "8139.999"), "READING_OUT_OF_ORDER", "finalReading");
        // a final reading on the day of the last counted reading may equal it; the rejected reading does not count
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-09-03", "8140"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedOn").value("2026-09-03"));
    }

    @Test
    void aMeterWithAReadingWaitingForApprovalIsNotRemoved() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        reading(meter, "2026-09-20", "8100", "PENDING");

        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-10-01", "8200"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_HAS_PENDING_READINGS"));
        send(chairman(), get(METERS + "/" + meter), null).andExpect(jsonPath("$.active").value(true));

        // once the reading is decided the meter is removed
        ownerJdbc().update("update meter_reading set status = 'REJECTED', reject_reason = 'Wrong' where meter_id = ?",
                meter);
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-10-01", "8200"))
                .andExpect(status().isOk());
    }

    @Test
    void aMeterWithoutReadingsIsRemovedNotBeforeItsInitialReading() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "WATER", "MT-1", "2026-06-01", "12.5"));

        expectRemovalConflict(meter, removal("2026-07-01", "12.499"), "READING_OUT_OF_ORDER", "finalReading");
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-06-01", "12.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalReading").value("12.500"));
    }

    @Test
    void aMalformedFinalReadingIsABadRequest() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-07-01", "1.0001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("finalReading"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        send(chairman(), post(METERS + "/" + UUID.randomUUID() + "/remove"), removal("2026-07-01", "1"))
                .andExpect(status().isNotFound());
    }

    // ---- delete

    @Test
    void aMeterWithoutReadingsIsDeleted() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isNoContent());

        assertThat(meterCount(meter)).isZero();
        send(chairman(), get(METERS + "/" + meter), null).andExpect(status().isNotFound());
        send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isNotFound());
    }

    @Test
    void aMeterWithReadingsOfAnyStatusIsRemovedInsteadOfDeleted() throws Exception {
        for (String status : new String[] {"APPROVED", "PENDING", "REJECTED"}) {
            UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-" + status, "2026-06-01", "0"));
            reading(meter, "2026-07-01", "5", status);

            send(chairman(), get(METERS + "/" + meter), null).andExpect(jsonPath("$.deletable").value(false));
            send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("METER_HAS_READINGS"));
            assertThat(meterCount(meter)).isEqualTo(1);
        }
    }

    @Test
    void aRemovedMeterIsNotDeleted() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        send(chairman(), post(METERS + "/" + meter + "/remove"), removal("2026-07-01", "1")).andExpect(status().isOk());

        send(chairman(), delete(METERS + "/" + meter), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_REMOVED"));
        assertThat(meterCount(meter)).isEqualTo(1);
    }

    // ---- who may do what

    @Test
    void theTreasurerSeesTheRegisterButChangesNothing() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        String treasurer = demo("treasurer@demo.local");

        send(treasurer, get(METERS), null).andExpect(status().isOk());
        send(treasurer, get(METERS + "/" + meter), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value("MT-1"));
        send(treasurer, get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk());
        expectEveryWriteDenied(treasurer, plot, meter);
    }

    @Test
    void anElectricianLikeRoleReadsButDoesNotManage() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        String electrician = customRoleToken("METERS_VIEW", "READINGS_ENTER");

        send(electrician, get(METERS), null).andExpect(status().isOk());
        send(electrician, get(METERS + "/" + meter), null).andExpect(status().isOk());
        send(electrician, get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(meter.toString()));
        expectEveryWriteDenied(electrician, plot, meter);
    }

    @Test
    void managingWithoutViewingIsDenied() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        String manager = customRoleToken("METERS_MANAGE");

        send(manager, get(METERS), null).andExpect(status().isForbidden());
        expectEveryWriteDenied(manager, plot, meter);
    }

    @Test
    void aCustomRoleWithBothPermissionsManagesMeters() throws Exception {
        UUID plot = newPlot(STREET);
        String manager = customRoleToken("METERS_VIEW", "METERS_MANAGE");

        String id = body(send(manager, post(METERS), plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"))
                .andExpect(status().isCreated())).get("id").asString();
        send(manager, put(METERS + "/" + id), Map.of("serial", "MT-2")).andExpect(status().isOk());
        send(manager, delete(METERS + "/" + id), null).andExpect(status().isNoContent());
    }

    @Test
    void aMemberSeesTheMetersOfTheirOwnPlotWithoutTheNoteAndNotFoundForAnyOther() throws Exception {
        UUID own = newPlot(STREET);
        UUID other = newPlot(STREET);
        RoleMember member = customRoleMember(); // a member without permissions
        own(member.id(), own);
        UUID meter = register(withNote(plotMeter(own, "ELECTRICITY", "MT-1", "2026-06-01", "8000"), "Board only"));
        register(plotMeter(other, "ELECTRICITY", "MT-2", "2026-06-01", "0"));
        String token = app.nadel.TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), member.email());

        send(token, get("/api/v1/plots/" + own + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(meter.toString()))
                .andExpect(jsonPath("$[0].serial").value("MT-1"))
                .andExpect(jsonPath("$[0].lastReading.value").value("8000.000"))
                .andExpect(jsonPath("$[0].note").doesNotExist());
        send(token, get("/api/v1/plots/" + other + "/meters"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(token, get("/api/v1/plots/" + UUID.randomUUID() + "/meters"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(token, get(METERS), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(token, get(METERS + "/" + meter), null).andExpect(status().isForbidden());
        expectEveryWriteDenied(token, own, meter);
        // the board sees the note on the same endpoint
        send(chairman(), get("/api/v1/plots/" + own + "/meters"), null)
                .andExpect(jsonPath("$[0].note").value("Board only"));
    }

    @Test
    void theDemoMemberSeesTheirOwnPlotOnlyEvenWithoutMeters() throws Exception {
        String member = demo("member@demo.local"); // owns plot 15 alone and half of plot 7

        send(member, get("/api/v1/plots/" + demoPlot("15") + "/meters"), null).andExpect(status().isOk());
        send(member, get("/api/v1/plots/" + demoPlot("1") + "/meters"), null).andExpect(status().isNotFound());
    }

    @Test
    void commonLandHasAnEmptyMeterList() throws Exception {
        UUID road = newPlot(DEMO_TENANT, STREET, "COMMON", false);

        send(chairman(), get("/api/v1/plots/" + road + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anotherCooperativeSeesNothingOfTheseMeters() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        String other = otherChairman();

        send(other, get(METERS + "/" + meter), null).andExpect(status().isNotFound());
        assertThat(ids(send(other, get(METERS).param("includeRemoved", "true"), null).andExpect(status().isOk())))
                .doesNotContain(meter.toString());
        send(other, get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(other, put(METERS + "/" + meter), Map.of("serial", "MT-x")).andExpect(status().isNotFound());
        send(other, post(METERS + "/" + meter + "/remove"), removal("2026-07-01", "1"))
                .andExpect(status().isNotFound());
        send(other, post(METERS + "/" + meter + "/replace"), Map.of("replacedOn", "2026-07-01", "finalReading", "1",
                "serial", "MT-x", "initialReading", "0")).andExpect(status().isNotFound());
        send(other, delete(METERS + "/" + meter), null).andExpect(status().isNotFound());
        assertThat(meterCount(meter)).isEqualTo(1);
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        String operator = operator();

        send(operator, get(METERS), null).andExpect(status().isForbidden());
        send(operator, get(METERS + "/" + meter), null).andExpect(status().isForbidden());
        send(operator, get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        expectEveryWriteDenied(operator, plot, meter);
    }

    @Test
    void withoutATokenTheAnswerIsUnauthorized() throws Exception {
        mvc.perform(get(METERS)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/plots/" + UUID.randomUUID() + "/meters")).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    private void expectEveryWriteDenied(String bearer, UUID plot, UUID meter) throws Exception {
        send(bearer, post(METERS), plotMeter(plot, "WATER", "MT-denied", "2026-06-01", "0"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(bearer, put(METERS + "/" + meter), Map.of("serial", "MT-denied")).andExpect(status().isForbidden());
        send(bearer, post(METERS + "/" + meter + "/remove"), removal("2026-07-01", "1"))
                .andExpect(status().isForbidden());
        send(bearer, post(METERS + "/" + meter + "/replace"), Map.of("replacedOn", "2026-07-01", "finalReading", "1",
                "serial", "MT-denied", "initialReading", "0")).andExpect(status().isForbidden());
        send(bearer, delete(METERS + "/" + meter), null).andExpect(status().isForbidden());
        assertThat(meterCount(meter)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from meter where replaces_meter_id = ?", Integer.class,
                meter)).isZero();
        assertThat(ownerJdbc().queryForObject("select serial from meter where id = ?", String.class, meter))
                .isNotEqualTo("MT-denied");
    }

    private void expectFieldProblem(Map<String, Object> body, String field, String code) throws Exception {
        send(chairman(), post(METERS), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code").value(code));
    }

    private void expectRemovalConflict(UUID meter, Map<String, Object> body, String code, String field)
            throws Exception {
        send(chairman(), post(METERS + "/" + meter + "/remove"), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
        send(chairman(), get(METERS + "/" + meter), null).andExpect(jsonPath("$.active").value(true));
    }

    private static Map<String, Object> removal(String removedOn, String finalReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removedOn", removedOn);
        body.put("finalReading", finalReading);
        return body;
    }

    private static Map<String, Object> withNote(Map<String, Object> body, String note) {
        body.put("note", note);
        return body;
    }

    private List<String> ids(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        List<String> ids = new ArrayList<>();
        body(actions.andExpect(status().isOk())).forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    @SafeVarargs
    private static <T> List<T> concurrently(Callable<T>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
