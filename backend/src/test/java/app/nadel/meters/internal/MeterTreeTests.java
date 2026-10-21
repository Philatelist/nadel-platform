package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The electricity meter tree through the API (spec 010, section 2.2 and the tree rows of 2.5): the rules of who may be
 * under whom, moves with an effective date that keep the history, a past range that shows the tree as it was, the
 * consumption of a moved plot split on the day of the move, calculated and unknown values, the guards on deleting and
 * removing a meter with meters under it, the audit entry and who may read and change the tree.
 * <p>
 * The dates are in 2025 so that the spec's October examples (01.10-31.10) lie in the past whatever today is.
 */
class MeterTreeTests extends MeterTestSupport {

    private static final String TREE = "/api/v1/meter-tree";
    private static final String STREET = "MT вул. Яблунева";
    private static final String INSTALLED = "2025-01-01";

    // ---- building the tree

    @Test
    void theChairmanBuildsTheTreeAndItShowsThreeLevels() throws Exception {
        UUID substation = substation("MT Підстанція");
        UUID quarter = quarter("MT Квартал А");
        UUID plotMeter = plotMeter();
        UUID lighting = register(namedMeter("COMMON_CONSUMER", "MT Освітлення", "MT-L", INSTALLED, "0"));
        UUID outside = plotMeter();

        JsonNode placement = body(move(chairman(), quarter, substation, INSTALLED).andExpect(status().isOk()));
        assertThat(placement.get("meterId").asString()).isEqualTo(quarter.toString());
        assertThat(placement.get("periods")).hasSize(1);
        assertThat(placement.get("periods").get(0).get("parentMeterId").asString()).isEqualTo(substation.toString());
        assertThat(placement.get("periods").get(0).get("validFrom").asString()).isEqualTo(INSTALLED);
        assertThat(placement.get("periods").get(0).has("validTo")).isFalse();
        move(chairman(), plotMeter, quarter, "2025-02-01").andExpect(status().isOk());
        move(chairman(), lighting, quarter, INSTALLED).andExpect(status().isOk());

        JsonNode tree = tree("2025-03-01", "2025-03-31");
        assertThat(tree.get("from").asString()).isEqualTo("2025-03-01");
        assertThat(tree.get("to").asString()).isEqualTo("2025-03-31");
        assertThat(parentsOf(tree, substation)).containsExactly((UUID) null);
        assertThat(parentsOf(tree, quarter)).containsExactly(substation);
        assertThat(parentsOf(tree, plotMeter)).containsExactly(quarter);
        assertThat(parentsOf(tree, lighting)).containsExactly(quarter);
        // a plot meter that nobody put in the tree is among the roots
        assertThat(parentsOf(tree, outside)).containsExactly((UUID) null);
        // under the quarter: the common consumer first, then the plot meters
        JsonNode quarterNode = places(tree, quarter).getFirst().node();
        assertThat(ids(quarterNode.get("children"), "meter")).containsExactly(lighting, plotMeter);
        JsonNode plotNode = places(tree, plotMeter).getFirst().node();
        assertThat(plotNode.get("meter").get("kind").asString()).isEqualTo("PLOT");
        assertThat(plotNode.get("meter").get("plot").get("number").asString()).startsWith("MT");
        assertThat(plotNode.get("children")).isEmpty();

        // before the plot meter was put under the quarter, it was a root
        JsonNode january = tree("2025-01-10", "2025-01-20");
        assertThat(parentsOf(january, plotMeter)).containsExactly((UUID) null);
    }

    @Test
    void aQuarterMeterIsNotUnderAnotherQuarterMeter() throws Exception {
        UUID first = quarter("MT Квартал А");
        UUID second = quarter("MT Квартал Б");

        expectTreeInvalid(move(chairman(), second, first, INSTALLED), "parentMeterId", "TREE_QUARTER_UNDER_QUARTER");
        assertThat(periods(second)).isEmpty();
    }

