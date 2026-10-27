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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Water (functional spec 2.7, feature 013 §3.5) through the report API, in a cooperative of its own, over October 2025
 * (the water use of {@code meters.internal.MeterReportsWaterAndMissingTests}, the charges in the style of
 * {@code ledger.LedgerWaterChargedTests}; October closed, November open; a well "Свердловина"):
 * <pre>
 * plot 1 Яблунева  supply all month, meter 100 -> 130 = 30 m3, 31 days
 *                  by meter 23.09-04.10 150.00 + adjustment (Nov) 30.00; fixed fee 25.00 reversed (Nov) -25.00
 *                  = by meter 180.00, fee 0.00, of which corrections 5.00, total 180.00
 * plot 2 Яблунева  well from 16.10, meter 200 -> 230: 15 m3 of those days, 16 days
 *                  by meter 450.00, fee 12.90, pump share 86.40 (the street lighting share 54.00 is not water) = 549.30
 * plot 3 Яблунева  supply until 30.09: not connected in October, not listed although charged 30.00
 * plot 4 Лісова    supply all month, meter read 01.09 only: m3 unknown since 01.09; fee 25.00
 * plot 5 Яблунева  well all month, no water meter: m3 empty; fee 25.00, pump share 86.40 = 111.40
 * plot 6 Яблунева  supply 01.10-10.10, then the well; meter 500 -> 562: 20.667 and 41.333 m3
 *                  supply row: by meter 01.10-10.10 20.00; well row: by meter 11.10-31.10 40.00, the month's fee
 *                  25.00 (21 of its days on the well), pump share 50.00 = 115.00
 * </pre>
 * Totals: 140 connected days, m3 unknown (plot 4), by meter 690.00, fees 87.90, pump shares 222.80, of which
 * corrections 5.00, 1 000.70. Per source: supply 3 plots, 72 plot-days, 225.00; the well 3 plots, 68 plot-days,
 * 56.333 m3, 775.70. Fourteen water ledger rows in all.
 */
class WaterReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/water";
    private static final String FILE = "/api/v1/reports/water.xlsx";
    private static final String FROM = "2025-10-01";
    private static final String TO = "2025-10-31";
    private static final String STREET = "Яблунева";

    @Autowired
    ReportLimits limits;

    private UUID well;

    @Test
    void everyConnectedPlotIsListedPerSourceWithItsDaysCubicMetresAndNetChargesByKind() throws Exception {
        workedWater();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        JsonNode main = preview.get("sheets").get(0);
        assertThat(main.get("key").asString()).isEqualTo("main");
        assertThat(columnKeys(main)).containsExactly("number", "street", "source", "sourceName", "connectedDays",
                "serial", "cubicMetres", "lastReading", "consumption", "fixedFee", "pumpShare", "corrections",
                "total");
        assertThat(lines(main)).containsExactly(
                "1 Яблунева COOPERATIVE_SUPPLY - 31 WS-1 30.000 - 180.00 0.00 0.00 5.00 180.00",
                // connected from 16.10: only those days and their m3
                "2 Яблунева METER Свердловина 16 WS-2 15.000 - 450.00 12.90 86.40 0.00 549.30",
                // no reading after 01.09: empty with the reason, never 0
                "4 Лісова COOPERATIVE_SUPPLY - 31 WS-4 ?NO_READING 2025-09-01 0.00 25.00 0.00 0.00 25.00",
                // no water meter: nothing measured, empty without a reason
                "5 Яблунева METER Свердловина 31 - - - 0.00 25.00 86.40 0.00 111.40",
                // the plot changed source: one row per source, each with its days and charges
                "6 Яблунева COOPERATIVE_SUPPLY - 10 WS-6 20.667 - 20.00 0.00 0.00 0.00 20.00",
                "6 Яблунева METER Свердловина 21 WS-6 41.333 - 40.00 25.00 50.00 0.00 115.00",
                "TOTAL all - - - - 140 - ?DERIVED_FROM_UNKNOWN - 690.00 87.90 222.80 5.00 1000.70");
        assertThat(filters(preview)).containsExactly("from=" + FROM, "to=" + TO);
        assertThat(notes(preview)).isEmpty();
    }

    @Test
    void aPlotNotConnectedOnAnyDayOfTheRangeIsNotListedEvenWhenCharged() throws Exception {
        workedWater();

        JsonNode main = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk())).get("sheets").get(0);

        assertThat(numbers(main)).containsExactly("1", "2", "4", "5", "6", "6").doesNotContain("3");
        // in September plot 3 was connected and plot 2 was not
        JsonNode september = body(send(chairman, get(REPORT).param("from", "2025-09-01").param("to", "2025-09-30"))
                .andExpect(status().isOk())).get("sheets").get(0);
        assertThat(numbers(september)).contains("3").doesNotContain("2");
    }

    @Test
    void theSummarySheetSumsTheRowsPerSource() throws Exception {
        workedWater();

        JsonNode summary = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk())).get("sheets").get(1);

        assertThat(summary.get("key").asString()).isEqualTo("summary");
        assertThat(columnKeys(summary)).containsExactly("source", "sourceName", "plots", "plotDays", "cubicMetres",
                "amount");
        assertThat(lines(summary)).containsExactly(
                "COOPERATIVE_SUPPLY - 3 72 ?DERIVED_FROM_UNKNOWN 225.00",
                "METER Свердловина 3 68 56.333 775.70",
                "TOTAL all - - - 140 ?DERIVED_FROM_UNKNOWN 1000.70");
    }

    @Test
    void theWellFilterListsOnlyThePlotsConnectedToAWellWithoutTheChargesOfTheirOtherSource() throws Exception {
        workedWater();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("waterSource", "METER")).andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "2 Яблунева METER Свердловина 16 WS-2 15.000 - 450.00 12.90 86.40 0.00 549.30",
                "5 Яблунева METER Свердловина 31 - - - 0.00 25.00 86.40 0.00 111.40",
                "6 Яблунева METER Свердловина 21 WS-6 41.333 - 40.00 25.00 50.00 0.00 115.00",
                "TOTAL all - - - - 68 - 56.333 - 490.00 62.90 222.80 0.00 775.70");
        assertThat(lines(preview.get("sheets").get(1))).containsExactly("METER Свердловина 3 68 56.333 775.70",
                "TOTAL all - - - 68 56.333 775.70");
        assertThat(filters(preview)).containsExactly("from=" + FROM, "to=" + TO, "waterSource=METER");

        JsonNode supply = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("waterSource", "COOPERATIVE_SUPPLY")).andExpect(status().isOk())).get("sheets").get(0);
        assertThat(numbers(supply)).containsExactly("1", "4", "6");
    }

    @Test
    void theStreetFilterKeepsThePlotsOfThatStreet() throws Exception {
        workedWater();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("street", " лісова ")).andExpect(status().isOk()));

        assertThat(numbers(preview.get("sheets").get(0))).containsExactly("4");
        assertThat(lines(preview.get("sheets").get(1))).containsExactly(
                "COOPERATIVE_SUPPLY - 1 31 ?DERIVED_FROM_UNKNOWN 25.00", "TOTAL all - - - 31 ?DERIVED_FROM_UNKNOWN 25.00");
        assertThat(filters(preview)).containsExactly("from=" + FROM, "to=" + TO, "street=лісова");
    }

    @Test
    void theFileHoldsBothSheetsWithTheSameNumbersAsTheScreenAndTheRunIsAudited() throws Exception {
        workedWater();
        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        byte[] bytes = send(chairman, get(FILE).param("from", FROM).param("to", TO).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"water_2025-10-01_2025-10-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            assertThat(workbook.getSheets().map(Sheet::getName).toList()).containsExactly("Water by plot",
                    "By source");
        }
        Map<Integer, Row> main = sheetRows(bytes, 0);
        assertThat(main.get(1).getCellText(0)).isEqualTo("Water");
        assertThat(main.get(2).getCellText(0)).isEqualTo("Filters: Service days: 01.10.2025 – 31.10.2025; "
                + "Water source: all; Street: all");
        assertThat(cells(main.get(6))).containsExactly("Plot", "Street", "Source", "Well", "Connected days",
                "Water meter", "m³", "Last reading (when unknown)", "By meter", "Fixed fee", "Well pump share",
                "Of which reversals and adjustments", "Total charged");
        Row first = main.get(7);
        assertThat(first.getCellText(2)).isEqualTo("Cooperative supply");
        assertNumber(first.getCell(4), "31", "#,##0");
        assertNumber(first.getCell(6), "30", "#,##0.000");
        assertNumber(first.getCell(8), "180", "#,##0.00");
        assertThat(main.get(8).getCellText(2)).isEqualTo("Well");
        assertThat(main.get(9).getCellText(6)).isEmpty();
        assertThat(main.get(9).getCell(7).asDate().toLocalDate()).isEqualTo(LocalDate.parse("2025-09-01"));
        // every row and the totals line, number for number
        int at = 7;
        JsonNode sheet = preview.get("sheets").get(0);
        for (JsonNode row : sheet.get("rows")) {
            assertSameNumbers(row.get("cells"), main.get(at++), List.of(4, 6, 8, 9, 10, 11, 12));
        }
        assertSameNumbers(sheet.get("totals").get(0).get("cells"), main.get(at), List.of(4, 6, 8, 9, 10, 11, 12));
        assertThat(main.get(at).getCellText(0)).isEqualTo("Total");

        Map<Integer, Row> summary = sheetRows(bytes, 1);
        assertThat(cells(summary.get(6))).containsExactly("Source", "Well", "Plots", "Connected plot-days", "m³",
                "Charged");
        at = 7;
        JsonNode bySource = preview.get("sheets").get(1);
        for (JsonNode row : bySource.get("rows")) {
            assertSameNumbers(row.get("cells"), summary.get(at++), List.of(2, 3, 4, 5));
        }
        assertSameNumbers(bySource.get("totals").get(0).get("cells"), summary.get(at), List.of(3, 4, 5));

        List<Map<String, Object>> entries = awaitReportEntries(2);
        assertThat(changes(entries.getFirst())).containsExactly("report=water", "output=PREVIEW",
                "filters=[{\"param\":\"from\",\"value\":\"2025-10-01\"},{\"param\":\"to\",\"value\":\"2025-10-31\"}]",
                "rows=8", "personalData=false");
        assertThat(changes(entries.getLast())).contains("report=water", "output=XLSX", "rows=8");
    }

    @Test
    void thePeriodIsThePreviousMonthByDefaultAndTheFiltersAreListed() throws Exception {
        YearMonth previous = YearMonth.now(KYIV).minusMonths(1);

        JsonNode preview = body(send(chairman, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("from=" + previous.atDay(1), "to=" + previous.atEndOfMonth());
        assertThat(preview.get("sheets").get(0).get("rowCount").asInt()).isZero();
        assertThat(preview.get("sheets").get(0).get("totals")).isEmpty();
        assertThat(preview.get("sheets").get(1).get("totals")).isEmpty();
        send(chairman, get(REPORT).param("from", "2025-01-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        send(chairman, get("/api/v1/reports"))
                .andExpect(jsonPath("$[4].code").value("water"))
                .andExpect(jsonPath("$[4].filters[0].basis").value("SERVICE_DAYS"))
                .andExpect(jsonPath("$[4].filters[1].key").value("waterSource"))
                .andExpect(jsonPath("$[4].filters[1].type").value("WATER_SOURCE"))
                .andExpect(jsonPath("$[4].filters[1].defaults").isEmpty())
                .andExpect(jsonPath("$[4].filters[2].key").value("street"));
    }

    @Test
    void theLedgerRowsAndTheSheetsCountAgainstTheRowCap() throws Exception {
        workedWater();
        limits.rowCap(13);
        try {
            // six rows on the main sheet, but fourteen water ledger rows behind them
            send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
            limits.rowCap(14);
            send(chairman, get(REPORT).param("from", FROM).param("to", TO)).andExpect(status().isOk());
        } finally {
            limits.rowCap(ReportLimits.DEFAULT_ROW_CAP);
        }
        assertThat(awaitReportEntries(1)).hasSize(1);
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

    // ---- the worked water use and charges of the class comment

    private void workedWater() {
        UUID p1 = plot("1", STREET);
        UUID p2 = plot("2", STREET);
        UUID p3 = plot("3", STREET);
        UUID p4 = plot("4", "Лісова");
        UUID p5 = plot("5", STREET);
        UUID p6 = plot("6", STREET);
        String installed = "2025-01-01";
        well = meter("COMMON_CONSUMER", "ELECTRICITY", null, "Свердловина", "WELL-1", installed, "0");
        UUID lighting = meter("COMMON_CONSUMER", "ELECTRICITY", null, "Освітлення", "LIGHT-1", installed, "0");

        connectWater(p1, null, installed, null);
        twoReadings(meter("PLOT", "WATER", p1, null, "WS-1", installed, "0"), "100", "130");
        connectWater(p2, well, "2025-10-16", null);
        twoReadings(meter("PLOT", "WATER", p2, null, "WS-2", installed, "0"), "200", "230");
        connectWater(p3, null, installed, "2025-09-30");
        meter("PLOT", "WATER", p3, null, "WS-3", installed, "0");
        connectWater(p4, null, installed, null);
        reading(meter("PLOT", "WATER", p4, null, "WS-4", installed, "0"), "2025-09-01", "40", "APPROVED");
        connectWater(p5, well, installed, null);
        connectWater(p6, null, installed, "2025-10-10");
        connectWater(p6, well, "2025-10-11", null);
        twoReadings(meter("PLOT", "WATER", p6, null, "WS-6", installed, "0"), "500", "562");

        UUID water = typeId("WATER");
        UUID common = typeId("COMMON_CONSUMERS");
        UUID run = UUID.randomUUID();
        ownerJdbc().update("insert into common_consumer_run (id, tenant_id, from_date, to_date, billing_month_id, "
                + "total) values (?, ?, date '2025-10-01', date '2025-10-31', ?, 276.80)", run, tenant,
                monthRow("2025-10"));

        UUID byMeter1 = waterCharge(p1, water, "CONSUMPTION", null, "150.00", "2025-09-23", "2025-10-04");
        UUID fee1 = waterCharge(p1, water, "TARIFF", null, "25.00", FROM, TO);
        waterCharge(p2, water, "CONSUMPTION", null, "450.00", "2025-10-16", TO);
        waterCharge(p2, water, "TARIFF", null, "12.90", "2025-10-16", TO);
        waterCharge(p2, common, "COMMON_CONSUMER", share(run, well, "CONNECTED_PLOTS"), "86.40", FROM, TO);
        waterCharge(p2, common, "COMMON_CONSUMER", share(run, lighting, "EQUAL_PER_PLOT"), "54.00", FROM, TO);
        waterCharge(p3, water, "CONSUMPTION", null, "30.00", FROM, TO);
        waterCharge(p4, water, "TARIFF", null, "25.00", FROM, TO);
        waterCharge(p5, water, "TARIFF", null, "25.00", FROM, TO);
        waterCharge(p5, common, "COMMON_CONSUMER", share(run, well, "CONNECTED_PLOTS"), "86.40", FROM, TO);
        waterCharge(p6, water, "CONSUMPTION", null, "20.00", FROM, "2025-10-10");
        waterCharge(p6, water, "CONSUMPTION", null, "40.00", "2025-10-11", TO);
        waterCharge(p6, water, "TARIFF", null, "25.00", FROM, TO);
        waterCharge(p6, common, "COMMON_CONSUMER", share(run, well, "CONNECTED_PLOTS"), "50.00", FROM, TO);
        // outside the range
        waterCharge(p1, water, "CONSUMPTION", null, "45.00", "2025-11-01", "2025-11-20");
        closeMonth("2025-10");
        adjustment(byMeter1, recalculation(), "30.00", "2025-11");
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, ?, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, service_to, "
                + "accrued_on, id, 'Не був підключений' from charge where id = ?", monthRow("2025-11"), fee1);
    }

    private void twoReadings(UUID meter, String first, String last) {
        reading(meter, FROM, first, "APPROVED");
        reading(meter, TO, last, "APPROVED");
    }

    /**
     * A regular water or common consumers charge booked in October (in November when it starts there), with the
     * calculation of a common consumer share ({@code run|json}).
     */
    private UUID waterCharge(UUID plot, UUID type, String source, @Nullable String share, String amount, String from,
            String to) {
        UUID id = UUID.randomUUID();
        UUID run = share == null ? null : UUID.fromString(share.substring(0, 36));
        String calculation = share == null ? null : share.substring(37);
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, run_id, calculation) values "
                + "(?, ?, ?, ?, ?, 'REGULAR', ?, ?, ?::date, ?::date, ?::date, ?, ?, ?::jsonb)", id, tenant, plot, type,
                monthRow(from.startsWith("2025-11") ? "2025-11" : "2025-10"), source, new BigDecimal(amount), from,
                to, to, "TEST:" + id, run, calculation);
        return id;
    }

    /** The run and the calculation a common consumers run stores with a share: the consumer and its split. */
    private static String share(UUID run, UUID consumer, String split) {
        return run + "|{\"consumerMeterId\": \"" + consumer + "\", \"split\": \"" + split + "\"}";
    }

    // ---- reading the answers

    /** The rows: every cell ({@code ?REASON} when unknown, {@code -} when empty), then the totals as {@code TOTAL}. */
    private static List<String> lines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(String.join(" ", values(row.get("cells"))));
        }
        for (JsonNode total : sheet.get("totals")) {
            lines.add("TOTAL " + total.get("key").asString() + " " + String.join(" ", values(total.get("cells"))));
        }
        return lines;
    }

    private static List<String> numbers(JsonNode sheet) {
        List<String> numbers = new ArrayList<>();
        sheet.get("rows").forEach(row -> numbers.add(row.get("cells").get(0).get("value").asString()));
        return numbers;
    }

    private static List<String> values(JsonNode cells) {
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString()
                : cell.has("reason") ? "?" + cell.get("reason").asString() : "-"));
        return values;
    }

    /** The rows of a worksheet by their index from 0 (the reader leaves empty rows out). */
    static Map<Integer, Row> sheetRows(byte[] bytes, int sheet) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            Map<Integer, Row> rows = new HashMap<>();
            workbook.getSheet(sheet).orElseThrow().read().forEach(row -> rows.put(row.getRowNum() - 1, row));
            return rows;
        }
    }

    static List<String> cells(Row row) {
        List<String> texts = new ArrayList<>();
        row.forEach(cell -> texts.add(cell.getText()));
        return texts;
    }

    /** Every numeric cell of the preview is the same number in the file; an empty one is empty there too. */
    static void assertSameNumbers(JsonNode cells, Row written, List<Integer> columns) {
        for (int index : columns) {
            JsonNode cell = cells.get(index);
            if (cell.has("value")) {
                assertThat(written.getCell(index).asNumber()).as("column %d", index)
                        .isEqualByComparingTo(cell.get("value").asString());
            } else {
                assertThat(written.getCellText(index)).as("column %d", index).isEmpty();
            }
        }
    }
}
