package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Replacing a meter through the API (spec 010, sections 2.3 and 2.5): one action removes the old meter and installs the
 * new one, the plot keeps one active meter, the new meter takes the old one's place in the tree, and the consumption over
 * a range adds up the meters of the chain. The "Replacement" and "Wrap-around" rows of the worked examples run end to
 * end here; the range rows with a calculated value and without a later reading run on the consumption endpoint.
 */
class MeterReplacementTests extends MeterTestSupport {

    private static final String STREET = "MT вул. Березова";

    // ---- the replacement

    @Test
    void aReplacementRemovesTheOldMeterAndInstallsTheNewOneOnTheSameDay() throws Exception {
        UUID plot = newPlot(STREET);
        Map<String, Object> body = plotMeter(plot, "ELECTRICITY", "MT-ЕМ-104523", "2026-06-01", "8000");
        body.put("note", "Seal 77");
        UUID old = register(body);
        entered(old, "2026-09-03", "8140");

        JsonNode created = body(send(chairman(), post(METERS + "/" + old + "/replace"),
                replacement("2026-09-20", "8205", "MT-ЕМ-200001", "3"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/meters/"))));

        UUID replacement = UUID.fromString(created.get("id").asString());
        assertThat(created.get("kind").asString()).isEqualTo("PLOT");
        assertThat(created.get("resource").asString()).isEqualTo("ELECTRICITY");
        assertThat(created.get("plot").get("id").asString()).isEqualTo(plot.toString());
        assertThat(created.get("serial").asString()).isEqualTo("MT-ЕМ-200001");
        assertThat(created.get("installedOn").asString()).isEqualTo("2026-09-20");
        assertThat(created.get("initialReading").asString()).isEqualTo("3.000");
        assertThat(created.get("replacesMeterId").asString()).isEqualTo(old.toString());
        assertThat(created.get("note").asString()).isEqualTo("Seal 77");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("lastReading").get("value").asString()).isEqualTo("3.000");

        send(chairman(), get(METERS + "/" + old), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.removedOn").value("2026-09-20"))
                .andExpect(jsonPath("$.finalReading").value("8205.000"));
        // the plot card shows both, the active one first
        send(chairman(), get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(replacement.toString()))
                .andExpect(jsonPath("$[1].id").value(old.toString()));
        // the new meter holds the plot's one active electricity meter
        send(chairman(), post(METERS), plotMeter(plot, "ELECTRICITY", "MT-3", "2026-09-21", "0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METER_ACTIVE_EXISTS"));
    }

    @Test
    void theReplacementRowOfTheWorkedExamplesAddsUpBothMeters() throws Exception {
        // old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 with 3, 05.10: 69 -> 65 + 66 = 131 kWh
        UUID old = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        entered(old, "2026-09-03", "8140");
        UUID replacement = replace(old, "2026-09-20", "8205", "MT-2", "3");
        entered(replacement, "2026-10-05", "69");

        JsonNode range = consumption(replacement, "2026-09-03", "2026-10-05");
        assertThat(range.get("known").asBoolean()).isTrue();
        assertThat(range.get("consumption").asString()).isEqualTo("131.000");
        assertThat(range.get("calculated").asBoolean()).isFalse();
        assertThat(ids(range.get("meterIds"))).containsExactly(old, replacement);
        // asked through the old meter, the chain is the same
        assertThat(consumption(old, "2026-09-03", "2026-10-05").get("consumption").asString()).isEqualTo("131.000");
        // each meter keeps its own intervals
        assertThat(intervals(history(old))).containsExactly("2026-09-03..2026-09-20=65.000",
                "2026-06-01..2026-09-03=140.000");
        assertThat(intervals(history(replacement))).containsExactly("2026-09-20..2026-10-05=66.000");
    }

    @Test
    void theWrapAroundRowOfTheWorkedExamplesIsAReplacementOfTheMeterByItself() throws Exception {
        // 03.09: 99 950, self-replacement 15.09 final 99 999 initial 0, 05.10: 12 -> 49 + 12 = 61 kWh
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-ЕМ-9", "2026-06-01", "99000"));
        entered(meter, "2026-09-03", "99950");

        UUID wrapped = replace(meter, "2026-09-15", "99999", "MT-ЕМ-9", "0");
        entered(wrapped, "2026-10-05", "12");

        send(chairman(), get(METERS + "/" + wrapped), null).andExpect(jsonPath("$.serial").value("MT-ЕМ-9"))
                .andExpect(jsonPath("$.initialReading").value("0.000"))
                .andExpect(jsonPath("$.replacesMeterId").value(meter.toString()));
        JsonNode range = consumption(wrapped, "2026-09-03", "2026-10-05");
        assertThat(range.get("known").asBoolean()).isTrue();
        assertThat(range.get("consumption").asString()).isEqualTo("61.000");
        assertThat(ids(range.get("meterIds"))).containsExactly(meter, wrapped);
        // the meter keeps counting from 0: a reading of 12 after the wrap is not "lower than the previous one"
        assertThat(intervals(history(wrapped))).containsExactly("2026-09-15..2026-10-05=12.000");
        // the round lists the plot once, with the wrapped counter
        JsonNode round = body(send(chairman(), get(READINGS + "/round").param("street", STREET), null)
                .andExpect(status().isOk()));
        List<UUID> roundIds = new ArrayList<>();
        round.forEach(row -> roundIds.add(UUID.fromString(row.get("id").asString())));
        assertThat(roundIds).contains(wrapped).doesNotContain(meter);
    }

    @Test
    void aNamedMeterIsReplacedWithItsName() throws Exception {
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина №1", "MT-1", "2026-06-01", "100"));

        UUID replacement = replace(pump, "2026-09-01", "150", "MT-2", "0");

        send(chairman(), get(METERS + "/" + replacement), null)
                .andExpect(jsonPath("$.kind").value("COMMON_CONSUMER"))
                .andExpect(jsonPath("$.name").value("MT Свердловина №1"))
                .andExpect(jsonPath("$.plot").doesNotExist());
    }

    // ---- the replacement links (the meter card and the plot card show the chain)

    @Test
    void theReplacedMeterLinksToItsSuccessorAndTheNewMeterToItsPredecessor() throws Exception {
        UUID plot = newPlot(STREET);
        UUID old = register(plotMeter(plot, "ELECTRICITY", "MT-ЕЛ-9/1", "2026-06-01", "8000"));

        JsonNode created = body(send(chairman(), post(METERS + "/" + old + "/replace"),
                replacement("2026-09-20", "8205", "MT-ЕЛ-9/2", "3")).andExpect(status().isCreated()));
        UUID replacement = UUID.fromString(created.get("id").asString());
        // the answer to the replacement already carries the link back, with the old meter's removal
        assertThat(created.get("replaces").get("id").asString()).isEqualTo(old.toString());
        assertThat(created.get("replaces").get("serial").asString()).isEqualTo("MT-ЕЛ-9/1");
        assertThat(created.get("replaces").get("removedOn").asString()).isEqualTo("2026-09-20");
        assertThat(created.get("replaces").get("finalReading").asString()).isEqualTo("8205.000");
        assertThat(created.has("replacedBy")).isFalse();

        // the old meter's card: "replaced by MT-ЕЛ-9/2", the day and value are its own removal
        send(chairman(), get(METERS + "/" + old), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.replacedBy.id").value(replacement.toString()))
                .andExpect(jsonPath("$.replacedBy.serial").value("MT-ЕЛ-9/2"))
                .andExpect(jsonPath("$.replacedBy.removedOn").doesNotExist())
                .andExpect(jsonPath("$.removedOn").value("2026-09-20"))
                .andExpect(jsonPath("$.finalReading").value("8205.000"))
                .andExpect(jsonPath("$.replaces").doesNotExist());
        // the new meter's card: "replaced MT-ЕЛ-9/1", with the old meter's removal
        send(chairman(), get(METERS + "/" + replacement), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.replaces.id").value(old.toString()))
                .andExpect(jsonPath("$.replaces.serial").value("MT-ЕЛ-9/1"))
                .andExpect(jsonPath("$.replaces.removedOn").value("2026-09-20"))
                .andExpect(jsonPath("$.replaces.finalReading").value("8205.000"))
                .andExpect(jsonPath("$.replacesMeterId").value(old.toString()))
                .andExpect(jsonPath("$.replacedBy").doesNotExist());
        // the plot card, the register and the round carry the same links
        send(chairman(), get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(replacement.toString()))
                .andExpect(jsonPath("$[0].replaces.id").value(old.toString()))
                .andExpect(jsonPath("$[0].replacedBy").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(old.toString()))
                .andExpect(jsonPath("$[1].replacedBy.id").value(replacement.toString()))
                .andExpect(jsonPath("$[1].replaces").doesNotExist());
        JsonNode register = body(send(chairman(), get(METERS).param("street", STREET)
                .param("includeRemoved", "true"), null).andExpect(status().isOk()));
        assertThat(byId(register, old).get("replacedBy").get("serial").asString()).isEqualTo("MT-ЕЛ-9/2");
        assertThat(byId(register, replacement).get("replaces").get("finalReading").asString())
                .isEqualTo("8205.000");
        JsonNode round = body(send(chairman(), get(READINGS + "/round").param("street", STREET), null)
                .andExpect(status().isOk()));
        assertThat(byId(round, replacement).get("replaces").get("removedOn").asString()).isEqualTo("2026-09-20");
    }

    @Test
    void aMeterInTheMiddleOfAChainLinksBothWays() throws Exception {
        UUID first = register(plotMeter(newPlot(STREET), "WATER", "MT-W-1", "2026-01-01", "10"));
        UUID second = replace(first, "2026-04-01", "40", "MT-W-2", "0");
        UUID third = replace(second, "2026-08-01", "25.5", "MT-W-3", "1");

        send(chairman(), get(METERS + "/" + second), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.replaces.id").value(first.toString()))
                .andExpect(jsonPath("$.replaces.removedOn").value("2026-04-01"))
                .andExpect(jsonPath("$.replaces.finalReading").value("40.000"))
                .andExpect(jsonPath("$.replacedBy.id").value(third.toString()))
                .andExpect(jsonPath("$.replacedBy.serial").value("MT-W-3"))
                .andExpect(jsonPath("$.removedOn").value("2026-08-01"))
                .andExpect(jsonPath("$.finalReading").value("25.500"));
        send(chairman(), get(METERS + "/" + third), null)
                .andExpect(jsonPath("$.replaces.id").value(second.toString()))
                .andExpect(jsonPath("$.replaces.removedOn").value("2026-08-01"))
                .andExpect(jsonPath("$.replaces.finalReading").value("25.500"));
    }

    @Test
    void aCounterThatWrappedAroundLinksToItselfUnderTheSameSerial() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-ЕМ-9", "2026-06-01", "99000"));
        UUID wrapped = replace(meter, "2026-09-15", "99999", "MT-ЕМ-9", "0");

        send(chairman(), get(METERS + "/" + meter), null)
                .andExpect(jsonPath("$.replacedBy.id").value(wrapped.toString()))
                .andExpect(jsonPath("$.replacedBy.serial").value("MT-ЕМ-9"));
        send(chairman(), get(METERS + "/" + wrapped), null)
                .andExpect(jsonPath("$.replaces.id").value(meter.toString()))
                .andExpect(jsonPath("$.replaces.serial").value("MT-ЕМ-9"))
                .andExpect(jsonPath("$.replaces.removedOn").value("2026-09-15"))
                .andExpect(jsonPath("$.replaces.finalReading").value("99999.000"));
    }

    @Test
    void aNamedMeterAndAMeterWaitingForApprovalCarryTheirLinks() throws Exception {
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина №2", "MT-P-1", "2026-06-01", "100"));
        UUID newPump = replace(pump, "2026-09-01", "150", "MT-P-2", "0");
        reading(newPump, "2026-09-10", "7", "PENDING");

        send(chairman(), get(METERS + "/" + pump), null)
                .andExpect(jsonPath("$.replacedBy.id").value(newPump.toString()));
        JsonNode pending = body(send(chairman(), get(READINGS).param("status", "PENDING"), null)
                .andExpect(status().isOk()));
        JsonNode item = null;
        for (JsonNode candidate : pending) {
            if (candidate.get("meter").get("id").asString().equals(newPump.toString())) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("meter").get("replaces").get("id").asString()).isEqualTo(pump.toString());
        assertThat(item.get("meter").get("replaces").get("finalReading").asString()).isEqualTo("150.000");
    }

    @Test
    void aMemberSeesTheChainOfTheirOwnPlotOnly() throws Exception {
        UUID plot = newPlot(STREET);
        UUID old = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID replacement = replace(old, "2026-09-01", "120", "MT-2", "0");
        RoleMember owner = customRoleMember();
        own(owner.id(), plot);
        String member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());

        send(member, get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].replaces.id").value(old.toString()))
                .andExpect(jsonPath("$[0].replaces.serial").value("MT-1"))
                .andExpect(jsonPath("$[0].replaces.finalReading").value("120.000"))
                .andExpect(jsonPath("$[1].replacedBy.id").value(replacement.toString()))
                .andExpect(jsonPath("$[1].replacedBy.serial").value("MT-2"));
        // another member's plot stays hidden, links included
        RoleMember stranger = customRoleMember();
        own(stranger.id(), newPlot(STREET));
        send(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), stranger.email()),
                get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isNotFound());
    }

    @Test
    void aLinkToAMeterOfAnotherPlotIsNeverShown() throws Exception {
        // not possible through the API (a replacement keeps the plot); a row written by hand must not leak the meter
        UUID foreign = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-F", "2026-01-01", "0"));
        send(chairman(), post(METERS + "/" + foreign + "/remove"),
                Map.of("removedOn", "2026-03-01", "finalReading", "50")).andExpect(status().isOk());
        UUID plot = newPlot(STREET);
        UUID meter = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value, replaces_meter_id) values (?, ?, 'PLOT', 'ELECTRICITY', 'MT-OWN', ?, "
                + "date '2026-03-01', 0, ?)", meter, DEMO_TENANT, plot, foreign);
        RoleMember owner = customRoleMember();
        own(owner.id(), plot);

        send(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email()),
                get("/api/v1/plots/" + plot + "/meters"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(meter.toString()))
                .andExpect(jsonPath("$[0].replaces").doesNotExist());
        send(chairman(), get(METERS + "/" + foreign), null).andExpect(jsonPath("$.replacedBy").doesNotExist());
    }

    // ---- the tree

    @Test
    void theNewMeterTakesTheOldOnesPlaceInTheTree() throws Exception {
        UUID substation = register(namedMeter("SUBSTATION", "MT Підстанція", "MT-S", "2026-01-01", "0"));
        UUID quarter = register(namedMeter("QUARTER", "MT Квартал А", "MT-Q", "2026-01-01", "0"));
        UUID plotMeter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-P", "2026-01-01", "0"));
        UUID lighting = register(namedMeter("COMMON_CONSUMER", "MT Освітлення", "MT-L", "2026-01-01", "0"));
        parent(quarter, substation, "2026-01-01", null);
        parent(plotMeter, quarter, "2026-01-01", null);
        // the lighting moves under the quarter after the replacement day: the whole period follows the quarter
        parent(lighting, substation, "2026-01-01", "2026-08-31");
        parent(lighting, quarter, "2026-09-01", null);

        // a plot meter: its own parent period continues for the new meter
        UUID newPlotMeter = replace(plotMeter, "2026-07-01", "100", "MT-P2", "0");
        assertThat(periods(plotMeter)).containsExactly(quarter + " 2026-01-01..2026-07-01");
        assertThat(periods(newPlotMeter)).containsExactly(quarter + " 2026-07-01..");

        // a quarter meter: its parent continues, and the meters under it move under the new one from that day
        UUID newQuarter = replace(quarter, "2026-08-15", "0", "MT-Q2", "0");
        assertThat(periods(quarter)).containsExactly(substation + " 2026-01-01..2026-08-15");
        assertThat(periods(newQuarter)).containsExactly(substation + " 2026-08-15..");
        assertThat(periods(newPlotMeter)).containsExactly(quarter + " 2026-07-01..2026-08-14",
                newQuarter + " 2026-08-15..");
        assertThat(periods(lighting)).containsExactly(substation + " 2026-01-01..2026-08-31",
                newQuarter + " 2026-09-01..");
    }

    // ---- refused

    @Test
    void aRemovedMeterIsNotReplaced() throws Exception {
        UUID old = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID replacement = replace(old, "2026-07-01", "10", "MT-2", "0");

        send(chairman(), post(METERS + "/" + old + "/replace"), replacement("2026-08-01", "20", "MT-3", "0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METER_REMOVED"));
        assertThat(successors(replacement)).isZero();
    }

    @Test
    void aMeterWithAReadingWaitingForApprovalIsNotReplaced() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        reading(meter, "2026-09-01", "8100", "PENDING");

        send(chairman(), post(METERS + "/" + meter + "/replace"), replacement("2026-09-20", "8205", "MT-2", "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_HAS_PENDING_READINGS"));
        expectUnchanged(meter);
    }