    @Test
    void theKindsDecideWhoIsUnderWhomSoThereIsNoCycle() throws Exception {
        UUID substation = substation("MT Підстанція 1");
        UUID otherSubstation = substation("MT Підстанція 2");
        UUID quarter = quarter("MT Квартал А");
        UUID plotMeter = plotMeter();
        UUID pump = register(namedMeter("COMMON_CONSUMER", "MT Свердловина", "MT-W", INSTALLED, "0"));
        move(chairman(), quarter, substation, INSTALLED).andExpect(status().isOk());

        expectTreeInvalid(move(chairman(), substation, otherSubstation, INSTALLED), "parentMeterId",
                "TREE_PARENT_KIND");
        // the substation under its own quarter would be a cycle
        expectTreeInvalid(move(chairman(), substation, quarter, INSTALLED), "parentMeterId", "TREE_PARENT_KIND");
        expectTreeInvalid(move(chairman(), quarter, plotMeter, INSTALLED), "parentMeterId", "TREE_PARENT_KIND");
        expectTreeInvalid(move(chairman(), plotMeter, pump, INSTALLED), "parentMeterId", "TREE_PARENT_KIND");
        expectTreeInvalid(move(chairman(), pump, plotMeter, INSTALLED), "parentMeterId", "TREE_PARENT_KIND");
        expectTreeInvalid(move(chairman(), quarter, quarter, INSTALLED), "parentMeterId", "TREE_PARENT_KIND");
        assertThat(periods(substation)).isEmpty();
        assertThat(periods(plotMeter)).isEmpty();
        assertThat(periods(pump)).isEmpty();
        assertThat(periods(quarter)).containsExactly(substation + " 2025-01-01..");
    }

    @Test
    void waterMetersHaveNoTree() throws Exception {
        UUID quarter = quarter("MT Квартал А");
        UUID water = register(plotMeter(newPlot(STREET), "WATER", "MT-V", INSTALLED, "0"));

        expectTreeInvalid(move(chairman(), water, quarter, INSTALLED), "parentMeterId", "TREE_WATER");
        expectTreeInvalid(move(chairman(), water, null, INSTALLED), "parentMeterId", "TREE_WATER");
        assertThat(places(tree("2025-03-01", "2025-03-31"), water)).isEmpty();
    }

