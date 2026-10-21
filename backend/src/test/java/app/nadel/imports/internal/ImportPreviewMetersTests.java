package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.METER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.METER_HEADER_UK;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.meter;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * POST /imports with the Meters sheet ({@code Лічильники}): new plot meters on plots of the file and on stored plots,
 * the unchanged active meter of a re-import, another active meter as a problem (the import never replaces one), the
 * problems of the cells and rows with sheet, row, column and code, both languages, limits, and nothing saved by a
 * preview. The plots and meters the tests write are removed by {@link ImportCommitSupport}.
 */
class ImportPreviewMetersTests extends ImportCommitSupport {

    private static final LocalDate INSTALLED = LocalDate.of(2020, 3, 1);

    private static List<List<?>> rows(List<?>... rows) {
        return List.of(rows);
    }

    private static byte[] plotsAndMeters(List<List<?>> plots, List<List<?>> meters) {
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plots.toArray(List<?>[]::new))
                .sheet("Meters", METER_HEADER, meters.toArray(List<?>[]::new))
                .build();
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

    private int meterCount() {
        return count("meter");
    }

    // ---- counts

    @Test
    void newMetersOnAPlotOfTheFileAndOnAStoredPlotAreCountedAndStoredWithThePreview() throws Exception {
        storedPlot("s");
        int metersBefore = meterCount();
        byte[] file = plotsAndMeters(rows(newPlot("a")), rows(
                meter(plotNo("a"), "ELECTRICITY", serial("1"), INSTALLED, new BigDecimal("1250.5")),
                meter(plotNo("a"), "water", serial("2"), "01.03.2020", "10,125"),
                meter(plotNo("s").toLowerCase(), "Electricity", " " + serial("3") + " ", INSTALLED, 0)));

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        assertThat(preview.get("counts").get("meters").get("created").asInt()).isEqualTo(3);
        assertThat(preview.get("counts").get("meters").get("updated").asInt()).isZero();
        assertThat(preview.get("counts").get("meters").get("unchanged").asInt()).isZero();
        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("committable").asBoolean()).isTrue();

