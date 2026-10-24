package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Charges by month (functional spec 2.3) through the report API on the worked ledger of {@link ReportsTestSupport}:
 * the preview reconciles with the billing months screen for every month, a reversal is in the reversals column, the
 * March adjustment of January is under March on the main sheet and under January on the service sheet; the street and
 * charge type filters; the workbook (header block, frozen header, numbers as numbers, totals, file name, language);
 * the route of {@code .xlsx}; filter errors; the row cap; the audit entries.
 */
class ChargesByMonthReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/charges-by-month";
    private static final String FILE = "/api/v1/reports/charges-by-month.xlsx";
    private static final String RANGE = "?fromMonth=2025-01&toMonth=2025-03";

    @Autowired
    ReportLimits limits;

    @BeforeEach
    void book() {
        bookTheLedger();
    }

    // ---- preview

    @Test
    void theMainSheetSplitsEveryMonthAndTypeAndTotalsTheMonthsAndTheRange() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT + RANGE)).andExpect(status().isOk()));

        JsonNode main = preview.get("sheets").get(0);
        assertThat(main.get("key").asString()).isEqualTo("byBillingMonth");
        assertThat(columns(main)).containsExactly("month:MONTH", "status:CODE", "chargeType:CHARGE_TYPE",
                "plots:INTEGER", "regular:MONEY", "opening:MONEY", "reversals:MONEY", "adjustments:MONEY",
                "effective:MONEY");
        assertThat(lines(main)).containsExactly(
                "DETAIL 2025-01 CLOSED MEMBERSHIP 2 60.00 1500.00 0.00 0.00 1560.00",
                "DETAIL 2025-01 CLOSED ELECTRICITY 1 52.50 0.00 0.00 0.00 52.50",
                "SUBTOTAL 2025-01 CLOSED - - 112.50 1500.00 0.00 0.00 1612.50",
                // the reversal is in its own column and the effective total is smaller by it
                "DETAIL 2025-02 CLOSED MEMBERSHIP 2 60.00 0.00 -30.00 0.00 30.00",
                "SUBTOTAL 2025-02 CLOSED - - 60.00 0.00 -30.00 0.00 30.00",
                // the adjustments booked in March for January and February are under March
                "DETAIL 2025-03 OPEN MEMBERSHIP 2 50.00 0.00 0.00 -10.00 40.00",
                "SUBTOTAL 2025-03 OPEN - - 50.00 0.00 0.00 -10.00 40.00",
                "TOTAL all - - - - 222.50 1500.00 -30.00 -10.00 1682.50");
        assertThat(main.get("rowCount").asInt()).isEqualTo(7);
        assertThat(main.get("truncated").asBoolean()).isFalse();
        assertThat(preview.get("code").asString()).isEqualTo("charges-by-month");
        assertThat(preview.get("generatedAt").asString()).isNotBlank();
        assertThat(filters(preview)).containsExactly("fromMonth=2025-01", "toMonth=2025-03");
        assertThat(preview.get("headerNotes")).singleElement().satisfies(note -> {
            assertThat(note.get("code").asString()).isEqualTo("SERVICE_PERIOD_SHEET");
            assertThat(note.get("params").isEmpty()).isTrue();
        });
        // the charge types in their configured order, named by their codes for the client
        assertThat(preview.get("chargeTypes")).extracting(type -> type.get("code").asString())
                .containsExactly("MEMBERSHIP", "ELECTRICITY");
    }

    @Test
    void theServiceSheetPutsAnAdjustmentUnderTheMonthOfTheChargeItCorrects() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT + RANGE)).andExpect(status().isOk()));

        JsonNode service = preview.get("sheets").get(1);
        assertThat(service.get("key").asString()).isEqualTo("byServicePeriod");
        assertThat(columns(service)).containsExactly("month:MONTH", "chargeType:CHARGE_TYPE", "plots:INTEGER",
                "regular:MONEY", "opening:MONEY", "reversals:MONEY", "adjustments:MONEY", "effective:MONEY");
        // the electricity of 16.12.2024-15.01.2025 starts in December, before the range
        assertThat(lines(service)).containsExactly(
                "DETAIL 2025-01 MEMBERSHIP 2 60.00 1500.00 0.00 -5.00 1555.00",
                "SUBTOTAL 2025-01 - - 60.00 1500.00 0.00 -5.00 1555.00",
                "DETAIL 2025-02 MEMBERSHIP 2 60.00 0.00 -30.00 -5.00 25.00",
                "SUBTOTAL 2025-02 - - 60.00 0.00 -30.00 -5.00 25.00",
                "DETAIL 2025-03 MEMBERSHIP 2 50.00 0.00 0.00 0.00 50.00",
                "SUBTOTAL 2025-03 - - 50.00 0.00 0.00 0.00 50.00",
                "TOTAL all - - - 170.00 1500.00 -30.00 -10.00 1630.00");
    }

    @Test
    void everyMonthOfTheBillingMonthsScreenHasTheSameTotalsCentForCent() throws Exception {
        // the screen lists every month from the first booked one to the current month, also those without charges
        JsonNode screen = body(send(chairman, get("/api/v1/billing-months")).andExpect(status().isOk()));
        assertThat(screen.get(screen.size() - 1).get("month").asString()).isEqualTo("2025-01");

        int withCharges = 0;
        for (JsonNode month : screen) {
            String ym = month.get("month").asString();
            // the report for that one month, as the treasurer
            JsonNode sheet = body(send(treasurer, get(REPORT + "?fromMonth=" + ym + "&toMonth=" + ym))
                    .andExpect(status().isOk())).get("sheets").get(0);

            assertThat(sheet.get("totals").get(0).get("cells").get(8).get("value").asString()).as("total of %s", ym)
                    .isEqualTo(month.get("total").asString());
            List<String> screenTypes = new ArrayList<>();
            month.get("totals").forEach(t -> screenTypes.add(t.get("chargeTypeId").asString() + "="
                    + t.get("amount").asString()));
            List<String> reportTypes = new ArrayList<>();
            for (JsonNode row : sheet.get("rows")) {
                JsonNode cells = row.get("cells");
                assertThat(cells.get(1).get("value").asString()).as("status of %s", ym)
                        .isEqualTo(month.get("status").asString());
                if (row.get("kind").asString().equals("DETAIL")) {
                    reportTypes.add(cells.get(2).get("value").asString() + "=" + cells.get(8).get("value")
                            .asString());
                }
            }
            assertThat(reportTypes).as("types of %s", ym).containsExactlyInAnyOrderElementsOf(screenTypes);
            withCharges += reportTypes.isEmpty() ? 0 : 1;
        }
        assertThat(withCharges).isEqualTo(3);
    }

    @Test
    void theStreetFilterCountsAndSumsOnlyThePlotsOfThatStreet() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT + RANGE).param("street", " лісова "))
                .andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "DETAIL 2025-01 CLOSED MEMBERSHIP 1 30.00 1500.00 0.00 0.00 1530.00",
                "SUBTOTAL 2025-01 CLOSED - - 30.00 1500.00 0.00 0.00 1530.00",
                "DETAIL 2025-02 CLOSED MEMBERSHIP 1 30.00 0.00 0.00 0.00 30.00",
                "SUBTOTAL 2025-02 CLOSED - - 30.00 0.00 0.00 0.00 30.00",
                "DETAIL 2025-03 OPEN MEMBERSHIP 1 25.00 0.00 0.00 -10.00 15.00",
                "SUBTOTAL 2025-03 OPEN - - 25.00 0.00 0.00 -10.00 15.00",
                "TOTAL all - - - - 85.00 1500.00 0.00 -10.00 1575.00");
        assertThat(filters(preview)).containsExactly("fromMonth=2025-01", "toMonth=2025-03", "street=лісова");

        JsonNode nowhere = body(send(treasurer, get(REPORT + RANGE).param("street", "Невідома"))
                .andExpect(status().isOk())).get("sheets").get(0);
        assertThat(lines(nowhere)).containsExactly("TOTAL all - - - - 0.00 0.00 0.00 0.00 0.00");
        assertThat(nowhere.get("rowCount").asInt()).isZero();
    }

    @Test
    void theChargeTypeFilterCountsOnlyThoseTypes() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT + RANGE + "&chargeTypeIds=" + electricity))
                .andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "DETAIL 2025-01 CLOSED ELECTRICITY 1 52.50 0.00 0.00 0.00 52.50",
                "SUBTOTAL 2025-01 CLOSED - - 52.50 0.00 0.00 0.00 52.50",
                "TOTAL all - - - - 52.50 0.00 0.00 0.00 52.50");
        assertThat(filters(preview)).containsExactly("fromMonth=2025-01", "toMonth=2025-03",
                "chargeTypeIds=" + electricity);
        assertThat(preview.get("chargeTypes")).extracting(type -> type.get("code").asString())
                .containsExactly("ELECTRICITY");
    }

    @Test
    void withoutFiltersTheReportCoversThePreviousMonth() throws Exception {
        String previous = YearMonth.now(ZoneId.of("Europe/Kyiv")).minusMonths(1).toString();

        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("fromMonth=" + previous, "toMonth=" + previous);
        assertThat(preview.get("sheets").get(0).get("rowCount").asInt()).isZero();
    }

    // ---- list

    @Test
    void theListNamesTheReportWithItsFiltersAndDefaults() throws Exception {
        String previous = YearMonth.now(ZoneId.of("Europe/Kyiv")).minusMonths(1).toString();

        send(chairman, get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("charges-by-month"))
                .andExpect(jsonPath("$[0].personalData").value(false))
                .andExpect(jsonPath("$[0].filters[0].key").value("period"))
                .andExpect(jsonPath("$[0].filters[0].type").value("MONTH_RANGE"))
                .andExpect(jsonPath("$[0].filters[0].params[0]").value("fromMonth"))
                .andExpect(jsonPath("$[0].filters[0].params[1]").value("toMonth"))
                .andExpect(jsonPath("$[0].filters[0].required").value(true))
                .andExpect(jsonPath("$[0].filters[0].basis").value("BILLING_MONTHS"))
                .andExpect(jsonPath("$[0].filters[0].defaults[0].param").value("fromMonth"))
                .andExpect(jsonPath("$[0].filters[0].defaults[0].value").value(previous))
                .andExpect(jsonPath("$[0].filters[0].defaults[1].value").value(previous))
                .andExpect(jsonPath("$[0].filters[1].key").value("chargeTypes"))
                .andExpect(jsonPath("$[0].filters[1].required").value(false))
                .andExpect(jsonPath("$[0].filters[1].defaults").isEmpty())
                .andExpect(jsonPath("$[0].filters[1].basis").doesNotExist())
                .andExpect(jsonPath("$[0].filters[2].key").value("street"))
                .andExpect(jsonPath("$[0].filters[2].type").value("STREET"));
    }

    // ---- file

    @Test
    void theWorkbookHasTheHeaderBlockAFrozenHeaderNumbersAsNumbersAndTheTotals() throws Exception {
        ResultActions download = send(chairman, get(FILE + RANGE + "&lang=uk"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"charges-by-month_2025-01-01_2025-03-31.xlsx\""));
        byte[] bytes = download.andReturn().getResponse().getContentAsByteArray();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            List<Sheet> sheets = workbook.getSheets().toList();
            assertThat(sheets).extracting(Sheet::getName).containsExactly("За місяцями нарахувань",
                    "За період послуг");
            Rows rows = new Rows(sheets.get(0).read());
            assertThat(text(rows, 0, 0)).isEqualTo(COOPERATIVE);
            assertThat(text(rows, 1, 0)).isEqualTo("Нарахування за місяцями");
            assertThat(text(rows, 2, 0)).isEqualTo(
                    "Фільтри: Місяці нарахувань: 01.2025 – 03.2025; Види нарахувань: усі; Вулиця: усі");
            assertThat(text(rows, 3, 0)).matches("Сформовано: \\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}");
            assertThat(text(rows, 4, 0)).startsWith("Сформував(ла): ").contains("CHAIRMAN");
            assertThat(text(rows, 5, 0)).startsWith("Аркуш «За період послуг» групує");
            assertThat(rowTexts(rows, 7)).containsExactly("Місяць", "Стан місяця", "Вид нарахування", "Ділянок",
                    "Нараховано", "Вхідні залишки", "Сторно", "Коригування", "Разом нараховано");
            // January membership: month as a date, codes and names in the language, numbers as numbers
            Row january = rows.get(8);
            assertThat(january.getCell(0).getType()).isEqualTo(CellType.NUMBER);
            assertThat(january.getCell(0).getDataFormatString()).isEqualTo("mm.yyyy");
            assertThat(january.getCell(0).asDate().toLocalDate()).isEqualTo(YearMonth.of(2025, 1).atDay(1));
            assertThat(text(rows, 8, 1)).isEqualTo("Закритий");
            assertThat(text(rows, 8, 2)).isEqualTo("Членський внесок");
            assertNumber(january.getCell(3), "2", "#,##0");
            assertNumber(january.getCell(5), "1500", "#,##0.00");
            assertNumber(january.getCell(8), "1560", "#,##0.00");
            // the subtotal of January names itself in the charge type column, the plot count stays empty
            assertThat(text(rows, 10, 2)).isEqualTo("Разом");
            assertThat(rows.get(10).getOptionalCell(3).map(Cell::getType).orElse(CellType.EMPTY))
                    .isEqualTo(CellType.EMPTY);
            assertNumber(rows.get(10).getCell(8), "1612.5", "#,##0.00");
            // the totals line after the seven rows: the same totals as the preview
            Row totals = rows.get(15);
            assertThat(text(rows, 15, 1)).isEqualTo("Усього");
            assertNumber(totals.getCell(4), "222.5", "#,##0.00");
            assertNumber(totals.getCell(6), "-30", "#,##0.00");
            assertNumber(totals.getCell(8), "1682.5", "#,##0.00");
            assertThat(rows.size()).isEqualTo(16);

            Rows service = new Rows(sheets.get(1).read());
            assertThat(rowTexts(service, 7)).containsExactly("Місяць", "Вид нарахування", "Ділянок", "Нараховано",
                    "Вхідні залишки", "Сторно", "Коригування", "Разом нараховано");
            assertNumber(service.get(service.size() - 1).getCell(7), "1630", "#,##0.00");
        }
        // the header row (the eighth) stays in view: the pane is frozen below it on both sheets
        assertThat(sheetXml(bytes, "xl/worksheets/sheet1.xml")).contains("ySplit=\"8\"").contains("state=\"frozen\"");
        assertThat(sheetXml(bytes, "xl/worksheets/sheet2.xml")).contains("ySplit=\"8\"");
    }

    @Test
    void theWorkbookTextsFollowTheLanguageAndPlotDataStaysAsStored() throws Exception {
        Rows english = firstSheet(send(chairman, get(FILE + RANGE + "&lang=en").param("street", "Лісова")));
        assertThat(text(english, 1, 0)).isEqualTo("Charges by month");
        assertThat(text(english, 2, 0)).isEqualTo(
                "Filters: Billing months: 01.2025 – 03.2025; Charge types: all; Street: Лісова");
        assertThat(text(english, 3, 0)).startsWith("Generated at: ");
        assertThat(text(english, 4, 0)).startsWith("Generated by: ");
        assertThat(rowTexts(english, 7)).containsExactly("Month", "Month status", "Charge type", "Plots", "Charged",
                "Opening balances", "Reversals", "Adjustments", "Effective total");
        assertThat(text(english, 8, 1)).isEqualTo("Closed");
        assertThat(text(english, 8, 2)).isEqualTo("Membership fee");

        // Accept-Language decides without lang; a renamed charge type is written as stored
        ownerJdbc().update("update charge_type set name = 'Внесок (новий)', name_customized = true where id = ?",
                membership);
        Rows byHeader = firstSheet(send(chairman, get(FILE + RANGE).header(HttpHeaders.ACCEPT_LANGUAGE,
                "en-US,en;q=0.9,uk;q=0.8")));
        assertThat(text(byHeader, 7, 0)).isEqualTo("Month");
        assertThat(text(byHeader, 8, 2)).isEqualTo("Внесок (новий)");
        Rows ukrainian = firstSheet(send(chairman, get(FILE + RANGE)));
        assertThat(text(ukrainian, 7, 0)).isEqualTo("Місяць");
        assertThat(text(ukrainian, 9, 2)).isEqualTo("Електроенергія");
    }

    @Test
    void theXlsxPathWinsOverTheCodePath() throws Exception {
        send(chairman, get(FILE))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        send(chairman, get(REPORT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // ---- errors

    @Test
    void filtersThatDoNotFitAreFieldErrors() throws Exception {
        send(treasurer, get(REPORT + "?fromMonth=2025-03&toMonth=2025-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("toMonth"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(treasurer, get(FILE + "?fromMonth=2025-03&chargeTypeIds=" + membership + "&chargeTypeIds="
                + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("chargeTypeIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // a charge type of another cooperative is not one of this cooperative
        UUID foreign = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? "
                + "and code = 'MEMBERSHIP'", UUID.class, DEMO_TENANT);
        send(treasurer, get(REPORT + "?chargeTypeIds=" + foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chargeTypeIds[0]"));
    }

    @Test
    void malformedParametersUnknownCodesAndReportsNotYetDefinedAreRefused() throws Exception {
        send(treasurer, get(REPORT + "?fromMonth=2025-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        send(treasurer, get(FILE + "?lang=de"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        send(treasurer, get("/api/v1/reports/nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        send(treasurer, get("/api/v1/reports/land-tax"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(treasurer, get("/api/v1/reports/land-tax.xlsx"))
                .andExpect(status().isNotFound());
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    @Test
    void aReportOverTheRowCapIsRefusedAndNotAudited() throws Exception {
        limits.rowCap(6);
        try {
            send(treasurer, get(REPORT + RANGE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
            send(treasurer, get(FILE + RANGE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
            // seven rows on the main sheet are over a cap of six; the second month alone has two
            send(treasurer, get(REPORT + "?fromMonth=2025-02&toMonth=2025-02")).andExpect(status().isOk());
            limits.rowCap(7);
            send(treasurer, get(REPORT + RANGE)).andExpect(status().isOk());
        } finally {
            limits.rowCap(ReportLimits.DEFAULT_ROW_CAP);
        }
        awaitOutbox();
        assertThat(reportEntries()).hasSize(2);
    }

    // ---- audit

    @Test
    void thePreviewAndTheFileAreAuditedWithTheFiltersAndRowCountButNoContents() throws Exception {
        send(chairman, get(REPORT + RANGE + "&chargeTypeIds=" + membership).param("street", "Лісова"))
                .andExpect(status().isOk());
        send(chairman, get(FILE + RANGE)).andExpect(status().isOk());

        List<Map<String, Object>> entries = awaitReportEntries(2);
        Map<String, Object> preview = entries.get(0);
        assertThat(preview.get("entity_type")).isEqualTo("REPORT");
        assertThat(preview.get("entity_label")).isEqualTo("charges-by-month");
        assertThat(preview.get("entity_id")).isEqualTo(UUID.nameUUIDFromBytes(
                "report:charges-by-month".getBytes(StandardCharsets.UTF_8)));
        assertThat(preview.get("actor_member_id")).isEqualTo(memberId("CHAIRMAN"));
        assertThat(changes(preview)).containsExactly("report=charges-by-month", "output=PREVIEW",
                "filters=[{\"param\":\"fromMonth\",\"value\":\"2025-01\"},{\"param\":\"toMonth\",\"value\":\"2025-03\"},"
                        + "{\"param\":\"chargeTypeIds\",\"value\":\"" + membership + "\"},"
                        + "{\"param\":\"street\",\"value\":\"Лісова\"}]",
                "rows=12", "personalData=false");
        Map<String, Object> file = entries.get(1);
        assertThat(file.get("entity_id")).isEqualTo(preview.get("entity_id"));
        assertThat(changes(file)).containsExactly("report=charges-by-month", "output=XLSX",
                "filters=[{\"param\":\"fromMonth\",\"value\":\"2025-01\"},{\"param\":\"toMonth\",\"value\":\"2025-03\"}]",
                "rows=13", "personalData=false");
        // no amount of the report is in the entry
        assertThat((String) file.get("changes")).doesNotContain("1682.50").doesNotContain("1560.00");
    }

    // ---- helpers

    private List<String> lines(JsonNode sheet) {
        Map<String, String> codes = Map.of(membership.toString(), "MEMBERSHIP", electricity.toString(),
                "ELECTRICITY");
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(row.get("kind").asString() + " " + cells(row.get("cells"), codes));
        }
        for (JsonNode total : sheet.get("totals")) {
            lines.add("TOTAL " + total.get("key").asString() + " " + cells(total.get("cells"), codes));
        }
        return lines;
    }

    private static String cells(JsonNode cells, Map<String, String> codes) {
        List<String> values = new ArrayList<>();
        for (JsonNode cell : cells) {
            String value = cell.has("value") ? cell.get("value").asString() : "-";
            values.add(codes.getOrDefault(value, value));
        }
        return String.join(" ", values);
    }

    private static List<String> columns(JsonNode sheet) {
        List<String> columns = new ArrayList<>();
        sheet.get("columns").forEach(column -> columns.add(column.get("key").asString() + ":"
                + column.get("type").asString()));
        return columns;
    }

    private static List<String> filters(JsonNode preview) {
        List<String> filters = new ArrayList<>();
        preview.get("filters").forEach(filter -> filters.add(filter.get("param").asString() + "="
                + filter.get("value").asString()));
        return filters;
    }

    private static Rows firstSheet(ResultActions download) throws Exception {
        byte[] bytes = download.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes))) {
            return new Rows(workbook.getFirstSheet().read());
        }
    }

    /** The rows of a worksheet by their index from 0; the reader leaves empty rows out of its list. */
    private record Rows(Map<Integer, Row> byIndex) {

        Rows(List<Row> rows) {
            this(rows.stream().collect(Collectors.toMap(row -> row.getRowNum() - 1, row -> row)));
        }

        Row get(int index) {
            return byIndex.get(index);
        }

        /** The index of the last row plus one. */
        int size() {
            return byIndex.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        }
    }

    private static String text(Rows rows, int row, int column) {
        return rows.get(row).getCellText(column);
    }

    private static List<String> rowTexts(Rows rows, int row) {
        List<String> texts = new ArrayList<>();
        rows.get(row).forEach(cell -> texts.add(cell.getText()));
        return texts;
    }

    private static void assertNumber(Cell cell, String value, String format) {
        assertThat(cell.getType()).isEqualTo(CellType.NUMBER);
        assertThat(cell.asNumber()).isEqualByComparingTo(value);
        assertThat(cell.getDataFormatString()).isEqualTo(format);
    }

    /** One part of the workbook; fastexcel streams with ZIP64 data descriptors, which only {@link ZipFile} reads. */
    private static String sheetXml(byte[] workbook, String entry) throws IOException {
        Path file = Files.createTempFile("report", ".xlsx");
        try {
            Files.write(file, workbook);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            Files.delete(file);
        }
    }
}