    @Test
    void theDatesOfAMoveAreChecked() throws Exception {
        UUID substation = substation("MT Підстанція");
        UUID late = register(namedMeter("QUARTER", "MT Квартал пізній", "MT-Q2", "2025-06-01", "0"));
        UUID plotMeter = plotMeter();

        // the parent must be installed by the effective date
        expectTreeInvalid(move(chairman(), plotMeter, late, "2025-05-31"), "parentMeterId", "TREE_PARENT_INACTIVE");
        move(chairman(), plotMeter, late, "2025-06-01").andExpect(status().isOk());
        // the meter must be installed by then, and the date not in the future
        expectTreeInvalid(move(chairman(), late, substation, "2024-12-31"), "effectiveFrom",
                "READING_DATE_OUTSIDE_METER");
        move(chairman(), plotMeter, substation, kyivToday().plusDays(1).toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_DATE_IN_FUTURE"))
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"));
        // the history before the latest change is not rewritten
        expectTreeInvalid(move(chairman(), plotMeter, substation, "2025-05-31"), "effectiveFrom",
                "TREE_DATE_BEFORE_LATEST");
        assertThat(periods(plotMeter)).containsExactly(late + " 2025-06-01..");
    }

    @Test
    void aRemovedOrUnknownMeterIsRefused() throws Exception {
        UUID substation = substation("MT Підстанція");
        UUID removedQuarter = quarter("MT Квартал знятий");
        send(chairman(), post(METERS + "/" + removedQuarter + "/remove"),
                Map.of("removedOn", "2025-05-01", "finalReading", "0")).andExpect(status().isOk());
        UUID plotMeter = plotMeter();

        expectTreeInvalid(move(chairman(), plotMeter, removedQuarter, "2025-02-01"), "parentMeterId",
                "TREE_PARENT_INACTIVE");
        move(chairman(), removedQuarter, substation, "2025-02-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_REMOVED"));
        move(chairman(), plotMeter, UUID.randomUUID(), "2025-02-01").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("parentMeterId"));
        move(chairman(), UUID.randomUUID(), substation, "2025-02-01").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), put(METERS + "/" + plotMeter + "/parent"), Map.of("parentMeterId", substation.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(periods(plotMeter)).isEmpty();
    }

    // ---- moves keep the history

    @Test
    void aMoveWithADateEndsTheOldPeriodTheDayBefore() throws Exception {
        UUID substation = substation("MT Підстанція");
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID quarterC = quarter("MT Квартал В");
        UUID plotMeter = plotMeter();
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());

        JsonNode placement = body(move(chairman(), plotMeter, quarterB, "2025-10-15").andExpect(status().isOk()));
        assertThat(placement.get("periods")).hasSize(2);
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..2025-10-14",
                quarterB + " 2025-10-15..");

        // a move on the date of the latest change corrects it
        move(chairman(), plotMeter, quarterC, "2025-10-15").andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..2025-10-14",
                quarterC + " 2025-10-15..");
        // and back to the parent before: the two periods are one again
        move(chairman(), plotMeter, quarterA, "2025-10-15").andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..");

        // out of the tree from a date, and back under the same parent the day it left
        move(chairman(), plotMeter, null, "2025-11-01").andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..2025-10-31");
        expectTreeInvalid(move(chairman(), plotMeter, substation, "2025-10-31"), "effectiveFrom",
                "TREE_DATE_BEFORE_LATEST");
        move(chairman(), plotMeter, quarterA, "2025-11-01").andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..");

        // the parent it already has: nothing changes
        move(chairman(), plotMeter, quarterA, "2025-12-01").andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..");
        // the quarters themselves may be put under the substation; the plot meter stays under its quarter
        move(chairman(), quarterA, substation, INSTALLED).andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarterA + " 2025-01-01..");
    }

    @Test
    void aPastRangeShowsTheTreeAsItWasThen() throws Exception {
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID plotMeter = plotMeter();
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        move(chairman(), plotMeter, quarterB, "2025-10-15").andExpect(status().isOk());

        assertThat(parentsOf(tree("2025-09-01", "2025-09-30"), plotMeter)).containsExactly(quarterA);
        assertThat(parentsOf(tree("2025-11-01", "2025-11-30"), plotMeter)).containsExactly(quarterB);
        assertThat(parentsOf(tree("2025-10-01", "2025-10-31"), plotMeter)).containsExactly(quarterA, quarterB);
    }

    @Test
    void theConsumptionOfAMovedPlotIsSplitOnTheDayOfTheMove() throws Exception {
        // the "Plot moved to another quarter" row: moved A -> B on 15.10; range 01.10-31.10
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID plotMeter = plotMeter();
        entered(plotMeter, "2025-10-01", "1000");
        entered(plotMeter, "2025-10-31", "1300");
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        move(chairman(), plotMeter, quarterB, "2025-10-15").andExpect(status().isOk());

        JsonNode tree = tree("2025-10-01", "2025-10-31");
        JsonNode underA = place(tree, plotMeter, quarterA);
        JsonNode underB = place(tree, plotMeter, quarterB);

        assertThat(underA.get("slices")).hasSize(1);
        JsonNode sliceA = underA.get("slices").get(0);
        assertThat(sliceA.get("from").asString()).isEqualTo("2025-10-01");
        assertThat(sliceA.get("to").asString()).isEqualTo("2025-10-15");
        assertThat(value(sliceA.get("start"))).isEqualTo("REAL 1000.000");
        // 1 000 + 300 x 14/30 = 1 140, calculated
        assertThat(value(sliceA.get("end"))).isEqualTo("CALCULATED 1140.000");
        assertThat(consumption(sliceA.get("consumption"))).isEqualTo("140.000 calculated");
        assertThat(consumption(underA.get("total"))).isEqualTo("140.000 calculated");

        JsonNode sliceB = underB.get("slices").get(0);
        assertThat(sliceB.get("from").asString()).isEqualTo("2025-10-15");
        assertThat(sliceB.get("to").asString()).isEqualTo("2025-10-31");
        assertThat(value(sliceB.get("start"))).isEqualTo("CALCULATED 1140.000");
        assertThat(value(sliceB.get("end"))).isEqualTo("REAL 1300.000");
        assertThat(consumption(underB.get("total"))).isEqualTo("160.000 calculated");
        // the two parts add up to the plot's consumption over the whole range
        JsonNode whole = body(send(chairman(), get(METERS + "/" + plotMeter + "/consumption")
                .param("from", "2025-10-01").param("to", "2025-10-31"), null).andExpect(status().isOk()));
        assertThat(whole.get("consumption").asString()).isEqualTo("300.000");
    }

    @Test
    void aValueBetweenTwoReadingsIsCalculatedAndMarked() throws Exception {
        // 01.10: 1 000, 31.10: 1 300; range 01.10-16.10 -> the value on 16.10 is 1 150 (calculated); 150 kWh
        UUID plotMeter = plotMeter();
        entered(plotMeter, "2025-10-01", "1000");
        entered(plotMeter, "2025-10-31", "1300");

        JsonNode node = place(tree("2025-10-01", "2025-10-16"), plotMeter, null);
        JsonNode slice = node.get("slices").get(0);
        assertThat(value(slice.get("start"))).isEqualTo("REAL 1000.000");
        assertThat(value(slice.get("end"))).isEqualTo("CALCULATED 1150.000");
        assertThat(slice.get("end").get("meterId").asString()).isEqualTo(plotMeter.toString());
        assertThat(consumption(slice.get("consumption"))).isEqualTo("150.000 calculated");
        // read off two readings, nothing is calculated
        JsonNode exact = place(tree("2025-10-01", "2025-10-31"), plotMeter, null);
        assertThat(consumption(exact.get("total"))).isEqualTo("300.000 exact");
    }

    @Test
    void aRangeEndingAfterTheLastReadingIsUnknown() throws Exception {
        UUID plotMeter = plotMeter();
        entered(plotMeter, "2025-10-05", "100");

        JsonNode node = place(tree("2025-10-01", kyivToday().toString()), plotMeter, null);
        JsonNode slice = node.get("slices").get(0);
        assertThat(value(slice.get("start"))).isEqualTo("CALCULATED 98.556");
        assertThat(value(slice.get("end"))).isEqualTo("UNKNOWN 2025-10-05");
        assertThat(slice.get("consumption").get("known").asBoolean()).isFalse();
        assertThat(slice.get("consumption").has("consumption")).isFalse();
        assertThat(slice.get("consumption").get("lastReadingOn").asString()).isEqualTo("2025-10-05");
        assertThat(node.get("total").get("known").asBoolean()).isFalse();
        assertThat(node.get("total").get("lastReadingOn").asString()).isEqualTo("2025-10-05");
    }

    @Test
    void aReplacedMeterAndItsSuccessorAreOneNode() throws Exception {
        // the "Replacement" row: old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 with 3, 05.10: 69
        UUID quarter = quarter("MT Квартал А");
        UUID old = register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-1", INSTALLED, "8000"));
        move(chairman(), old, quarter, INSTALLED).andExpect(status().isOk());
        entered(old, "2025-09-03", "8140");
        UUID replacement = UUID.fromString(body(send(chairman(), post(METERS + "/" + old + "/replace"),
                Map.of("replacedOn", "2025-09-20", "finalReading", "8205", "serial", "MT-2", "initialReading", "3"))
                .andExpect(status().isCreated())).get("id").asString());
        entered(replacement, "2025-10-05", "69");

        JsonNode tree = tree("2025-09-03", "2025-10-05");
        List<Place> places = places(tree, old);
        assertThat(places).hasSize(1);
        JsonNode node = places.getFirst().node();
        assertThat(places.getFirst().parent()).isEqualTo(quarter);
        assertThat(node.get("meter").get("id").asString()).isEqualTo(replacement.toString());
        assertThat(ids(node.get("meterIds"))).containsExactly(old, replacement);
        assertThat(node.get("slices")).hasSize(1);
        assertThat(consumption(node.get("total"))).isEqualTo("131.000 exact");
        assertThat(value(node.get("slices").get(0).get("start"))).isEqualTo("REAL 8140.000");
        assertThat(node.get("slices").get(0).get("start").get("meterId").asString()).isEqualTo(old.toString());
        assertThat(value(node.get("slices").get(0).get("end"))).isEqualTo("REAL 69.000");
        // a range that ends on the replacement day reads the old meter's final reading
        JsonNode upToReplacement = places(tree("2025-09-03", "2025-09-20"), old).getFirst().node();
        assertThat(upToReplacement.get("meter").get("id").asString()).isEqualTo(replacement.toString());
        assertThat(value(upToReplacement.get("slices").get(0).get("end"))).isEqualTo("REAL 8205.000");
        assertThat(upToReplacement.get("slices").get(0).get("end").get("meterId").asString())
                .isEqualTo(old.toString());
    }

    @Test
    void aReplacedQuarterStaysOneNodeWithItsPlots() throws Exception {
        UUID substation = substation("MT Підстанція");
        UUID quarter = quarter("MT Квартал А");
        UUID plotMeter = plotMeter();
        move(chairman(), quarter, substation, INSTALLED).andExpect(status().isOk());
        move(chairman(), plotMeter, quarter, INSTALLED).andExpect(status().isOk());
        UUID newQuarter = UUID.fromString(body(send(chairman(), post(METERS + "/" + quarter + "/replace"),
                Map.of("replacedOn", "2025-06-15", "finalReading", "0", "serial", "MT-Q2", "initialReading", "0"))
                .andExpect(status().isCreated())).get("id").asString());

        JsonNode tree = tree("2025-06-01", "2025-06-30");
        assertThat(parentsOf(tree, quarter)).containsExactly(substation);
        assertThat(parentsOf(tree, plotMeter)).containsExactly(newQuarter);
        JsonNode plotNode = place(tree, plotMeter, newQuarter);
        assertThat(plotNode.get("slices")).hasSize(1);
        assertThat(plotNode.get("slices").get(0).get("from").asString()).isEqualTo("2025-06-01");
        assertThat(plotNode.get("slices").get(0).get("to").asString()).isEqualTo("2025-06-30");
    }

    @Test
    void theRangeMustNotEndBeforeItStarts() throws Exception {
        send(chairman(), get(TREE).param("from", "2025-10-02").param("to", "2025-10-01"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), get(TREE).param("to", "2025-10-01"), null).andExpect(status().isBadRequest());
    }

    // ---- meters with meters under them

    @Test
    void aMeterThatHasOrHadMetersUnderItIsNotDeleted() throws Exception {
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID plotMeter = plotMeter();
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());

        expectHasChildren(send(chairman(), delete(METERS + "/" + quarterA), null));
        // moved away, its history still refers to the quarter
        move(chairman(), plotMeter, quarterB, "2025-03-01").andExpect(status().isOk());
        expectHasChildren(send(chairman(), delete(METERS + "/" + quarterA), null));
        assertThat(meterCount(quarterA)).isOne();

        // a meter that is only somebody's child is deleted with its periods
        send(chairman(), delete(METERS + "/" + plotMeter), null).andExpect(status().isNoContent());
        assertThat(meterCount(plotMeter)).isZero();
        assertThat(periods(plotMeter)).isEmpty();
    }

    @Test
    void aMeterIsNotRemovedWhileMetersStillWorkUnderIt() throws Exception {
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID plotMeter = plotMeter();
        UUID removedPlotMeter = plotMeter();
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        move(chairman(), removedPlotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        // a child removed earlier does not hold the quarter
        send(chairman(), post(METERS + "/" + removedPlotMeter + "/remove"),
                Map.of("removedOn", "2025-08-01", "finalReading", "0")).andExpect(status().isOk());
        entered(plotMeter, "2025-10-01", "1000");
        entered(plotMeter, "2025-10-31", "1300");

        expectHasChildren(send(chairman(), post(METERS + "/" + quarterA + "/remove"),
                Map.of("removedOn", "2025-10-14", "finalReading", "0")));
        send(chairman(), get(METERS + "/" + quarterA), null).andExpect(jsonPath("$.active").value(true));

        // the plot meter is under another quarter from 15.10: under the quarter up to 14.10, so the quarter, which
        // works in the tree only up to the day before its removal, still cannot go on 14.10, but can on 15.10
        move(chairman(), plotMeter, quarterB, "2025-10-15").andExpect(status().isOk());
        expectHasChildren(send(chairman(), post(METERS + "/" + quarterA + "/remove"),
                Map.of("removedOn", "2025-10-14", "finalReading", "0")));
        send(chairman(), get(METERS + "/" + quarterA), null).andExpect(jsonPath("$.active").value(true));
        send(chairman(), post(METERS + "/" + quarterA + "/remove"),
                Map.of("removedOn", "2025-10-15", "finalReading", "0")).andExpect(status().isOk());

        // no day of the moved plot is lost: its slices under both quarters add up to its consumption over the range
        JsonNode tree = tree("2025-10-01", "2025-10-31");
        BigDecimal sliced = BigDecimal.ZERO;
        for (UUID quarter : List.of(quarterA, quarterB)) {
            for (JsonNode slice : place(tree, plotMeter, quarter).get("slices")) {
                assertThat(slice.get("consumption").get("known").asBoolean()).isTrue();
                sliced = sliced.add(new BigDecimal(slice.get("consumption").get("consumption").asString()));
            }
        }
        JsonNode whole = body(send(chairman(), get(METERS + "/" + plotMeter + "/consumption")
                .param("from", "2025-10-01").param("to", "2025-10-31"), null).andExpect(status().isOk()));
        assertThat(whole.get("consumption").asString()).isEqualTo("300.000");
        assertThat(sliced).isEqualByComparingTo(new BigDecimal(whole.get("consumption").asString()));
    }

    // ---- audit

    @Test
    void aMoveIsAuditedWithTheOldAndNewParentAndTheDate() throws Exception {
        UUID quarterA = quarter("MT Квартал А");
        UUID quarterB = quarter("MT Квартал Б");
        UUID plotMeter = plotMeter();
        move(chairman(), plotMeter, quarterA, INSTALLED).andExpect(status().isOk());
        move(chairman(), plotMeter, quarterB, "2025-10-15").andExpect(status().isOk());
        // nothing changes, nothing is written
        move(chairman(), plotMeter, quarterB, "2025-10-20").andExpect(status().isOk());

        List<Map<String, Object>> entries = awaitEntries(plotMeter, "METER_PARENT_CHANGED", 2);
        assertThat(entries.getFirst().get("entity_type")).isEqualTo("METER");
        assertThat(entries.getFirst().get("entity_label")).isEqualTo("MT-P");
        assertThat(entries.getFirst().get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(entries.getFirst().get("actor_member_id")).isNotNull();
        assertThat(changes(entries.get(0))).containsExactly(new Change("parent", null, "MT Квартал А"),
                new Change("effectiveFrom", null, "2025-01-01"));
        assertThat(changes(entries.get(1))).containsExactly(new Change("parent", "MT Квартал А", "MT Квартал Б"),
                new Change("effectiveFrom", null, "2025-10-15"));
    }

    @Test
    void aRefusedMoveWritesNothing() throws Exception {
        UUID first = quarter("MT Квартал А");
        UUID second = quarter("MT Квартал Б");
        move(chairman(), second, first, INSTALLED).andExpect(status().isConflict());
        UUID plotMeter = plotMeter();
        move(chairman(), plotMeter, first, INSTALLED).andExpect(status().isOk());

        awaitEntries(plotMeter, "METER_PARENT_CHANGED", 1);
        assertThat(entries(second, "METER_PARENT_CHANGED")).isEmpty();
    }

    // ---- who may

    @Test
    void theTreeIsReadWithTheViewPermission() throws Exception {
        UUID plot = newPlot(STREET);
        UUID plotMeter = register(plotMeter(plot, "ELECTRICITY", "MT-P", INSTALLED, "0"));
        RoleMember owner = customRoleMember();
        own(owner.id(), plot);
        String member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());

        assertThat(places(body(readTree(demo("treasurer@demo.local")).andExpect(status().isOk())), plotMeter))
                .hasSize(1);
        readTree(customRoleToken("METERS_VIEW")).andExpect(status().isOk());
        readTree(member).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        readTree(customRoleToken("METERS_MANAGE")).andExpect(status().isForbidden());
        readTree(operator()).andExpect(status().isForbidden());
        // another cooperative sees its own tree, never this one's meters
        assertThat(places(body(readTree(otherChairman()).andExpect(status().isOk())), plotMeter)).isEmpty();
        mvc.perform(get(TREE).param("from", "2025-03-01").param("to", "2025-03-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMoveNeedsTheViewAndManagePermissions() throws Exception {
        UUID quarter = quarter("MT Квартал А");
        UUID plotMeter = plotMeter();

        move(demo("treasurer@demo.local"), plotMeter, quarter, INSTALLED).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        move(customRoleToken("METERS_VIEW"), plotMeter, quarter, INSTALLED).andExpect(status().isForbidden());
        move(customRoleToken("METERS_MANAGE"), plotMeter, quarter, INSTALLED).andExpect(status().isForbidden());
        move(customRoleToken("METERS_VIEW", "READINGS_ENTER", "READINGS_APPROVE"), plotMeter, quarter, INSTALLED)
                .andExpect(status().isForbidden());
        move(operator(), plotMeter, quarter, INSTALLED).andExpect(status().isForbidden());
        // another cooperative does not see the meters at all
        move(otherChairman(), plotMeter, quarter, INSTALLED).andExpect(status().isNotFound());
        assertThat(periods(plotMeter)).isEmpty();

        move(customRoleToken("METERS_VIEW", "METERS_MANAGE"), plotMeter, quarter, INSTALLED)
                .andExpect(status().isOk());
        assertThat(periods(plotMeter)).containsExactly(quarter + " 2025-01-01..");
        mvc.perform(put(METERS + "/" + plotMeter + "/parent")).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    private UUID substation(String name) throws Exception {
        return register(namedMeter("SUBSTATION", name, "MT-S-" + UUID.randomUUID(), INSTALLED, "0"));
    }

    private UUID quarter(String name) throws Exception {
        return register(namedMeter("QUARTER", name, "MT-Q-" + UUID.randomUUID(), INSTALLED, "0"));
    }

    /** A plot meter on a new plot, installed with 0. */
    private UUID plotMeter() throws Exception {
        return register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-P", INSTALLED, "0"));
    }

    private ResultActions move(String bearer, UUID meter, @Nullable UUID parent, String effectiveFrom)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (parent != null) {
            body.put("parentMeterId", parent.toString());
        }
        body.put("effectiveFrom", effectiveFrom);
        return send(bearer, put(METERS + "/" + meter + "/parent"), body);
    }

    private ResultActions readTree(String bearer) throws Exception {
        return send(bearer, get(TREE).param("from", "2025-03-01").param("to", "2025-03-31"), null);
    }

    private JsonNode tree(String from, String to) throws Exception {
        return body(send(chairman(), get(TREE).param("from", from).param("to", to), null)
                .andExpect(status().isOk()));
    }

    private static void expectTreeInvalid(ResultActions actions, String field, String code) throws Exception {
        actions.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_TREE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }

    private static void expectHasChildren(ResultActions actions) throws Exception {
        actions.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("METER_HAS_CHILDREN"));
    }

    /** A place of a node in the tree: the shown meter of its parent (null among the roots) and the node. */
    private record Place(@Nullable UUID parent, JsonNode node) {
    }

    /** Every place of the node whose chain holds the meter, in the order of the tree. */
    private static List<Place> places(JsonNode tree, UUID meter) {
        List<Place> found = new ArrayList<>();
        collect(tree.get("roots"), null, meter, found);
        return found;
    }

    private static void collect(JsonNode nodes, @Nullable UUID parent, UUID meter, List<Place> found) {
        for (JsonNode node : nodes) {
            if (ids(node.get("meterIds")).contains(meter)) {
                found.add(new Place(parent, node));
            }
            collect(node.get("children"), UUID.fromString(node.get("meter").get("id").asString()), meter, found);
        }
    }

    private static List<UUID> parentsOf(JsonNode tree, UUID meter) {
        return places(tree, meter).stream().map(Place::parent).toList();
    }

    /** The node of the meter under the parent (null: among the roots); fails when it is not there exactly once. */
    private static JsonNode place(JsonNode tree, UUID meter, @Nullable UUID parent) {
        List<JsonNode> nodes = places(tree, meter).stream()
                .filter(place -> parent == null ? place.parent() == null : parent.equals(place.parent()))
                .map(Place::node)
                .toList();
        assertThat(nodes).hasSize(1);
        return nodes.getFirst();
    }

    /** {@code KIND value} or {@code UNKNOWN lastReadingOn}. */
    private static String value(JsonNode valueAt) {
        String kind = valueAt.get("kind").asString();
        return kind + " " + ("UNKNOWN".equals(kind) ? valueAt.get("lastReadingOn").asString()
                : valueAt.get("value").asString());
    }

    /** {@code consumption calculated|exact} of a known consumption. */
    private static String consumption(JsonNode range) {
        assertThat(range.get("known").asBoolean()).isTrue();
        return range.get("consumption").asString() + " "
                + (range.get("calculated").asBoolean() ? "calculated" : "exact");
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(id -> ids.add(UUID.fromString(id.asString())));
        return ids;
    }

    /** The ids of the shown meters of the nodes. */
    private static List<UUID> ids(JsonNode nodes, String field) {
        List<UUID> ids = new ArrayList<>();
        nodes.forEach(node -> ids.add(UUID.fromString(node.get(field).get("id").asString())));
        return ids;
    }

    /** The parent periods of a meter as {@code parent from..to}, oldest first. */
    private List<String> periods(UUID meter) {
        return ownerJdbc().queryForList("select parent_meter_id || ' ' || valid_from || '..' "
                + "|| coalesce(valid_to::text, '') from meter_parent where meter_id = ? order by valid_from",
                String.class, meter);
    }
}
