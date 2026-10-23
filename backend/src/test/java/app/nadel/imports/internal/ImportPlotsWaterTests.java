package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The water column of the Plots sheet ({@code Вода}, feature 011 §2.8) through the API: yes connects a plot to the
 * cooperative's water supply and the name of a well to that well, from the day of the commit, in both languages; no
 * and an empty cell leave a plot as it is (a connected plot stays connected); a re-import changes nothing; every problem
 * of the cell is reported on its row with a code. The plots, wells and connections of the test are removed after it.
 */
class ImportPlotsWaterTests extends ImportCommitSupport {

    private static final List<String> HEADER = List.of("number", "kind", "area_m2", "has_building", "water");
    private static final List<String> HEADER_UK = List.of("Номер ділянки", "Тип ділянки", "Площа, м²", "Є будівля",
            "Вода");

    @Autowired
    private Clock clock;

    private final List<UUID> wells = new ArrayList<>();

    /** Runs before the cleanup of {@link ImportCommitSupport}, which deletes the plots (and with them their connections). */
    @AfterEach
    void removeConnectionsAndWells() {
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                    .until(ImportCommitSupport::noPublicationIsOutstanding);
        } catch (ConditionTimeoutException e) {
            // clean up anyway
        }
        ownerJdbc().update("delete from plot_water_connection where plot_id in (select id from plot "
                + "where tenant_id = ? and number like 'CMT-%')", DEMO_TENANT);
        wells.forEach(id -> {
            ownerJdbc().update("delete from audit_entry where entity_id = ?", id);
            ownerJdbc().update("delete from meter where id = ?", id);
        });
        wells.clear();
    }

    // ---- helpers

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** A Plots row of an existing plot: only its number and the water cell (blank cells keep the stored values). */
    private List<Object> water(String name, @Nullable Object water) {
        List<Object> row = new ArrayList<>();
        row.add(plotNo(name));
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(water);
        return row;
    }

    /** A Plots row of a new plot of 600 square metres with the water cell. */
    private List<Object> newWithWater(String name, String kind, @Nullable Object water) {
        List<Object> row = new ArrayList<>();
        row.add(plotNo(name));
        row.add(kind);
        row.add(600);
        row.add(null);
        row.add(water);
        return row;
    }

    private static byte[] plots(String sheet, List<String> header, List<?>... rows) {
        return ImportWorkbooks.builder().sheet(sheet, header, rows).build();
    }

    /** An active (or removed) common consumer meter: a well; removed after the test. */
    private UUID well(String name, boolean removed) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value, "
                + "removed_on, final_value) values (?, ?, 'COMMON_CONSUMER', 'ELECTRICITY', ?, ?, date '2020-01-10', 0, "
                + "case when ? then date '2021-01-10' else null end, case when ? then 10 else null end)", id,
                DEMO_TENANT, "WELL-" + id.toString().substring(0, 8), name, removed, removed);
        wells.add(id);
        return id;
    }

    /** A connection written as the database owner: lasting when {@code to} is null. */
    private void connected(UUID plot, @Nullable UUID source, LocalDate from, @Nullable LocalDate to) {
        ownerJdbc().update("insert into plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from, "
                + "valid_to) values (?, ?, ?, ?, ?)", DEMO_TENANT, plot, source, from, to);
    }

    /** The connections of a plot, oldest first: source (null = the supply), from and until. */
    private List<Map<String, Object>> connections(UUID plot) {
        return ownerJdbc().queryForList("select source_meter_id, valid_from, valid_to from plot_water_connection "
                + "where plot_id = ? order by valid_from", plot);
    }

    private UUID plotIdOf(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
    }

    /** {@code SHEET/row/column/code} of every problem, in the order of the preview. */
    private static List<String> problems(JsonNode preview) {
        List<String> problems = new ArrayList<>();
        for (JsonNode problem : preview.get("problems")) {
            problems.add(problem.get("sheet").asString() + "/" + problem.get("row").asInt() + "/"
                    + (problem.has("column") ? problem.get("column").asString() : "-") + "/"
                    + problem.get("code").asString());
        }
        return problems;
    }

    private static JsonNode waterCounts(JsonNode preview) {
        return preview.get("counts").get("waterConnections");
    }

    // ---- connecting

    @Test
    void aUkrainianSheetConnectsByYesToTheSupplyAndByAWellsNameToTheWellAndNoOrEmptyLeavesAPlotAsItIs()
            throws Exception {
        UUID well = well("Свердловина " + tag, false);
        UUID kept = storedPlot("kept");
        UUID empty = storedPlot("empty");
        UUID named = storedPlot("named");
        // a connected plot with "ні" stays connected: the import never disconnects
        connected(kept, null, LocalDate.of(2020, 5, 1), null);
        byte[] file = plots("Ділянки", HEADER_UK,
                newWithWater("new", "ДІЛЯНКА", "так"),
                water("kept", "ні"),
                water("empty", null),
                water("named", "  свердловина   " + tag.toUpperCase() + " "));

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        assertThat(waterCounts(preview).get("created").asInt()).isEqualTo(2);
        assertThat(waterCounts(preview).get("updated").asInt()).isZero();
        assertThat(waterCounts(preview).get("unchanged").asInt()).isZero();
        assertThat(preview.get("committable").asBoolean()).isTrue();
        // a preview connects nothing
        assertThat(connections(named)).isEmpty();

        commit(preview.get("id").asString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.waterConnections.created").value(2));

        List<Map<String, Object>> ofNew = connections(plotIdOf(plotNo("new")));
        assertThat(ofNew).hasSize(1);
        assertThat(ofNew.getFirst().get("source_meter_id")).isNull();
        assertThat(ofNew.getFirst().get("valid_from").toString()).isEqualTo(today().toString());
        assertThat(ofNew.getFirst().get("valid_to")).isNull();
        List<Map<String, Object>> ofNamed = connections(named);
        assertThat(ofNamed).hasSize(1);
        assertThat(ofNamed.getFirst().get("source_meter_id")).isEqualTo(well);
        assertThat(ofNamed.getFirst().get("valid_from").toString()).isEqualTo(today().toString());
        List<Map<String, Object>> ofKept = connections(kept);
        assertThat(ofKept).hasSize(1);
        assertThat(ofKept.getFirst().get("valid_to")).isNull();
        assertThat(connections(empty)).isEmpty();
        awaitAudit("WATER_CONNECTED", plotNo(""), 2);
        assertThat(ownerJdbc().queryForList("select c->>'new' from audit_entry e, jsonb_array_elements(e.changes) c "
                + "where e.tenant_id = ? and e.action = 'WATER_CONNECTED' and e.entity_id = ? "
                + "and c->>'field' = 'source'", String.class, DEMO_TENANT, named))
                .containsExactly("Свердловина " + tag);

        // the same file again: both plots are connected already, so nothing is left to do
        JsonNode again = preview(file);
        assertThat(problems(again)).isEmpty();
        assertThat(waterCounts(again).get("created").asInt()).isZero();
        assertThat(waterCounts(again).get("unchanged").asInt()).isEqualTo(2);
        assertThat(again.get("committable").asBoolean()).isFalse();
    }

    @Test
    void anEnglishSheetWithWaterYesDoesTheSameAndYesKeepsAPlotConnectedToAWell() throws Exception {
        UUID well = well("Well " + tag, false);
        UUID atWell = storedPlot("well");
        connected(atWell, well, LocalDate.of(2020, 5, 1), null);
        storedPlot("nine");

        JsonNode preview = preview(plots("Plots", HEADER, water("nine", "Yes"), water("well", "yes"),
                newWithWater("t", "PLOT", Boolean.TRUE)));

        assertThat(problems(preview)).isEmpty();
        assertThat(waterCounts(preview).get("created").asInt()).isEqualTo(2);
        assertThat(waterCounts(preview).get("unchanged").asInt()).isEqualTo(1);
        commit(preview.get("id").asString()).andExpect(status().isOk());

        assertThat(connections(plotIdOf(plotNo("nine")))).singleElement()
                .satisfies(row -> assertThat(row.get("source_meter_id")).isNull());
        assertThat(connections(plotIdOf(plotNo("t")))).hasSize(1);
        assertThat(connections(atWell)).singleElement()
                .satisfies(row -> assertThat(row.get("source_meter_id")).isEqualTo(well));
    }

    // ---- problems

    @Test
    void everyProblemOfTheWaterCellIsReportedOnItsRowAndNothingIsConnected() throws Exception {
        UUID wellA = well("Well A " + tag, false);
        well("Twin " + tag, false);
        well("twin  " + tag, false);
        well("Old well " + tag, true);
        UUID atA = storedPlot("at-a");
        connected(atA, wellA, LocalDate.of(2020, 5, 1), null);
        UUID ending = storedPlot("ending");
        connected(ending, null, LocalDate.of(2020, 5, 1), today().plusDays(5));
        UUID ended = storedPlot("ended");
        connected(ended, null, LocalDate.of(2020, 5, 1), today().minusDays(1));
        storedPlot("free");

        JsonNode preview = preview(plots("Plots", HEADER,
                water("free", "No such well " + tag),
                water("at-a", "Twin " + tag),
                water("ending", "yes"),
                newWithWater("road", "COMMON", "yes"),
                water("ended", "Old well " + tag)));

        assertThat(problems(preview)).containsExactly(
                "PLOTS/2/water/WATER_SOURCE_INVALID",
                "PLOTS/3/water/WATER_SOURCE_AMBIGUOUS",
                "PLOTS/4/water/WATER_ALREADY_CONNECTED",
                "PLOTS/5/water/PLOT_COMMON_LAND",
                "PLOTS/6/water/WATER_SOURCE_INVALID");
        assertThat(preview.get("committable").asBoolean()).isFalse();
        assertThat(connections(atA)).hasSize(1);
        assertThat(connections(ended)).hasSize(1);
    }

    @Test
    void aPlotConnectedToAnotherWellOrWhoseRowHasAProblemIsReportedOnceAndAPlotWithAnEndedConnectionIsConnected()
            throws Exception {
        UUID wellA = well("Well A " + tag, false);
        UUID wellB = well("Well B " + tag, false);
        UUID atA = storedPlot("at-a");
        connected(atA, wellA, LocalDate.of(2020, 5, 1), null);
        UUID ended = storedPlot("ended");
        connected(ended, null, LocalDate.of(2020, 5, 1), today().minusDays(1));

        JsonNode refused = preview(plots("Plots", HEADER,
                water("at-a", "Well B " + tag),
                newWithWater("fine", "PLOT", "yes"),
                List.of(plotNo("broken"), "PLOT", 600, "perhaps", "yes")));
        assertThat(problems(refused)).containsExactly(
                "PLOTS/2/water/WATER_ALREADY_CONNECTED",
                "PLOTS/4/has_building/YES_NO_INVALID");

        JsonNode accepted = preview(plots("Plots", HEADER, water("ended", "WELL B " + tag),
                water("at-a", "Well A " + tag)));
        assertThat(problems(accepted)).isEmpty();
        assertThat(waterCounts(accepted).get("created").asInt()).isEqualTo(1);
        assertThat(waterCounts(accepted).get("unchanged").asInt()).isEqualTo(1);
        commit(accepted.get("id").asString()).andExpect(status().isOk());
        assertThat(connections(ended)).hasSize(2);
        assertThat(connections(ended).getLast().get("source_meter_id")).isEqualTo(wellB);
    }

    @Test
    void aConnectionMadeBetweenThePreviewAndTheCommitMakesThePreviewStale() throws Exception {
        UUID plot = storedPlot("p");
        JsonNode preview = preview(plots("Plots", HEADER, water("p", "так")));
        assertThat(waterCounts(preview).get("created").asInt()).isEqualTo(1);

        connected(plot, null, today().minusDays(10), null);

        commit(preview.get("id").asString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
        assertThat(connections(plot)).hasSize(1);
    }
}
