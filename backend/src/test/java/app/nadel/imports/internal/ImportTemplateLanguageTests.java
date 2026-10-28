package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The template in both languages end to end (download, keep the example rows, upload) and uploads that mix Ukrainian and
 * English sheet names, headers and value words in one file.
 */
class ImportTemplateLanguageTests extends ImportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate SINCE = LocalDate.of(2020, 3, 1);

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private byte[] download(String lang) throws Exception {
        return mvc.perform(get(IMPORTS + "/template?lang=" + lang).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /**
     * The template with the marker of the example rows removed ({@code # example: 12a} becomes {@code 12a},
     * {@code # приклад: 31а} becomes {@code 31а}) so that the example is data: "keep the example row". The hint rows
     * stay comments.
     */
    private static byte[] keepTheExampleRows(byte[] template) throws IOException {
        ImportWorkbooks.Builder builder = ImportWorkbooks.builder();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(template))) {
            for (Sheet sheet : workbook.getSheets().toList()) {
                List<List<Object>> rows = new ArrayList<>();
                for (Row row : sheet.read()) {
                    List<Object> cells = new ArrayList<>();
                    for (int column = 0; column < row.getCellCount(); column++) {
                        String text = row.getCellText(column);
                        cells.add(text.isEmpty() ? null : text);
                    }
                    rows.add(cells);
                }
                String marker = (String) rows.get(2).get(0);
                assertThat(marker).startsWith("# ");
                rows.get(2).set(0, marker.replaceFirst("^#\\s*(example|приклад):\\s*", ""));
                builder.sheetWithRows(sheet.getName(), List.copyOf(rows));
            }
        }
        return builder.build();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode preview(byte[] file) throws Exception {
        return body(upload(file).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode plan(JsonNode preview) {
        return storedPlan(preview.get("id").asString());
    }

    private static List<String> problems(JsonNode preview) {
        List<String> problems = new ArrayList<>();
        for (JsonNode problem : preview.get("problems")) {
            problems.add(problem.get("sheet").asString() + "/" + problem.get("row").asInt() + "/"
                    + (problem.has("column") ? problem.get("column").asString() : "-") + "/"
                    + problem.get("code").asString());
        }
        return problems;
    }

    // ---- the downloaded template, per language

    @ParameterizedTest
    @ValueSource(strings = {"uk", "en"})
    void theUnchangedDownloadedTemplateHasNothingToImport(String lang) throws Exception {
        int plotsBefore = count("plot");

        upload(download(lang))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.members.updated").value(0))
                .andExpect(jsonPath("$.counts.members.unchanged").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.committable").value(false));
        assertThat(count("plot")).isEqualTo(plotsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk", "en"})
    void theTemplateWithItsExampleRowsKeptUploadsWithoutProblemsAndShowsThemAsNew(String lang) throws Exception {
        JsonNode preview = preview(keepTheExampleRows(download(lang)));

        assertThat(problems(preview)).isEmpty();
        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("plots").get("updated").asInt()).isZero();
        assertThat(preview.get("counts").get("members").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("members").get("updated").asInt()).isZero();
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("committable").asBoolean()).isTrue();
    }

    @Test
    void theUkrainianExampleIsReadAsTheUkrainianPersonAndPlotItShows() throws Exception {
        JsonNode plan = plan(preview(keepTheExampleRows(download("uk"))));

        JsonNode plot = plan.get("plots").get(0);
        assertThat(plot.get("number").asString()).isEqualTo("31а");
        assertThat(plot.get("street").asString()).isEqualTo("вулиця Садова");
        assertThat(plot.get("kind").asString()).isEqualTo("PLOT");
        assertThat(plot.get("areaM2").asString()).isEqualTo("600");
        assertThat(plot.get("hasBuilding").asBoolean()).isTrue();
        assertThat(plot.get("notes").asString()).isEqualTo("Кутова ділянка");
        JsonNode member = plan.get("members").get(0);
        assertThat(member.get("lastName").asString()).isEqualTo("Шевченко");
        assertThat(member.get("firstName").asString()).isEqualTo("Тарас");
        assertThat(member.get("middleName").asString()).isEqualTo("Григорович");
        assertThat(member.get("deliveryChannel").asString()).isEqualTo("EMAIL");
        JsonNode ownership = plan.get("ownership").get(0);
        assertThat(ownership.get("plotNumber").asString()).isEqualTo("31а");
        assertThat(ownership.get("memberKey").asString()).isEqualTo("Ч-001");
        assertThat(ownership.get("share").asString()).isEqualTo("1");
        assertThat(ownership.get("validFrom").asString()).isEqualTo("2020-03-01");
    }

    // ---- uploads that mix the two languages

    private String plotNo(String name) {
        return name + tag;
    }

    @Test
    void ukrainianSheetNamesWithEnglishHeaders() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ділянки", PLOT_HEADER, plot(plotNo("a"), "Garden", "PLOT", 600, null, null, "yes", null))
                .sheet("Члени", MEMBER_HEADER, member("K-1", "Last" + tag, "First", "k1." + tag + "@import.example.com"))
                .sheet("Власність", OWNERSHIP_HEADER, owner(plotNo("a"), "K-1", "1", SINCE))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("members").get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isEqualTo(1);
    }

    @Test
    void englishSheetNamesWithUkrainianHeadersAndUkrainianValueWords() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", List.of("Номер ділянки", "Тип ділянки", "Площа, сот.", "Є будівля"),
                        List.of(plotNo("b"), "ДІЛЯНКА", 6, "так"),
                        List.of(plotNo("c"), "Спільна земля", 2, "ні"))
                .sheet("Members", List.of("Ключ члена", "Прізвище", "Ім'я", "Електронна пошта", "Канал доставки"),
                        List.of("K-2", "Last" + tag, "First", "k2." + tag + "@import.example.com", "ел. пошта"))
                .sheet("Ownership", List.of("Номер ділянки", "Ключ члена", "Частка", "Власник з"),
                        owner(plotNo("b"), "K-2", "1", SINCE))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(2);
        JsonNode plan = plan(preview);
        assertThat(plan.get("plots").get(0).get("kind").asString()).isEqualTo("PLOT");
        assertThat(plan.get("plots").get(0).get("hasBuilding").asBoolean()).isTrue();
        assertThat(plan.get("plots").get(0).get("areaM2").asString()).isEqualTo("600");
        assertThat(plan.get("plots").get(1).get("kind").asString()).isEqualTo("COMMON");
        assertThat(plan.get("plots").get(1).get("hasBuilding").asBoolean()).isFalse();
        assertThat(plan.get("members").get(0).get("deliveryChannel").asString()).isEqualTo("EMAIL");
    }

    @Test
    void aSheetMayMixUkrainianAndEnglishHeadersAndEnglishValueWordsStillWork() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ділянки", List.of("number", "Вулиця", "kind", "Площа, м²", "has_building"),
                        List.of(plotNo("d"), "Садова", "common", 100, "no"),
                        List.of(plotNo("e"), "Садова", "ДІЛЯНКА", 200, "TRUE"))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        JsonNode plan = plan(preview);
        assertThat(plan.get("plots").get(0).get("kind").asString()).isEqualTo("common"); // the plots module reads it
        assertThat(plan.get("plots").get(1).get("kind").asString()).isEqualTo("PLOT");
        assertThat(plan.get("plots").get(1).get("hasBuilding").asBoolean()).isTrue();
    }

    @Test
    void everyUkrainianDeliveryChannelWordIsRead() throws Exception {
        List<List<?>> rows = List.of(
                member("K-1", "Email" + tag, "A", null, null, "e." + tag + "@import.example.com", null, "ЕЛ. ПОШТА", null, null),
                member("K-2", "Tg" + tag, "B", null, "+380671110002", null, null, "телеграм", "tg_user", null),
                member("K-3", "Viber" + tag, "C", null, "+380671110003", null, null, "Вайбер", null, "+380671234567"),
                member("K-4", "Paper" + tag, "D", null, "+380671110004", null, null, "ПАПЕР", null, null));
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("Члени", MEMBER_HEADER, rows.toArray(List<?>[]::new))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).isEmpty();
        List<String> channels = new ArrayList<>();
        for (JsonNode member : plan(preview).get("members")) {
            channels.add(member.get("deliveryChannel").asString());
        }
        assertThat(channels).containsExactly("EMAIL", "TELEGRAM", "VIBER", "PAPER");
    }

    @Test
    void anUnknownKindWordIsStillReportedOnTheKindColumn() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ділянки", PLOT_HEADER, plot(plotNo("f"), null, "ЩОСЬ", 600, null, null, null, null))
                .build();

        assertThat(problems(preview(file))).containsExactly("PLOTS/2/kind/INVALID");
    }

    @Test
    void anUnknownYesNoWordIsStillReportedOnTheBuildingColumn() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ділянки", PLOT_HEADER, plot(plotNo("g"), null, "ДІЛЯНКА", 600, null, null, "може", null))
                .build();

        assertThat(problems(preview(file))).containsExactly("PLOTS/2/has_building/YES_NO_INVALID");
    }

    @Test
    void aWorkbookWithoutAPlotsSheetInEitherLanguageIsReportedMissing() throws Exception {
        byte[] file = ImportWorkbooks.builder().sheet("Аркуш1", PLOT_HEADER).build();

        assertThat(problems(preview(file))).containsExactly("PLOTS/0/-/SHEET_MISSING");
    }
}
