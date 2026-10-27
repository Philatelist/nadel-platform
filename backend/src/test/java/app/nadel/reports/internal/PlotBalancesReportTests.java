package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Plot balances (functional spec 2.4, feature 013 §3.2) through the report API on the worked balances of
 * {@link ReportsTestSupport#bookTheBalances()}: debts per charge type, total, oldest unpaid date, days overdue and
 * status RED/YELLOW/GREEN/GREY; today's status is the map's; a past day leaves out later charges and says that today's
 * thresholds were used; the status, street and charge type filters; the owner count without any name, in the preview
 * and in the file; the totals; the list entry; the audit entry.
 */
class PlotBalancesReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/plot-balances";
    private static final String FILE = "/api/v1/reports/plot-balances.xlsx";

    /** Personal data of the fixture that the report must never show. */
    private static final List<String> PERSONAL = List.of("Шевченко", "Тарас", "Григорович", "Коваль", "Марія",
            "Колишній", "Бондар", "Мельник", "Ткач", "+38050", "+38063", "+38044", "taras@", "olena@", "hanna@");

    @BeforeEach
    void book() {
        bookTheBalances();
    }

    @Test
    void todayEveryActivePlotShowsItsDebtsPerTypeTotalOldestDateDaysAndTheStatusOfTheMap() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        List<String> keys = columnKeys(sheet);
        assertThat(keys.subList(0, 3)).containsExactly("number", "street", "owners");
        assertThat(keys.subList(keys.size() - 4, keys.size())).containsExactly("total", "oldestUnpaid",
                "daysOverdue", "status");
        // one money column per charge type, in configured order, named by the charge type's id
        JsonNode membershipColumn = sheet.get("columns").get(keys.indexOf("debt." + membership));
        assertThat(membershipColumn.get("type").asString()).isEqualTo("MONEY");
        assertThat(membershipColumn.get("chargeTypeId").asString()).isEqualTo(membership.toString());
        assertThat(keys.indexOf("debt." + membership)).isLessThan(keys.indexOf("debt." + electricity));
        assertThat(preview.get("chargeTypes")).extracting(type -> type.get("code").asString())
                .startsWith("MEMBERSHIP", "ELECTRICITY");

        // common land and the archived plot are not listed; natural order: 10 and 11 after 3
        assertThat(lines(sheet)).containsExactly(
                "1 Лісова 2 600.00 52.50 652.50 " + daysAgo(120) + " 120 RED",
                "2 Садова 1 1500.00 0.00 1500.00 " + daysAgo(10) + " 10 RED",
                "3 Садова 1 300.00 0.00 300.00 " + daysAgo(20) + " 20 YELLOW",
                "10 Лісова 1 0.00 0.00 0.00 - - GREEN",
                "11 Лісова 0 100.00 0.00 100.00 " + daysAgo(5) + " 5 GREY",
                "TOTAL - - - 2500.00 52.50 2552.50 - - -");
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(5);
        assertThat(filters(preview)).containsExactly("asOf=" + today);
        assertThat(notes(preview)).containsExactly("OLDEST_UNPAID_IS_OLDEST_CHARGE");

        // the status is the one of the plots list and map, plot for plot
        JsonNode plots = body(send(chairman, get("/api/v1/plots?size=100")).andExpect(status().isOk()));
        Map<String, String> mapStatus = new HashMap<>();
        plots.get("items").forEach(plot -> mapStatus.put(plot.get("number").asString(),
                plot.get("status").asString()));
        for (JsonNode row : sheet.get("rows")) {
            JsonNode cells = row.get("cells");
            assertThat(cells.get(keys.indexOf("status")).get("value").asString())
                    .isEqualTo(mapStatus.get(cells.get(0).get("value").asString()));
        }
    }

    @Test
    void aPastDayLeavesOutLaterChargesAndWorksOutTheStatusWithTodaysThresholds() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT).param("asOf", today.minusDays(15).toString()))
                .andExpect(status().isOk()));

        // plot 2's 1500.00 and plot 11's 100.00 are dated after the day; plot 1 is 105 days old on that day
        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "1 Лісова 2 600.00 52.50 652.50 " + daysAgo(120) + " 105 RED",
                "2 Садова 1 0.00 0.00 0.00 - - GREEN",
                "3 Садова 1 300.00 0.00 300.00 " + daysAgo(20) + " 5 YELLOW",
                "10 Лісова 1 0.00 0.00 0.00 - - GREEN",
                "11 Лісова 0 0.00 0.00 0.00 - - GREY",
                "TOTAL - - - 900.00 52.50 952.50 - - -");
        assertThat(notes(preview)).containsExactly("PAST_DATE_STATUS", "OLDEST_UNPAID_IS_OLDEST_CHARGE");

        // today's thresholds: with a red amount of 500.00 plot 1 is red on that day by its amount as well, plot 3
        // stays yellow; with 400 red days plot 1 is red by nothing any more and turns yellow
        thresholds("500.00", 400);
        JsonNode lowered = body(send(treasurer, get(REPORT).param("asOf", today.minusDays(15).toString()))
                .andExpect(status().isOk()));
        assertThat(statuses(lowered.get("sheets").get(0))).containsExactly("1=RED", "2=GREEN", "3=YELLOW",
                "10=GREEN", "11=GREY");
        thresholds("1000.00", 400);
        JsonNode older = body(send(treasurer, get(REPORT).param("asOf", today.minusDays(15).toString()))
                .andExpect(status().isOk()));
        assertThat(statuses(older.get("sheets").get(0))).containsExactly("1=YELLOW", "2=GREEN", "3=YELLOW",
                "10=GREEN", "11=GREY");
    }

    @Test
    void aDayAfterTodayIsAFilterError() throws Exception {
        send(treasurer, get(REPORT).param("asOf", today.plusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("asOf"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void theStatusFilterKeepsOnlyThoseColoursAndTheTotalsSumTheRowsListed() throws Exception {
        JsonNode red = body(send(treasurer, get(REPORT).param("statuses", "RED")).andExpect(status().isOk()));
        assertThat(lines(red.get("sheets").get(0))).containsExactly(
                "1 Лісова 2 600.00 52.50 652.50 " + daysAgo(120) + " 120 RED",
                "2 Садова 1 1500.00 0.00 1500.00 " + daysAgo(10) + " 10 RED",
                "TOTAL - - - 2100.00 52.50 2152.50 - - -");
        assertThat(filters(red)).containsExactly("asOf=" + today, "statuses=RED");

        JsonNode noDebtOrOwner = body(send(treasurer, get(REPORT).param("statuses", "GREY", "GREEN", "GREY"))
                .andExpect(status().isOk()));
        assertThat(statuses(noDebtOrOwner.get("sheets").get(0))).containsExactly("10=GREEN", "11=GREY");
        assertThat(filters(noDebtOrOwner)).containsExactly("asOf=" + today, "statuses=GREY", "statuses=GREEN");
    }

    @Test
    void theStreetFilterKeepsThePlotsOfThatStreet() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT).param("street", " садова ")).andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "2 Садова 1 1500.00 0.00 1500.00 " + daysAgo(10) + " 10 RED",
                "3 Садова 1 300.00 0.00 300.00 " + daysAgo(20) + " 20 YELLOW",
                "TOTAL - - - 1800.00 0.00 1800.00 - - -");
    }

    @Test
    void withSomeChargeTypesTheDebtsCountOnlyThoseButTheStatusStillCountsEveryType() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT).param("chargeTypeIds", electricity.toString()))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(columnKeys(sheet)).containsExactly("number", "street", "owners", "debt." + electricity, "total",
                "oldestUnpaid", "daysOverdue", "status");
        assertThat(cellLines(sheet)).containsExactly(
                "1 Лісова 2 52.50 52.50 " + daysAgo(30) + " 30 RED",
                "2 Садова 1 0.00 0.00 - - RED",
                "3 Садова 1 0.00 0.00 - - YELLOW",
                "10 Лісова 1 0.00 0.00 - - GREEN",
                "11 Лісова 0 0.00 0.00 - - GREY",
                "TOTAL - - - 52.50 52.50 - - -");
        assertThat(notes(preview)).containsExactly("STATUS_ALL_CHARGE_TYPES", "OLDEST_UNPAID_IS_OLDEST_CHARGE");

        // on a past day too: electricity alone would make plot 1 yellow (52.50, 15 days), every type makes it red
        JsonNode past = body(send(treasurer, get(REPORT).param("chargeTypeIds", electricity.toString())
                .param("asOf", today.minusDays(15).toString())).andExpect(status().isOk()));
        assertThat(cellLines(past.get("sheets").get(0)).getFirst())
                .isEqualTo("1 Лісова 2 52.50 52.50 " + daysAgo(30) + " 15 RED");
        assertThat(notes(past)).containsExactly("PAST_DATE_STATUS", "STATUS_ALL_CHARGE_TYPES",
                "OLDEST_UNPAID_IS_OLDEST_CHARGE");
    }

    @Test
    void theOwnersAreCountedAndNeverNamedInThePreviewOrTheFile() throws Exception {
        String json = send(treasurer, get(REPORT)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).doesNotContain(PERSONAL);

        byte[] bytes = send(treasurer, get(FILE + "?lang=uk"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"plot-balances_" + today + "_" + today + ".xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> texts = new ArrayList<>();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("Борги");
            List<Row> rows = workbook.getFirstSheet().read();
            rows.forEach(row -> row.forEach(cell -> texts.add(cell == null ? "" : cell.getText())));
            Map<Integer, Row> byIndex = new HashMap<>();
            rows.forEach(row -> byIndex.put(row.getRowNum() - 1, row));
            assertThat(byIndex.get(1).getCellText(0)).isEqualTo("Борги ділянок");
            assertThat(byIndex.get(2).getCellText(0)).isEqualTo("Фільтри: Станом на: " + ReportTexts.day(today)
                    + "; Види нарахувань: усі; Вулиця: усі; Стани: усі");
            assertThat(byIndex.get(5).getCellText(0)).startsWith("Поки оплати не обліковуються");
            List<String> headers = new ArrayList<>();
            byIndex.get(7).forEach(cell -> headers.add(cell.getText()));
            assertThat(headers.subList(0, 5)).containsExactly("Ділянка", "Вулиця", "Власників", "Членський внесок",
                    "Електроенергія");
            assertThat(headers.subList(headers.size() - 4, headers.size())).containsExactly("Борг усього",
                    "Найстаріше неоплачене", "Днів прострочення", "Стан");
            // plot 1: two owners as a number, its money and its status in the language
            Row first = byIndex.get(8);
            assertThat(first.getCellText(0)).isEqualTo("1");
            assertNumber(first.getCell(2), "2", "#,##0");
            assertNumber(first.getCell(3), "600", "#,##0.00");
            assertThat(first.getCell(headers.size() - 3).getType()).isEqualTo(CellType.NUMBER);
            assertThat(first.getCell(headers.size() - 3).asDate().toLocalDate()).isEqualTo(today.minusDays(120));
            assertThat(first.getCellText(headers.size() - 1)).isEqualTo("Борг вищий за поріг");
            assertThat(byIndex.get(12).getCellText(headers.size() - 1)).isEqualTo("Без власника або спільна земля");
            Row totals = byIndex.get(13);
            assertThat(totals.getCellText(0)).isEqualTo("Усього");
            assertNumber(totals.getCell(headers.size() - 4), "2552.5", "#,##0.00");
        }
        assertThat(String.join("|", texts)).doesNotContain(PERSONAL);

        Rows english = rowsOf(send(treasurer, get(FILE + "?lang=en").param("statuses", "YELLOW"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(english.text(2, 0)).isEqualTo("Filters: As of: " + ReportTexts.day(today)
                + "; Charge types: all; Street: all; Statuses: Has debt");
        assertThat(english.text(7, 3)).isEqualTo("Membership fee");
        assertThat(english.text(8, english.width(7) - 1)).isEqualTo("Has debt");
    }

    @Test
    void theListOffersTheReportWithItsFiltersAndTheRunIsAuditedWithoutPersonalData() throws Exception {
        send(treasurer, get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].code").value("plot-balances"))
                .andExpect(jsonPath("$[1].personalData").value(false))
                .andExpect(jsonPath("$[1].filters[0].key").value("asOf"))
                .andExpect(jsonPath("$[1].filters[0].type").value("DATE"))
                .andExpect(jsonPath("$[1].filters[0].required").value(true))
                .andExpect(jsonPath("$[1].filters[0].defaults[0].param").value("asOf"))
                .andExpect(jsonPath("$[1].filters[0].defaults[0].value").value(today.toString()))
                .andExpect(jsonPath("$[1].filters[1].key").value("chargeTypes"))
                .andExpect(jsonPath("$[1].filters[2].key").value("street"))
                .andExpect(jsonPath("$[1].filters[3].key").value("statuses"))
                .andExpect(jsonPath("$[1].filters[3].type").value("STATUS"))
                .andExpect(jsonPath("$[1].filters[3].params[0]").value("statuses"))
                .andExpect(jsonPath("$[1].filters[3].defaults").isEmpty());

        send(treasurer, get(REPORT).param("statuses", "RED")).andExpect(status().isOk());
        Map<String, Object> entry = awaitReportEntries(1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("plot-balances");
        assertThat(changes(entry)).containsExactly("report=plot-balances", "output=PREVIEW",
                "filters=[{\"param\":\"asOf\",\"value\":\"" + today + "\"},"
                        + "{\"param\":\"statuses\",\"value\":\"RED\"}]",
                "rows=2", "personalData=false");
    }

    // ---- helpers

    private String daysAgo(int days) {
        return today.minusDays(days).toString();
    }

    /** Number, street, owners, membership, electricity, total, oldest, days, status of every row and the totals. */
    private List<String> lines(JsonNode sheet) {
        List<String> keys = columnKeys(sheet);
        List<Integer> wanted = new ArrayList<>();
        for (String key : List.of("number", "street", "owners", "debt." + membership, "debt." + electricity,
                "total", "oldestUnpaid", "daysOverdue", "status")) {
            wanted.add(keys.indexOf(key));
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(pick(row.get("cells"), wanted));
        }
        for (JsonNode total : sheet.get("totals")) {
            lines.add("TOTAL " + pick(total.get("cells"), wanted));
        }
        return lines;
    }

    /** Every cell of every row and the totals. */
    private static List<String> cellLines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(String.join(" ", values(row.get("cells"))));
        }
        for (JsonNode total : sheet.get("totals")) {
            lines.add("TOTAL " + String.join(" ", values(total.get("cells"))));
        }
        return lines;
    }

    private static List<String> statuses(JsonNode sheet) {
        int status = columnKeys(sheet).indexOf("status");
        List<String> result = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            result.add(row.get("cells").get(0).get("value").asString() + "="
                    + row.get("cells").get(status).get("value").asString());
        }
        return result;
    }

    private static String pick(JsonNode cells, List<Integer> indexes) {
        List<String> all = values(cells);
        return String.join(" ", indexes.stream().map(all::get).toList());
    }

    private static List<String> values(JsonNode cells) {
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString() : "-"));
        return values;
    }

    static List<String> columnKeys(JsonNode sheet) {
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        return keys;
    }

    static List<String> filters(JsonNode preview) {
        List<String> filters = new ArrayList<>();
        preview.get("filters").forEach(filter -> filters.add(filter.get("param").asString() + "="
                + filter.get("value").asString()));
        return filters;
    }

    static List<String> notes(JsonNode preview) {
        List<String> notes = new ArrayList<>();
        preview.get("headerNotes").forEach(note -> notes.add(note.get("code").asString()));
        return notes;
    }

    static void assertNumber(Cell cell, String value, String format) {
        assertThat(cell.getType()).isEqualTo(CellType.NUMBER);
        assertThat(cell.asNumber()).isEqualByComparingTo(value);
        assertThat(cell.getDataFormatString()).isEqualTo(format);
    }

    /** The rows of the first worksheet by their index from 0 (the reader leaves empty rows out). */
    static Rows rowsOf(byte[] workbook) throws Exception {
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(workbook),
                new ReadingOptions(true, false))) {
            Map<Integer, Row> byIndex = new HashMap<>();
            book.getFirstSheet().read().forEach(row -> byIndex.put(row.getRowNum() - 1, row));
            return new Rows(byIndex);
        }
    }

    record Rows(Map<Integer, Row> byIndex) {

        String text(int row, int column) {
            return byIndex.get(row).getCellText(column);
        }

        int width(int row) {
            return byIndex.get(row).getCellCount();
        }
    }
}