    @Test
    void theDayAndTheFinalReadingFollowTheRulesOfARemoval() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "8000"));
        entered(meter, "2026-09-03", "8140");

        expectConflict(meter, replacement(kyivToday().plusDays(1).toString(), "9000", "MT-2", "0"),
                "READING_DATE_IN_FUTURE", "replacedOn");
        expectConflict(meter, replacement("2026-05-31", "9000", "MT-2", "0"), "READING_DATE_OUTSIDE_METER",
                "replacedOn");
        expectConflict(meter, replacement("2026-09-02", "9000", "MT-2", "0"), "READING_OUT_OF_ORDER", "replacedOn");
        expectConflict(meter, replacement("2026-09-20", "8139.999", "MT-2", "0"), "READING_OUT_OF_ORDER",
                "finalReading");
        // on the day of the last counted reading the final reading may equal it
        replace(meter, "2026-09-03", "8140", "MT-2", "0");
    }

    @Test
    void theNewSerialAndBothReadingsAreValidatedAtTheBoundary() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        expectFieldProblem(meter, replacement("2026-07-01", "10", "   ", "0"), "serial", "REQUIRED");
        expectFieldProblem(meter, replacement("2026-07-01", "10", "MT-" + "x".repeat(50), "0"), "serial",
                "TOO_LONG");
        expectFieldProblem(meter, replacement("2026-07-01", "10.1234", "MT-2", "0"), "finalReading",
                "METER_VALUE_INVALID");
        expectFieldProblem(meter, replacement("2026-07-01", "10", "MT-2", "-1"), "initialReading",
                "METER_VALUE_INVALID");
        expectUnchanged(meter);
    }

    // ---- the consumption endpoint

    @Test
    void aRangeEndingBetweenTwoReadingsIsCalculatedAndMarked() throws Exception {
        // 01.09: 1 000, 01.10: 1 300; range 01.09-16.09 -> the value on 16.09 is 1 150 (calculated); 150 kWh
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "900"));
        entered(meter, "2026-09-01", "1000");
        entered(meter, "2026-10-01", "1300");

        JsonNode range = consumption(meter, "2026-09-01", "2026-09-16");
        assertThat(range.get("known").asBoolean()).isTrue();
        assertThat(range.get("consumption").asString()).isEqualTo("150.000");
        assertThat(range.get("calculated").asBoolean()).isTrue();
        assertThat(ids(range.get("meterIds"))).containsExactly(meter);
    }

    @Test
    void aRangeEndingAfterTheLastReadingIsUnknown() throws Exception {
        UUID old = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID replacement = replace(old, "2026-09-01", "100", "MT-2", "0");
        entered(replacement, "2026-10-05", "50");

        JsonNode range = consumption(replacement, "2026-08-01", kyivToday().plusDays(10).toString());
        assertThat(range.get("known").asBoolean()).isFalse();
        assertThat(range.has("consumption")).isFalse();
        assertThat(range.get("lastReadingOn").asString()).isEqualTo("2026-10-05");
    }

    @Test
    void aRangeMustNotEndBeforeItStarts() throws Exception {
        UUID meter = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        send(chairman(), get(METERS + "/" + meter + "/consumption").param("from", "2026-09-02")
                .param("to", "2026-09-01"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void theConsumptionIsForTheBoard() throws Exception {
        UUID plot = newPlot(STREET);
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        RoleMember owner = customRoleMember();
        own(owner.id(), plot);
        String member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());

        expectConsumption(demo("treasurer@demo.local"), meter).andExpect(status().isOk());
        expectConsumption(customRoleToken("METERS_VIEW", "READINGS_ENTER"), meter).andExpect(status().isOk());
        expectConsumption(member, meter).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        expectConsumption(operator(), meter).andExpect(status().isForbidden());
        expectConsumption(otherChairman(), meter).andExpect(status().isNotFound());
        expectConsumption(chairman(), UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get(METERS + "/" + meter + "/consumption").param("from", "2026-09-01").param("to", "2026-09-02"))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers

    static Map<String, Object> replacement(String replacedOn, String finalReading, String serial,
            String initialReading) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replacedOn", replacedOn);
        body.put("finalReading", finalReading);
        body.put("serial", serial);
        body.put("initialReading", initialReading);
        return body;
    }

    /** Replaces the meter as the demo chairman and returns the id of the new meter. */
    private UUID replace(UUID meter, String replacedOn, String finalReading, String serial, String initialReading)
            throws Exception {
        return UUID.fromString(body(send(chairman(), post(METERS + "/" + meter + "/replace"),
                replacement(replacedOn, finalReading, serial, initialReading)).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private JsonNode consumption(UUID meter, String from, String to) throws Exception {
        return body(send(chairman(), get(METERS + "/" + meter + "/consumption").param("from", from).param("to", to),
                null).andExpect(status().isOk()));
    }

    private ResultActions expectConsumption(String bearer, UUID meter) throws Exception {
        return send(bearer, get(METERS + "/" + meter + "/consumption").param("from", "2026-09-01")
                .param("to", "2026-09-02"), null);
    }

    /** A parent period written as the database owner (the tree endpoints arrive with a later slice). */
    private void parent(UUID meter, UUID parent, String validFrom, String validTo) {
        ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from, valid_to) "
                + "values (?, ?, ?, ?::date, ?::date)", DEMO_TENANT, meter, parent, validFrom, validTo);
    }

    /** The parent periods of a meter as {@code parent from..to}, oldest first. */
    private List<String> periods(UUID meter) {
        return ownerJdbc().queryForList("select parent_meter_id || ' ' || valid_from || '..' "
                + "|| coalesce(valid_to::text, '') from meter_parent where meter_id = ? order by valid_from",
                String.class, meter);
    }

    private int successors(UUID meter) {
        return ownerJdbc().queryForObject("select count(*) from meter where replaces_meter_id = ?", Integer.class,
                meter);
    }

    private void expectConflict(UUID meter, Map<String, Object> body, String code, String field) throws Exception {
        send(chairman(), post(METERS + "/" + meter + "/replace"), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
        expectUnchanged(meter);
    }

    private void expectFieldProblem(UUID meter, Map<String, Object> body, String field, String code)
            throws Exception {
        send(chairman(), post(METERS + "/" + meter + "/replace"), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code").value(code));
    }

    private void expectUnchanged(UUID meter) throws Exception {
        send(chairman(), get(METERS + "/" + meter), null).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.removedOn").doesNotExist());
        assertThat(successors(meter)).isZero();
    }

    /** The element of a list of meters with that id. */
    private static JsonNode byId(JsonNode meters, UUID id) {
        for (JsonNode meter : meters) {
            if (meter.get("id").asString().equals(id.toString())) {
                return meter;
            }
        }
        throw new AssertionError("No meter " + id + " in " + meters);
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(id -> ids.add(UUID.fromString(id.asString())));
        return ids;
    }

    /** The intervals of a history as {@code from..to=consumption}, newest first. */
    private static List<String> intervals(JsonNode history) {
        List<String> result = new ArrayList<>();
        history.get("intervals").forEach(interval -> result.add(interval.get("from").asString() + ".."
                + interval.get("to").asString() + "=" + interval.get("consumption").asString()));
        return result;
    }
}
