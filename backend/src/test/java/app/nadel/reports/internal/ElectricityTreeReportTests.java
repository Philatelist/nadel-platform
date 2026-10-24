package app.nadel.reports.internal;

import static app.nadel.reports.internal.PlotBalancesReportTests.assertNumber;
import static app.nadel.reports.internal.PlotBalancesReportTests.columnKeys;
import static app.nadel.reports.internal.PlotBalancesReportTests.filters;
import static app.nadel.reports.internal.PlotBalancesReportTests.notes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Electricity by meter tree (functional spec 2.6, feature 013 §3.4) through the report API, in a cooperative of its
 * own.
 * <p>
 * The worked tree, October 2025 (values on 01.10 and 31.10; the tree of {@code meters.internal.MeterReportsTests}):
 * <pre>
 * ТП-1 (substation)      10 000 -> 12 300 = 2 300; children unknown (quarter Б), so its losses are too
 * ├── Квартал А          5 000 -> 6 100 = 1 100; children 1 030, losses 70 = 6.36 %
 * │   ├── Освітлення     read 300 on 01.09 and 390 on 30.11: 330 -> 360 = 30, both values calculated
 * │   ├── plot 1         1 000 -> 1 600 = 600
 * │   └── plot 2         old meter 2 000 -> replaced on 15.10 at 2 150, new meter 3 -> 253: 150 + 250 = 400
 * └── Квартал Б          7 000 -> no reading after 01.10: unknown, so its consumption and losses are too
 *     └── plot 3         3 000 -> 4 000 = 1 000
 * </pre>
 * Its ledger, losses as a fixed 5 % (October closed, November open):
 * <pre>
 * C1  P1 consumption 23.09-04.10, 65 kWh at 4.3200 = 280.80 (October)
 *     A1 adjustment booked in November: 70 kWh = 302.40, so +5 kWh and +21.60
 * L1  P1 fixed-percentage losses of the same interval, 3.25 kWh at 4.3200 = 14.04 (October)
 * C2  P2 consumption 05.10-15.10, 7 kWh at 4.3250 + 1 kWh at 4.3350 = 34.61 (November), R2 reversed by hand -34.61
 * CR  common consumers run 01.10-31.10: Освітлення 25 kWh equally per plot, P1 and P3 12.5 kWh = 54.00 each
 * X3  P3 consumption 01.11-20.11, 41.00: outside the range
 * </pre>
 * The charged sheet: consumption 70 kWh and 302.40, losses 3.25 kWh and 14.04, common consumers 25 kWh and 108.00,
 * 424.44 in all: the electricity and common consumer debts of the three plots' accounts without X3.
 * <p>
 * The proportional case books its runs through the API (the fixture of {@code ledger.LedgerElectricityRunsTests},
 * September 2026, values on 31.08 and 30.09): Квартал А 1 100 kWh, P1 600, P2 400, Освітлення 50, losses 50 = 4.55 %;
 * a losses run booked, reversed and booked again (P1 30 kWh = 129.60, P2 20 kWh = 86.40) and a common consumers run
 * (Освітлення equally, 25 kWh = 108.00 each).
 */
class ElectricityTreeReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/electricity-tree";
    private static final String FILE = "/api/v1/reports/electricity-tree.xlsx";
    private static final String FROM = "2025-10-01";
    private static final String TO = "2025-10-31";

    @Autowired
    ReportLimits limits;

    private UUID e1;
    private UUID e2;
    private UUID e3;

    @Test
    void theConsumptionSheetShowsEveryNodeWithItsValuesConsumptionChildrenLossesAndPlotsFedIndentedByLevel()
            throws Exception {
        workedTree();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("consumption");
        assertThat(columnKeys(sheet)).containsExactly("name", "nodeKind", "serial", "start", "startKind",
                "startLastReading", "end", "endKind", "endLastReading", "consumption", "children", "losses",
                "lossesPercent", "plotsFed");
        assertThat(types(sheet)).containsExactly("TEXT", "CODE", "TEXT", "QUANTITY", "CODE", "DATE", "QUANTITY",
                "CODE", "DATE", "QUANTITY", "QUANTITY", "QUANTITY", "PERCENT", "INTEGER");
        assertThat(lines(sheet)).containsExactly(
                "0 ТП-1 SUBSTATION S-TP1 10000.000 REAL - 12300.000 REAL - 2300.000 ?DERIVED_FROM_UNKNOWN "
                        + "?DERIVED_FROM_UNKNOWN ?DERIVED_FROM_UNKNOWN 3",
                "1 Квартал А QUARTER S-QA 5000.000 REAL - 6100.000 REAL - 1100.000 1030.000 70.000 6.36 2",
                "2 Освітлення COMMON_CONSUMER S-L 330.000 CALCULATED - 360.000 CALCULATED - 30.000 - - - 0",
                "2 1 PLOT S-P1 1000.000 REAL - 1600.000 REAL - 600.000 - - - 1",
                // the replacement: the start read off the old meter, the end off the new one, 150 + 250 kWh
                "2 2 PLOT S-P2-NEW 2000.000 REAL - 253.000 REAL - 400.000 - - - 1",
                // no reading after 01.10: the end value and everything worked out from it are empty with a reason
                "1 Квартал Б QUARTER S-QB 7000.000 REAL - ?NO_READING UNKNOWN 2025-10-01 ?DERIVED_FROM_UNKNOWN "
                        + "1000.000 ?DERIVED_FROM_UNKNOWN ?DERIVED_FROM_UNKNOWN 1",
                "2 3 PLOT S-P3 3000.000 REAL - 4000.000 REAL - 1000.000 - - - 1");
        assertThat(sheet.get("totals")).isEmpty();
        assertThat(filters(preview)).containsExactly("from=" + FROM, "to=" + TO);
        assertThat(notes(preview)).containsExactly("LOSSES_FIXED_PERCENT");
        assertThat(preview.get("headerNotes").get(0).get("params").get("percent").asString()).isEqualTo("5.00");
    }

    @Test
    void anUnknownValueIsNeverShownAsZeroOnScreenOrInTheFile() throws Exception {
        workedTree();

        JsonNode quarterB = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk())).get("sheets").get(0).get("rows").get(5);
        for (int column : List.of(6, 9, 11, 12)) {
            JsonNode cell = quarterB.get("cells").get(column);
            assertThat(cell.has("value")).as("column %d", column).isFalse();
            assertThat(cell.get("reason").asString()).isIn("NO_READING", "DERIVED_FROM_UNKNOWN");
        }

        Map<Integer, Row> rows = sheetRows(file(chairman, "uk"), 0);
        Row b = rows.get(13);
        assertThat(b.getCellText(0)).isEqualTo("Квартал Б");
        for (int column : List.of(6, 9, 11, 12)) {
            assertThat(b.getCellText(column)).as("column %d", column).isEmpty();
        }
        assertThat(b.getCellText(7)).isEqualTo("Невідомий");
        assertThat(b.getCell(8).asDate().toLocalDate()).isEqualTo(LocalDate.parse("2025-10-01"));
        assertNumber(b.getCell(10), "1000", "#,##0.000");
    }

    @Test
    void theChargedSheetListsEachSourceWithItsCorrectionsTheRunAndTheTotals() throws Exception {
        workedTree();
        workedLedger();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(1);
        assertThat(sheet.get("key").asString()).isEqualTo("charged");
        assertThat(columnKeys(sheet)).containsExactly("source", "rowKind", "number", "serviceFrom", "serviceTo",
                "month", "kwh", "rate", "amount", "runStatus", "outcome", "split", "consumption", "children",
                "losses", "lossesPercent", "runAmount", "plots", "plotDays");
        assertThat(chargedLines(sheet)).containsExactly(
                "CONSUMPTION REGULAR 1 2025-09-23 2025-10-04 2025-10 65.000 4.3200 280.80",
                // the adjustment booked in November keeps the original service period
                "CONSUMPTION ADJUSTMENT 1 2025-09-23 2025-10-04 2025-11 5.000 4.3200 21.60",
                "CONSUMPTION REGULAR 2 2025-10-05 2025-10-15 2025-11 8.000 ?SEVERAL_RATES 34.61",
                "CONSUMPTION REVERSAL 2 2025-10-05 2025-10-15 2025-11 -8.000 ?SEVERAL_RATES -34.61",
                "SUBTOTAL CONSUMPTION - - - - - 70.000 - 302.40",
                "LOSSES REGULAR 1 2025-09-23 2025-10-04 2025-10 3.250 4.3200 14.04",
                "SUBTOTAL LOSSES - - - - - 3.250 - 14.04",
                "COMMON_CONSUMER RUN Освітлення 2025-10-01 2025-10-31 2025-10 - - - | BOOKED CHARGED "
                        + "EQUAL_PER_PLOT 25.000 - - - 108.00 2 62",
                "COMMON_CONSUMER REGULAR 1 2025-10-01 2025-10-31 2025-10 12.500 4.3200 54.00",
                "COMMON_CONSUMER REGULAR 3 2025-10-01 2025-10-31 2025-10 12.500 4.3200 54.00",
                "SUBTOTAL COMMON_CONSUMER - - - - - 25.000 - 108.00",
                "TOTAL CONSUMPTION 70.000 302.40",
                "TOTAL LOSSES 3.250 14.04",
                "TOTAL COMMON_CONSUMER 25.000 108.00",
                "TOTAL all - 424.44");
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(11);
    }

    @Test
    void theAmountsOfTheChargedSheetAreTheElectricityChargesOnThePlotAccounts() throws Exception {
        workedTree();
        workedLedger();

        JsonNode sheet = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk())).get("sheets").get(1);

        // the listed amounts add up to the total line, and only the ledger rows carry an amount
        BigDecimal listed = BigDecimal.ZERO;
        for (JsonNode row : sheet.get("rows")) {
            JsonNode amount = row.get("cells").get(8);
            if (row.get("kind").asString().equals("DETAIL") && amount.has("value")) {
                listed = listed.add(new BigDecimal(amount.get("value").asString()));
            }
        }
        BigDecimal total = new BigDecimal(totalCell(sheet, "all", 8));
        assertThat(listed).isEqualByComparingTo(total);
        // every electricity and common consumer debt of the accounts, less the charge outside the range (41.00)
        BigDecimal accounts = BigDecimal.ZERO;
        for (UUID plot : List.of(e1, e2, e3)) {
            JsonNode account = body(send(chairman, get("/api/v1/plots/" + plot + "/account"))
                    .andExpect(status().isOk()));
            accounts = accounts.add(debt(account, "ELECTRICITY")).add(debt(account, "COMMON_CONSUMERS"));
        }
        assertThat(accounts.subtract(new BigDecimal("41.00"))).isEqualByComparingTo(total)
                .isEqualByComparingTo("424.44");
    }

    @Test
    void proportionalLossesShowEveryBookedRunPerNodeAReversedRunNettingToZero() throws Exception {
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-30");
        runsBookedThroughTheApi(from, to);

        JsonNode preview = body(send(treasurer, get(REPORT).param("from", from.toString())
                .param("to", to.toString())).andExpect(status().isOk()));

        assertThat(notes(preview)).containsExactly("LOSSES_PROPORTIONAL");
        assertThat(preview.get("headerNotes").get(0).get("params").isEmpty()).isTrue();
        assertThat(chargedLines(preview.get("sheets").get(1))).containsExactly(
                "LOSSES RUN Квартал А 2026-09-01 2026-09-30 2026-09 - - - | REVERSED CHARGED - 1100.000 1050.000 "
                        + "50.000 4.55 216.00 2 -",
                "LOSSES REGULAR 1 2026-09-01 2026-09-30 2026-09 30.000 4.3200 129.60",
                "LOSSES REVERSAL 1 2026-09-01 2026-09-30 2026-09 -30.000 4.3200 -129.60",
                "LOSSES REGULAR 2 2026-09-01 2026-09-30 2026-09 20.000 4.3200 86.40",
                "LOSSES REVERSAL 2 2026-09-01 2026-09-30 2026-09 -20.000 4.3200 -86.40",
                "LOSSES RUN Квартал А 2026-09-01 2026-09-30 2026-09 - - - | BOOKED CHARGED - 1100.000 1050.000 "
                        + "50.000 4.55 216.00 2 -",
                "LOSSES REGULAR 1 2026-09-01 2026-09-30 2026-09 30.000 4.3200 129.60",
                "LOSSES REGULAR 2 2026-09-01 2026-09-30 2026-09 20.000 4.3200 86.40",
                "SUBTOTAL LOSSES - - - - - 50.000 - 216.00",
                "COMMON_CONSUMER RUN Освітлення 2026-09-01 2026-09-30 2026-09 - - - | BOOKED CHARGED "
                        + "EQUAL_PER_PLOT 50.000 - - - 216.00 2 60",
                "COMMON_CONSUMER REGULAR 1 2026-09-01 2026-09-30 2026-09 25.000 4.3200 108.00",
                "COMMON_CONSUMER REGULAR 2 2026-09-01 2026-09-30 2026-09 25.000 4.3200 108.00",
                "SUBTOTAL COMMON_CONSUMER - - - - - 50.000 - 216.00",
                "TOTAL CONSUMPTION 0.000 0.00",
                "TOTAL LOSSES 50.000 216.00",
                "TOTAL COMMON_CONSUMER 50.000 216.00",
                "TOTAL all - 432.00");
    }

    @Test
    void theFileHoldsBothSheetsWithTheSameNumbersAsTheScreen() throws Exception {
        workedTree();
        workedLedger();
        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        byte[] bytes = send(chairman, get(FILE).param("from", FROM).param("to", TO).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"electricity-tree_2025-10-01_2025-10-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            assertThat(workbook.getSheets().map(Sheet::getName).toList()).containsExactly("Consumption", "Charged");
        }
        Map<Integer, Row> tree = sheetRows(bytes, 0);
        assertThat(tree.get(1).getCellText(0)).isEqualTo("Electricity by meter tree");
        assertThat(tree.get(2).getCellText(0)).isEqualTo("Filters: Service days: 01.10.2025 – 31.10.2025");
        assertThat(tree.get(5).getCellText(0)).isEqualTo("Losses are now charged as a fixed 5.00 % of each interval. "
                + "A charge or a run booked under another mode shows what it booked.");
        assertThat(cells(tree.get(7))).containsExactly("Name or plot", "Kind", "Serial number", "Start value",
                "Start value type", "Last reading (start)", "End value", "End value type", "Last reading (end)",
                "Consumption, kWh", "Children, kWh", "Losses, kWh", "Losses, %", "Plots fed");
        Row quarterA = tree.get(9);
        assertThat(quarterA.getCellText(0)).isEqualTo("Квартал А");
        assertThat(quarterA.getCellText(1)).isEqualTo("Quarter");
        assertNumber(quarterA.getCell(3), "5000", "#,##0.000");
        assertThat(quarterA.getCellText(4)).isEqualTo("Real");
        assertNumber(quarterA.getCell(9), "1100", "#,##0.000");
        assertNumber(quarterA.getCell(10), "1030", "#,##0.000");
        assertNumber(quarterA.getCell(11), "70", "#,##0.000");
        assertNumber(quarterA.getCell(12), "6.36", "0.00");
        assertNumber(quarterA.getCell(13), "2", "#,##0");

        Map<Integer, Row> charged = sheetRows(bytes, 1);
        JsonNode sheet = preview.get("sheets").get(1);
        assertThat(charged.get(7).getCellText(0)).isEqualTo("Source");
        // every row and total line of the preview, number for number
        int at = 8;
        for (JsonNode row : sheet.get("rows")) {
            Row written = charged.get(at++);
            assertSameNumbers(row.get("cells"), written);
        }
        for (JsonNode total : sheet.get("totals")) {
            Row written = charged.get(at++);
            assertSameNumbers(total.get("cells"), written);
        }
        Row adjustment = charged.get(9);
        assertThat(adjustment.getCellText(0)).isEqualTo("Consumption");
        assertThat(adjustment.getCellText(1)).isEqualTo("Adjustment");
        assertThat(adjustment.getCell(3).asDate().toLocalDate()).isEqualTo(LocalDate.parse("2025-09-23"));
        assertNumber(adjustment.getCell(6), "5", "#,##0.000");
        assertNumber(adjustment.getCell(7), "4.32", XlsxReportWriter.DECIMAL_FORMAT);
        assertNumber(adjustment.getCell(8), "21.6", "#,##0.00");
        assertThat(charged.get(10).getCellText(7)).isEmpty();
        assertNumber(charged.get(11).getCell(6), "-8", "#,##0.000");
        assertThat(charged.get(12).getCellText(1)).isEqualTo("Subtotal");
        assertThat(charged.get(15).getCellText(1)).isEqualTo("Run");
        assertThat(charged.get(15).getCellText(11)).isEqualTo("Equally per plot");
        assertThat(charged.get(19).getCellText(0)).isEqualTo("Consumption of plots");
        assertThat(charged.get(22).getCellText(0)).isEqualTo("Total");

        List<Map<String, Object>> entries = awaitReportEntries(2);
        assertThat(changes(entries.getFirst())).containsExactly("report=electricity-tree", "output=PREVIEW",
                "filters=[{\"param\":\"from\",\"value\":\"2025-10-01\"},{\"param\":\"to\",\"value\":\"2025-10-31\"}]",
                "rows=18", "personalData=false");
        assertThat(changes(entries.getLast())).contains("output=XLSX", "rows=18");
    }

    @Test
    void thePeriodIsThePreviousMonthByDefaultAndARangeBackwardsOrOverAYearIsRefused() throws Exception {
        YearMonth previous = YearMonth.now(KYIV).minusMonths(1);

        JsonNode preview = body(send(chairman, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("from=" + previous.atDay(1), "to=" + previous.atEndOfMonth());
        assertThat(preview.get("sheets").get(0).get("rowCount").asInt()).isZero();
        assertThat(preview.get("sheets").get(1).get("totals")).isEmpty();
        for (String[] range : List.of(new String[] {TO, FROM}, new String[] {"2025-01-01", "2026-01-01"})) {
            send(chairman, get(REPORT).param("from", range[0]).param("to", range[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("to"))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        }
        send(chairman, get("/api/v1/reports"))
                .andExpect(jsonPath("$[3].code").value("electricity-tree"))
                .andExpect(jsonPath("$[3].personalData").value(false))
                .andExpect(jsonPath("$[3].filters[0].key").value("period"))
                .andExpect(jsonPath("$[3].filters[0].type").value("DATE_RANGE"))
                .andExpect(jsonPath("$[3].filters[0].basis").value("SERVICE_DAYS"))
                .andExpect(jsonPath("$[3].filters[0].params[0]").value("from"))
                .andExpect(jsonPath("$[3].filters[0].defaults[1].value").value(previous.atEndOfMonth().toString()));
    }

    @Test
    void aReportOverTheRowCapIsRefusedAndNotAudited() throws Exception {
        workedTree();
        workedLedger();
        limits.rowCap(10);
        try {
            // the tree has 7 rows, the charged sheet 11
            send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
            limits.rowCap(11);
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

    // ---- the worked tree and ledger of the class comment

    private UUID substation;
    private UUID quarterA;
    private UUID quarterB;
    private UUID lighting;
    private UUID meterP1;
    private UUID oldP2;
    private UUID meterP3;

    private void workedTree() {
        e1 = plot("1", "Яблунева");
        e2 = plot("2", "Яблунева");
        e3 = plot("3", "Яблунева");
        substation = named("SUBSTATION", "ТП-1", "S-TP1", null);
        read(substation, "2025-10-01", "10000");
        read(substation, "2025-10-31", "12300");
        quarterA = named("QUARTER", "Квартал А", "S-QA", substation);
        read(quarterA, "2025-10-01", "5000");
        read(quarterA, "2025-10-31", "6100");
        quarterB = named("QUARTER", "Квартал Б", "S-QB", substation);
        read(quarterB, "2025-10-01", "7000");
        lighting = named("COMMON_CONSUMER", "Освітлення", "S-L", quarterA);
        read(lighting, "2025-09-01", "300");
        read(lighting, "2025-11-30", "390");
        meterP1 = plotMeter(e1, quarterA, "S-P1", "2025-01-01", null);
        read(meterP1, "2025-10-01", "1000");
        read(meterP1, "2025-10-31", "1600");
        oldP2 = plotMeter(e2, quarterA, "S-P2", "2025-01-01", null);
        read(oldP2, "2025-10-01", "2000");
        ownerJdbc().update("update meter set removed_on = date '2025-10-15', final_value = 2150 where id = ?", oldP2);
        ownerJdbc().update("update meter_parent set valid_to = date '2025-10-15' where meter_id = ?", oldP2);
        UUID newP2 = plotMeter(e2, quarterA, "S-P2-NEW", "2025-10-15", oldP2);
        read(newP2, "2025-10-31", "253");
        meterP3 = plotMeter(e3, quarterB, "S-P3", "2025-01-01", null);
        read(meterP3, "2025-10-01", "3000");
        read(meterP3, "2025-10-31", "4000");
        ownerJdbc().update("insert into cooperative_settings (tenant_id, losses_mode, losses_percent) "
                + "values (?, 'FIXED_PERCENT', 5.00)", tenant);
    }

    private void workedLedger() {
        UUID electricityType = typeId("ELECTRICITY");
        UUID commonType = typeId("COMMON_CONSUMERS");
        UUID power = tariff(electricityType, "Електроенергія");
        UUID v4320 = perKwh(power, "4.3200", "2025-01-01");
        UUID v4325 = perKwh(power, "4.3250", "2025-10-05");
        UUID v4335 = perKwh(power, "4.3350", "2025-10-12");
        UUID common = perKwh(tariff(commonType, "Спільні споживачі"), "4.3200", "2025-01-01");

        UUID c1 = ledgerCharge(e1, electricityType, "CONSUMPTION", meterP1, null, "280.80", "2025-09-23",
                "2025-10-04", "2025-10", "2025-10-05 10:00:00+03");
        part(c1, v4320, 12, "65", "1", "4.3200");
        UUID l1 = ledgerCharge(e1, electricityType, "LOSSES", meterP1, null, "14.04", "2025-09-23", "2025-10-04",
                "2025-10", "2025-10-05 10:00:00+03");
        part(l1, v4320, 12, "13", "4", "4.3200");
        UUID run = UUID.randomUUID();
        ownerJdbc().update("insert into common_consumer_run (id, tenant_id, from_date, to_date, billing_month_id, "
                + "total) values (?, ?, date '2025-10-01', date '2025-10-31', ?, 108.00)", run, tenant,
                monthRow("2025-10"));
        ownerJdbc().update("insert into common_consumer_run_line (tenant_id, run_id, consumer_meter_id, "
                + "consumer_name, from_date, to_date, position, split, outcome, consumption, plot_days, amount, "
                + "plots) values (?, ?, ?, 'Освітлення', date '2025-10-01', date '2025-10-31', 0, 'EQUAL_PER_PLOT', "
                + "'CHARGED', 25.000, 62, 108.00, cast(? as jsonb))", tenant, run, lighting,
                "[{\"plotId\":\"" + e1 + "\"},{\"plotId\":\"" + e3 + "\"}]");
        for (UUID plot : List.of(e1, e3)) {
            UUID share = ledgerCharge(plot, commonType, "COMMON_CONSUMER", null, run, "54.00", "2025-10-01",
                    "2025-10-31", "2025-10", "2025-10-31 18:00:00+02");
            part(share, common, 31, "25", "2", "4.3200");
        }
        UUID c2 = ledgerCharge(e2, electricityType, "CONSUMPTION", oldP2, null, "34.61", "2025-10-05", "2025-10-15",
                "2025-11", "2025-11-01 09:00:00+02");
        part(c2, v4325, 7, "7", "1", "4.3250");
        part(c2, v4335, 4, "1", "1", "4.3350");
        ledgerCharge(e3, electricityType, "CONSUMPTION", meterP3, null, "41.00", "2025-11-01", "2025-11-20",
                "2025-11", "2025-11-21 09:00:00+02");
        closeMonth("2025-10");
        UUID recalculation = recalculation();
        UUID a1 = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, meter_id, adjusts_charge_id, "
                + "recalculation_id, reason, created_at) select ?, tenant_id, plot_id, charge_type_id, ?, "
                + "'ADJUSTMENT', 'RECALCULATION', 21.60, service_from, service_to, accrued_on, ?, meter_id, id, ?, "
                + "'Виправлено покази', timestamptz '2025-11-03 10:00:00+02' from charge where id = ?", a1,
                monthRow("2025-11"), "ADJUSTMENT:" + recalculation + ":" + c1, recalculation, c1);
        part(a1, v4320, 12, "70", "1", "4.3200");
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, meter_id, reason, created_at) "
                + "select tenant_id, plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', "
                + "-amount, service_from, service_to, accrued_on, id, meter_id, 'Помилкові покази', "
                + "timestamptz '2025-11-02 09:00:00+02' from charge where id = ?", c2);
    }

    /**
     * The proportional case: quarter, plots, lighting and readings as the database owner, the runs booked through the
     * API as the chairman.
     */
    private void runsBookedThroughTheApi(LocalDate from, LocalDate to) throws Exception {
        ownerJdbc().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')",
                tenant);
        perKwh(tariff(typeId("ELECTRICITY"), "Електроенергія"), "4.3200", "2026-01-01");
        perKwh(tariff(typeId("COMMON_CONSUMERS"), "Спільні споживачі"), "4.3200", "2026-01-01");
        UUID r1 = plot("1", "Яблунева");
        UUID r2 = plot("2", "Яблунева");
        String before = from.minusDays(1).toString();
        UUID quarter = named("QUARTER", "Квартал А", "R-QA", null, "2026-01-01");
        readTwice(quarter, before, "5000", to.toString(), "6100");
        readTwice(plotMeter(r1, quarter, "R-P1", "2026-01-01", null), before, "1000", to.toString(), "1600");
        readTwice(plotMeter(r2, quarter, "R-P2", "2026-01-01", null), before, "2000", to.toString(), "2400");
        UUID light = named("COMMON_CONSUMER", "Освітлення", "R-L", quarter, "2026-01-01");
        readTwice(light, before, "300", to.toString(), "350");
        send(chairman, put("/api/v1/common-consumers/" + light + "/billing").contentType(MediaType.APPLICATION_JSON)
                .content("{\"split\":\"EQUAL_PER_PLOT\",\"servicePlotIds\":[]}")).andExpect(status().isOk());

        UUID first = book("/api/v1/losses-runs", from, to);
        send(chairman, post("/api/v1/losses-runs/" + first + "/reverse").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Помилкові покази\"}")).andExpect(status().isOk());
        book("/api/v1/losses-runs", from, to);
        book("/api/v1/common-consumer-runs", from, to);
    }

    private UUID book(String runs, LocalDate from, LocalDate to) throws Exception {
        return UUID.fromString(body(send(chairman, post(runs + "?preview=false")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}")).andExpect(status().isOk()))
                .get("runId").asString());
    }

    // ---- fixtures, written as the database owner

    private UUID named(String kind, String name, String serial, @Nullable UUID parent) {
        return named(kind, name, serial, parent, "2025-01-01");
    }

    private UUID named(String kind, String name, String serial, @Nullable UUID parent, String installedOn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?::date, 0)", id, tenant, kind, serial, name,
                installedOn);
        place(id, parent, installedOn);
        return id;
    }

    private UUID plotMeter(UUID plot, UUID parent, String serial, String installedOn, @Nullable UUID replaces) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value, replaces_meter_id) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, ?::date, ?, ?)", id,
                tenant, serial, plot, installedOn, new BigDecimal(replaces == null ? "0" : "3"), replaces);
        place(id, parent, installedOn);
        return id;
    }

    private void place(UUID meter, @Nullable UUID parent, String from) {
        if (parent != null) {
            ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, meter, parent, from);
        }
    }

    private void read(UUID meter, String on, String value) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }

    private void readTwice(UUID meter, String on, String value, String later, String laterValue) {
        read(meter, on, value);
        read(meter, later, laterValue);
    }

    /** A monthly per-kWh version of the tariff. */
    private UUID perKwh(UUID tariff, String rate, String validFrom) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, ?::date, 'PER_KWH', 'MONTHLY', ?)", id, tenant, tariff, validFrom,
                new BigDecimal(rate));
        return id;
    }

    /** A regular electricity charge, debt dated on its last service day, booked at {@code at}. */
    private UUID ledgerCharge(UUID plot, UUID type, String source, @Nullable UUID meter, @Nullable UUID run,
            String amount, String from, String to, String bookedIn, String at) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, meter_id, run_id, created_at) "
                + "values (?, ?, ?, ?, ?, 'REGULAR', ?, ?, ?::date, ?::date, ?::date, ?, ?, ?, ?::timestamptz)", id,
                tenant, plot, type, monthRow(bookedIn), source, new BigDecimal(amount), from, to, to, "TEST:" + id,
                meter, run, at);
        return id;
    }

    private void part(UUID charge, UUID version, int days, String numerator, String denominator, String rate) {
        ownerJdbc().update("insert into charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, "
                + "units_den, rate) values (?, ?, ?, ?, ?, ?, ?)", tenant, charge, version, days,
                new BigDecimal(numerator), new BigDecimal(denominator), new BigDecimal(rate));
    }

    // ---- reading the answers

    private byte[] file(String bearer, String lang) throws Exception {
        return send(bearer, get(FILE).param("from", FROM).param("to", TO).param("lang", lang))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** The rows of a worksheet by their index from 0 (the reader leaves empty rows out). */
    private static Map<Integer, Row> sheetRows(byte[] bytes, int sheet) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            Map<Integer, Row> rows = new HashMap<>();
            workbook.getSheet(sheet).orElseThrow().read().forEach(row -> rows.put(row.getRowNum() - 1, row));
            return rows;
        }
    }

    private static List<String> cells(Row row) {
        List<String> texts = new ArrayList<>();
        row.forEach(cell -> texts.add(cell.getText()));
        return texts;
    }

    /** Every numeric cell of the preview is the same number in the file; an empty one is empty there too. */
    private static void assertSameNumbers(JsonNode cells, Row written) {
        for (int index : List.of(6, 8, 12, 16, 17, 18)) {
            JsonNode cell = cells.get(index);
            if (cell.has("value")) {
                assertThat(written.getCell(index).asNumber()).as("column %d", index)
                        .isEqualByComparingTo(cell.get("value").asString());
            } else {
                assertThat(written.getCellText(index)).as("column %d", index).isEmpty();
            }
        }
    }

    private static List<String> types(JsonNode sheet) {
        List<String> types = new ArrayList<>();
        sheet.get("columns").forEach(column -> types.add(column.get("type").asString()));
        return types;
    }

    /** {@code level} and every cell of a tree row: a value, {@code ?REASON} when unknown, {@code -} when empty. */
    private static List<String> lines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(row.get("level").asInt() + " " + String.join(" ", values(row.get("cells"), 0, 14)));
        }
        return lines;
    }

    /**
     * The rows of the charged sheet: the ledger columns, then {@code |} and the run columns for a run row; a subtotal
     * as {@code SUBTOTAL}, the totals as {@code TOTAL key kwh amount}.
     */
    private static List<String> chargedLines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            List<String> values = values(row.get("cells"), 0, 9);
            String line = String.join(" ", values);
            if (row.get("kind").asString().equals("SUBTOTAL")) {
                line = "SUBTOTAL " + line;
            } else if ("RUN".equals(values.get(1))) {
                line += " | " + String.join(" ", values(row.get("cells"), 9, 19));
            }
            lines.add(line);
        }
        for (JsonNode total : sheet.get("totals")) {
            List<String> values = values(total.get("cells"), 0, 9);
            lines.add("TOTAL " + total.get("key").asString() + " " + values.get(6) + " " + values.get(8));
        }
        return lines;
    }

    private static List<String> values(JsonNode cells, int from, int to) {
        List<String> values = new ArrayList<>();
        for (int index = from; index < to; index++) {
            JsonNode cell = cells.get(index);
            values.add(cell.has("value") ? cell.get("value").asString()
                    : cell.has("reason") ? "?" + cell.get("reason").asString() : "-");
        }
        return values;
    }

    private static String totalCell(JsonNode sheet, String key, int column) {
        for (JsonNode total : sheet.get("totals")) {
            if (total.get("key").asString().equals(key)) {
                return total.get("cells").get(column).get("value").asString();
            }
        }
        throw new AssertionError("No total " + key);
    }

    private static BigDecimal debt(JsonNode account, String code) {
        for (JsonNode debt : account.get("debts")) {
            if (code.equals(debt.get("chargeTypeCode").asString())) {
                return new BigDecimal(debt.get("debt").asString());
            }
        }
        return BigDecimal.ZERO;
    }
}