        Map<String, Object> run = runRow(preview.get("id").asString());
        JsonNode plan = JSON.readTree(run.get("plan").toString());
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("meters")).hasSize(3);
        JsonNode first = plan.get("meters").get(0);
        assertThat(first.get("row").asInt()).isEqualTo(2);
        assertThat(first.get("plotNumber").asString()).isEqualTo(plotNo("a"));
        assertThat(first.get("resource").asString()).isEqualTo("ELECTRICITY");
        assertThat(first.get("serial").asString()).isEqualTo(serial("1"));
        assertThat(first.get("installedOn").asString()).isEqualTo("2020-03-01");
        assertThat(first.get("initialReading").asString()).isEqualTo("1250.5");
        assertThat(plan.get("meters").get(1).get("initialReading").asString()).isEqualTo("10.125");
        assertThat(JSON.readTree(run.get("counts").toString()).get("meters").get("created").asInt()).isEqualTo(3);
        // a preview saves nothing
        assertThat(meterCount()).isEqualTo(metersBefore);
    }

    @Test
    void theUkrainianSheetHeadersAndResourceWordsAreRead() throws Exception {
        storedPlot("s");
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ділянки", PLOT_HEADER)
                .sheet("лічильники", METER_HEADER_UK,
                        meter(plotNo("s"), "ЕЛЕКТРОЕНЕРГІЯ", serial("1"), "01.03.2020", "1250,5"),
                        meter(plotNo("s"), "вода", serial("2"), INSTALLED, 3))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        assertThat(preview.get("counts").get("meters").get("created").asInt()).isEqualTo(2);
        JsonNode stored = JSON.readTree(runRow(preview.get("id").asString()).get("plan").toString()).get("meters");
        assertThat(stored.get(0).get("resource").asString()).isEqualTo("ELECTRICITY");
        assertThat(stored.get(1).get("resource").asString()).isEqualTo("WATER");
    }

    @Test
    void theActiveMeterOfTheSameSerialIsUnchangedAndAloneIsNotCommittable() throws Exception {
        UUID plot = storedPlot("s");
        storedMeter(plot, "ELECTRICITY", serial("abc"), null);
        // a removed water meter does not stand in the way of a new one
        storedMeter(plot, "WATER", serial("old"), "2021-01-01");

        upload(plotsAndMeters(rows(), rows(
                meter(plotNo("s"), "ELECTRICITY", serial("ABC").toLowerCase(), "05.05.2021", 999))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.meters.unchanged").value(1))
                .andExpect(jsonPath("$.counts.meters.created").value(0))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(false));

        upload(plotsAndMeters(rows(), rows(
                meter(plotNo("s"), "WATER", serial("new"), INSTALLED, 1))))
                .andExpect(jsonPath("$.counts.meters.created").value(1))
                .andExpect(jsonPath("$.committable").value(true));
    }

    @Test
    void anotherActiveMeterOfThePlotIsAProblemNamingThePlot() throws Exception {
        UUID plot = storedPlot("s");
        storedMeter(plot, "ELECTRICITY", serial("installed"), null);

        upload(plotsAndMeters(rows(), rows(
                meter(plotNo("s"), "ELECTRICITY", serial("other"), INSTALLED, 5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems", hasSize(1)))
                .andExpect(jsonPath("$.problems[0].sheet").value("METERS"))
                .andExpect(jsonPath("$.problems[0].row").value(2))
                .andExpect(jsonPath("$.problems[0].column").value("serial"))
                .andExpect(jsonPath("$.problems[0].code").value("METER_ACTIVE_EXISTS"))
                .andExpect(jsonPath("$.problems[0].params.plot").value(plotNo("s")))
                .andExpect(jsonPath("$.counts.meters.created").value(0))
                .andExpect(jsonPath("$.committable").value(false));
    }

    // ---- problems

    @Test
    void eachMeterProblemNamesSheetRowColumnAndCode() throws Exception {
        storedPlot("s");
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Kyiv"));
        String tooLong = "S".repeat(51);
        byte[] file = plotsAndMeters(
                rows(newPlot("a"), plot(plotNo("road"), null, "COMMON", 100, null, null, null, null),
                        plot(plotNo("bad"), null, "PLOT", "lots", null, null, null, null)),
                rows(
                        meter(null, "ELECTRICITY", serial("0"), INSTALLED, 1),                       // 2
                        meter(plotNo("a"), null, null, null, null),                                  // 3
                        meter(plotNo("a"), "gas", tooLong, "31.02.2020", "many"),                    // 4
                        meter(plotNo("a"), "ELECTRICITY", serial("1"), today.plusDays(1), "-1"),     // 5
                        meter(plotNo("a"), "WATER", serial("2"), INSTALLED, new BigDecimal("1.2345")), // 6
                        meter(plotNo("s"), "ELECTRICITY", serial("3"), INSTALLED, 1),                // 7
                        meter(plotNo("S"), "electricity", serial("4"), INSTALLED, 1),                // 8: duplicate
                        meter("no-such-plot", "ELECTRICITY", serial("5"), INSTALLED, 1),             // 9
                        meter("R-1", "ELECTRICITY", serial("6"), INSTALLED, 1),                      // 10: common land
                        meter(plotNo("road"), "ELECTRICITY", serial("7"), INSTALLED, 1),             // 11
                        meter(plotNo("bad"), "ELECTRICITY", serial("8"), INSTALLED, 1),              // 12: not checked
                        meter(plotNo("s"), "WATER", serial("9"), today, "12345678901.123")));        // 13: fine

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly(
                "PLOTS/4/area_m2/NUMBER_INVALID",
                "METERS/2/plot_number/REQUIRED",
                "METERS/3/resource/REQUIRED",
                "METERS/3/serial/REQUIRED",
                "METERS/3/installed_on/REQUIRED",
                "METERS/3/initial_reading/REQUIRED",
                "METERS/4/installed_on/DATE_INVALID",
                "METERS/4/initial_reading/NUMBER_INVALID",
                "METERS/4/resource/INVALID",
                "METERS/4/serial/TOO_LONG",
                "METERS/5/installed_on/READING_DATE_IN_FUTURE",
                "METERS/5/initial_reading/METER_VALUE_INVALID",
                "METERS/6/initial_reading/METER_VALUE_INVALID",
                "METERS/8/resource/DUPLICATE_IN_FILE",
                "METERS/9/plot_number/UNKNOWN_PLOT",
                "METERS/10/plot_number/PLOT_COMMON_LAND",
                "METERS/11/plot_number/PLOT_COMMON_LAND");
        // the two fine rows are the only ones counted
        assertThat(preview.get("counts").get("meters").get("created").asInt()).isEqualTo(2);
        assertThat(preview.get("committable").asBoolean()).isFalse();
        assertThat(runRow(preview.get("id").asString())).containsEntry("status", "HAS_ERRORS")
                .containsEntry("plan", null).containsEntry("plan_digest", null);
    }

    @Test
    void aStoredPlotTurnedIntoCommonLandByTheSameFileGetsNoMeter() throws Exception {
        storedPlot("s");

        JsonNode preview = preview(plotsAndMeters(
                rows(plot(plotNo("s"), null, "COMMON", null, null, null, null, null)),
                rows(meter(plotNo("s"), "ELECTRICITY", serial("1"), INSTALLED, 1))));

        assertThat(problems(preview)).containsExactly("METERS/2/plot_number/PLOT_COMMON_LAND");
    }

    @Test
    void aMetersSheetWithoutARequiredColumnIsReportedOnItsHeaderRow() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("Meters", List.of("plot_number", "resource", "installed_on", "initial_reading"),
                        List.of(plotNo("a"), "ELECTRICITY", "01.03.2020", 1))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly("METERS/1/serial/HEADER_MISSING");
        assertThat(preview.get("counts").get("meters").get("created").asInt()).isZero();
    }

    @Test
    void aFileWithoutAMetersSheetCountsNoMeters() throws Exception {
        upload(ImportWorkbooks.plots(newPlot("a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.meters.created").value(0))
                .andExpect(jsonPath("$.counts.meters.updated").value(0))
                .andExpect(jsonPath("$.counts.meters.unchanged").value(0))
                .andExpect(jsonPath("$.committable").value(true));
    }

    @Test
    void moreThanTwoThousandMeterRowsAreRefused() throws Exception {
        List<List<?>> meters = new ArrayList<>();
        for (int i = 0; i <= MetersSheet.MAX_ROWS; i++) {
            meters.add(meter("P-" + i, "ELECTRICITY", "S-" + i, "01.03.2020", 1));
        }

        upload(plotsAndMeters(rows(), meters))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_LIMIT_EXCEEDED"));
        assertThat(count("import_run")).isZero();
    }
}
