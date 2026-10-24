package app.nadel.reports.internal;

import static app.nadel.reports.internal.PlotBalancesReportTests.assertNumber;
import static app.nadel.reports.internal.PlotBalancesReportTests.columnKeys;
import static app.nadel.reports.internal.PlotBalancesReportTests.filters;
import static app.nadel.reports.internal.PlotBalancesReportTests.notes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Land tax (functional spec 2.9, feature 013 §3.7) through the report API in a cooperative of its own, for 2025 (365
 * days) with the land tax tariff "Земельний податок" of 35.00 per sotka per year from 01.01.2024 for every plot:
 * <pre>
 * plot  street  area       land tax charged for 2025                          expected  charged  mark
 * 1     Лісова  600.00 m2  12 x 17.50                                          210.00    210.00   no
 * 2     Садова  600.00 m2  12 x 17.50, December reversed (-17.50)              210.00    192.50   yes
 * 5     Садова  612.00 m2  12 x 17.85                                          214.20    214.20   no
 * 3     Лісова  600.00 m2  excluded from the tariff                            not listed
 * Д-1   Лісова  common land                                                    not listed
 * 4     Лісова  archived                                                       not listed
 * </pre>
 * 600 m2 is 6 sotkas: 35.00 x 6 / 12 = 17.50 a month, 210.00 a year; 612 m2 is 6.12 sotkas, 17.85 a month. A second
 * version of 40.00 from 16.07.2025 makes two rows for a plot: 35.00 for 196 days (113.47) and 40.00 for 169 days
 * (110.32), together 223.79 (the worked numbers of {@code tariffs.ExpectedAmountsTests}).
 */
class LandTaxReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/land-tax";
    private static final String FILE = "/api/v1/reports/land-tax.xlsx";

    private UUID landTax;
    private UUID tariff;
    private UUID l1;
    private UUID l2;
    private UUID l5;

    @BeforeEach
    void book() {
        landTax = typeId("LAND_TAX");
        tariff = tariff(landTax, "Земельний податок");
        perSotkaYearly(tariff, "35", "2024-01-01");
        l1 = plot("1", "Лісова");
        l2 = plot("2", "Садова");
        l5 = plot("5", "Садова", "612.00");
        exclude(tariff, plot("3", "Лісова"));
        UUID common = plot("Д-1", "Лісова");
        ownerJdbc().update("update plot set kind = 'COMMON' where id = ?", common);
        UUID archived = plot("4", "Лісова");
        ownerJdbc().update("update plot set archived_at = now() where id = ?", archived);

        monthly(l1, "17.50");
        UUID december = monthly(l2, "17.50");
        reversal(december);
        monthly(l5, "17.85");
        // charged to the excluded, common and archived plots: still not listed
        charge(common, landTax, "REGULAR", "TARIFF", "99.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(archived, landTax, "REGULAR", "TARIFF", "99.00", "2025-01-01", "2025-01-31", "2025-01");
    }

    @Test
    void everyPlotTheTariffAppliesToShowsItsAreaRateDaysExpectedAndChargedAndADifferenceIsMarked()
            throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT).param("year", "2025")).andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        assertThat(columnKeys(sheet)).containsExactly("number", "street", "areaM2", "areaSotka", "rate", "from", "to",
                "days", "expected", "charged", "mismatch");
        assertThat(types(sheet)).containsExactly("TEXT", "TEXT", "DECIMAL", "DECIMAL", "DECIMAL", "DATE", "DATE",
                "INTEGER", "MONEY", "MONEY", "BOOLEAN");
        // 35.00 per sotka on 600 m2: 6 sotkas and 210.00; plot 2 was charged 192.50 and is marked
        assertThat(lines(sheet)).containsExactly(
                "1 Лісова 600.00 6.0000 35.0000 2025-01-01 2025-12-31 365 210.00 210.00 false",
                "2 Садова 600.00 6.0000 35.0000 2025-01-01 2025-12-31 365 210.00 192.50 true",
                "5 Садова 612.00 6.1200 35.0000 2025-01-01 2025-12-31 365 214.20 214.20 false",
                "TOTAL - - - - - - - - 634.20 616.70 -");
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(3);
        assertThat(filters(preview)).containsExactly("year=2025");
        assertThat(notes(preview)).containsExactly("RECALCULATION_SUGGESTED");
        assertThat(preview.get("headerNotes").get(0).get("params").get("count").asString()).isEqualTo("1");

        // the report suggests a recalculation and books none
        assertThat(ownerJdbc().queryForObject("select count(*) from recalculation where tenant_id = ?", Integer.class,
                tenant)).isZero();
    }

    @Test
    void whenEveryPlotIsChargedWhatIsExpectedNothingIsMarkedAndNoRecalculationIsSuggested() throws Exception {
        charge(l2, landTax, "REGULAR", "TARIFF", "17.50", "2025-12-01", "2025-12-31", "2026-01");

        JsonNode preview = body(send(treasurer, get(REPORT).param("year", "2025")).andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "1 Лісова 600.00 6.0000 35.0000 2025-01-01 2025-12-31 365 210.00 210.00 false",
                "2 Садова 600.00 6.0000 35.0000 2025-01-01 2025-12-31 365 210.00 210.00 false",
                "5 Садова 612.00 6.1200 35.0000 2025-01-01 2025-12-31 365 214.20 214.20 false",
                "TOTAL - - - - - - - - 634.20 634.20 -");
        assertThat(notes(preview)).isEmpty();
    }

    @Test
    void twoRatesInTheYearGiveOneRowPerRateWithItsDaysAndTheChargedAmountOnce() throws Exception {
        perSotkaYearly(tariff, "40", "2025-07-16");
        // plot 1 charged as the month run would: January to June 17.50, July 18.79, August to December 20.00
        UUID julyOn = ownerJdbc().queryForObject("select id from charge where plot_id = ? and service_from = "
                + "date '2025-07-01'", UUID.class, l1);
        reversal(julyOn);
        charge(l1, landTax, "REGULAR", "TARIFF", "18.79", "2025-07-01", "2025-07-31", "2025-07");
        for (int month = 8; month <= 12; month++) {
            charge(l1, landTax, "REGULAR", "TARIFF", "2.50", YearMonth.of(2025, month).atDay(1).toString(),
                    YearMonth.of(2025, month).atEndOfMonth().toString(), "2025-12");
        }

        JsonNode preview = body(send(treasurer, get(REPORT).param("year", "2025")).andExpect(status().isOk()));

        List<String> lines = lines(preview.get("sheets").get(0));
        assertThat(lines.subList(0, 2)).containsExactly(
                "1 Лісова 600.00 6.0000 35.0000 2025-01-01 2025-07-15 196 113.47 223.79 false",
                "1 Лісова - - 40.0000 2025-07-16 2025-12-31 169 110.32 - -");
        // every plot has two rates now (plot 5: 6 x 17.85 + July 19.17 + 5 x 20.40 = 228.27); the totals count each
        // share and each plot's charged amount once: 223.79 + 223.79 + 228.27 and 223.79 + 192.50 + 214.20
        assertThat(lines).hasSize(7);
        assertThat(lines.getLast()).isEqualTo("TOTAL - - - - - - - - 675.85 630.49 -");
        assertThat(notes(preview)).containsExactly("RECALCULATION_SUGGESTED");
        assertThat(preview.get("headerNotes").get(0).get("params").get("count").asString()).isEqualTo("2");
    }

    @Test
    void theYearIsTheCurrentOneByDefaultAndCannotBeAfterIt() throws Exception {
        int current = LocalDate.now(KYIV).getYear();

        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("year=" + current);
        // nothing is charged for this year in the fixture: every listed plot differs
        assertThat(preview.get("sheets").get(0).get("rowCount").asInt()).isEqualTo(3);
        assertThat(preview.get("headerNotes").get(0).get("params").get("count").asString()).isEqualTo("3");

        send(treasurer, get(REPORT).param("year", Integer.toString(current + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("year"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(treasurer, get(REPORT).param("year", "1999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        send(treasurer, get("/api/v1/reports"))
                .andExpect(jsonPath("$[3].code").value("land-tax"))
                .andExpect(jsonPath("$[3].filters[0].key").value("year"))
                .andExpect(jsonPath("$[3].filters[0].type").value("YEAR"))
                .andExpect(jsonPath("$[3].filters[0].required").value(true))
                .andExpect(jsonPath("$[3].filters[0].defaults[0].value").value(Integer.toString(current)));
    }

    @Test
    void theFileHoldsTheSameRowsAndTotalsAsNumbersWithTheRateAndAreaExact() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT).param("year", "2025")).andExpect(status().isOk()));
        byte[] bytes = send(treasurer, get(FILE).param("year", "2025").param("lang", "uk"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"land-tax_2025-01-01_2025-12-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("Земельний податок");
            Map<Integer, Row> rows = new HashMap<>();
            workbook.getFirstSheet().read().forEach(row -> rows.put(row.getRowNum() - 1, row));
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Земельний податок");
            assertThat(rows.get(2).getCellText(0)).isEqualTo("Фільтри: Рік: 2025");
            assertThat(rows.get(5).getCellText(0)).isEqualTo("Ділянок, яким нараховано не ту суму, що очікується: 1. "
                    + "Можливо, потрібен перерахунок; звіт нічого не проводить.");
            List<String> headers = new ArrayList<>();
            rows.get(7).forEach(cell -> headers.add(cell.getText()));
            assertThat(headers).containsExactly("Ділянка", "Вулиця", "Площа, м²", "Площа, сотки", "Ставка за сотку",
                    "З", "По", "Днів", "Очікується", "Нараховано за рік", "Розбіжність");
            Row plot5 = rows.get(10);
            assertThat(plot5.getCellText(0)).isEqualTo("5");
            assertNumber(plot5.getCell(2), "612", XlsxReportWriter.DECIMAL_FORMAT);
            assertNumber(plot5.getCell(3), "6.12", XlsxReportWriter.DECIMAL_FORMAT);
            assertNumber(plot5.getCell(4), "35", XlsxReportWriter.DECIMAL_FORMAT);
            assertThat(plot5.getCell(5).asDate().toLocalDate()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertNumber(plot5.getCell(7), "365", "#,##0");
            assertNumber(plot5.getCell(8), "214.2", "#,##0.00");
            assertNumber(plot5.getCell(9), "214.2", "#,##0.00");
            assertThat(plot5.getCell(10).getType()).isEqualTo(CellType.BOOLEAN);
            assertThat(plot5.getCell(10).asBoolean()).isFalse();
            assertThat(rows.get(9).getCell(10).asBoolean()).isTrue();
            Row totals = rows.get(11);
            assertThat(totals.getCellText(0)).isEqualTo("Усього");
            // the same totals as the preview
            JsonNode previewTotals = preview.get("sheets").get(0).get("totals").get(0).get("cells");
            assertNumber(totals.getCell(8), previewTotals.get(8).get("value").asString(), "#,##0.00");
            assertNumber(totals.getCell(9), previewTotals.get(9).get("value").asString(), "#,##0.00");
        }

        Map<String, Object> entry = awaitReportEntries(2).getLast();
        assertThat(changes(entry)).containsExactly("report=land-tax", "output=XLSX",
                "filters=[{\"param\":\"year\",\"value\":\"2025\"}]", "rows=3", "personalData=false");
    }

    @Test
    void aMemberAndTheElectricianAreRefused() throws Exception {
        String electrician = personWithPermissions("Електрик");
        for (String caller : List.of(member, electrician)) {
            send(caller, get(REPORT)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(caller, get(FILE)).andExpect(status().isForbidden());
        }
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    // ---- fixture and helpers

    /** Twelve monthly land tax charges of 2025, each booked in its month; returns December's. */
    private UUID monthly(UUID plot, String amount) {
        UUID last = null;
        for (int month = 1; month <= 12; month++) {
            YearMonth of = YearMonth.of(2025, month);
            last = charge(plot, landTax, "REGULAR", "TARIFF", amount, of.atDay(1).toString(),
                    of.atEndOfMonth().toString(), of.toString());
        }
        return last;
    }

    private static List<String> types(JsonNode sheet) {
        List<String> types = new ArrayList<>();
        sheet.get("columns").forEach(column -> types.add(column.get("type").asString()));
        return types;
    }

    /** Every cell of every row and the totals, an empty cell as "-". */
    private static List<String> lines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(String.join(" ", values(row.get("cells"))));
        }
        for (JsonNode total : sheet.get("totals")) {
            lines.add("TOTAL " + String.join(" ", values(total.get("cells"))));
        }
        return lines;
    }

    private static List<String> values(JsonNode cells) {
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString() : "-"));
        return values;
    }
}
