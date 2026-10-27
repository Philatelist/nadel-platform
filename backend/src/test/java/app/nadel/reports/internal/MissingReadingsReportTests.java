package app.nadel.reports.internal;

import static app.nadel.reports.internal.PlotBalancesReportTests.assertNumber;
import static app.nadel.reports.internal.PlotBalancesReportTests.columnKeys;
import static app.nadel.reports.internal.PlotBalancesReportTests.filters;
import static app.nadel.reports.internal.PlotBalancesReportTests.notes;
import static app.nadel.reports.internal.WaterReportTests.cells;
import static app.nadel.reports.internal.WaterReportTests.sheetRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Missing readings (functional spec 2.8, feature 013 §3.6) through the report API, in a cooperative of its own, over
 * October 2025 (the meters of {@code meters.internal.MeterReportsWaterAndMissingTests}):
 * <pre>
 * plot 1 Яблунева  electricity read 15.10, water read 01.10 and 31.10                  not listed (read in the range)
 * plot 2 Яблунева  electricity read 10.09 (1 000), a member's reading of 20.10 pending listed, waiting, 51 days;
 *                  two open "not charged" interval items and a resolved one
 * plot 3 Яблунева  water supply until 30.09, water meter never read                      not listed (not connected)
 *                  electricity meter installed 05.11                                     not listed (after the range)
 * plot 4 Лісова    water read 01.09 (40), connected                                      listed, 60 days
 *                  electricity meter removed 20.09                                       not listed (before the range)
 * plot 5 Яблунева  electricity initial value 7 on 01.01, never read                      listed, initial, 303 days;
 *                  one open "not charged" interval item
 * plot 6 Яблунева  electricity meter installed 10.10                                     not listed (a point in the range)
 * Квартал А        quarter meter never read                                              only with its kind chosen
 * Свердловина      common consumer (well pump) never read                                only with its kind chosen
 * </pre>
 */
class MissingReadingsReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/missing-readings";
    private static final String FILE = "/api/v1/reports/missing-readings.xlsx";
    private static final String FROM = "2025-10-01";
    private static final String TO = "2025-10-31";
    private static final String STREET = "Яблунева";
    private static final String INSTALLED = "2025-01-01";

    @Autowired
    ReportLimits limits;

    @Test
    void thePlotMetersWithoutAnApprovedReadingInTheRangeAreListedByDefault() throws Exception {
        workedMeters();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        assertThat(columnKeys(sheet)).containsExactly("resource", "kind", "name", "street", "serial",
                "lastReadingOn", "lastReadingValue", "initialValue", "daysSince", "pending");
        assertThat(lines(sheet)).containsExactly(
                "WATER PLOT 4 Лісова M-W4 2025-09-01 40.000 false 60 false",
                // only a reading waiting for approval: listed and marked
                "ELECTRICITY PLOT 2 Яблунева M-E2 2025-09-10 1000.000 false 51 true",
                "ELECTRICITY PLOT 5 Яблунева M-E5 2025-01-01 7.000 true 303 false");
        assertThat(sheet.get("totals")).isEmpty();
        assertThat(filters(preview)).containsExactly("from=" + FROM, "to=" + TO, "meterKinds=PLOT");
    }

    @Test
    void readMetersUnconnectedWaterMetersAndMetersOutsideTheRangeAreNotListed() throws Exception {
        workedMeters();

        JsonNode sheet = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("meterKinds", "PLOT", "SUBSTATION", "QUARTER", "COMMON_CONSUMER"))
                .andExpect(status().isOk())).get("sheets").get(0);

        List<String> serials = new ArrayList<>();
        sheet.get("rows").forEach(row -> serials.add(row.get("cells").get(4).get("value").asString()));
        // M-E1, M-W1 read in the range; M-W3 not connected; M-E3 after, M-E4 before, M-E6 within the range
        assertThat(serials).containsExactly("M-W4", "M-E2", "M-E5", "M-QA", "M-WELL")
                .doesNotContain("M-E1", "M-W1", "M-W3", "M-E3", "M-E4", "M-E6");
    }

    @Test
    void quarterMetersAndCommonConsumersAreListedOnlyWhenChosenByNameWithoutAStreet() throws Exception {
        workedMeters();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("meterKinds", "QUARTER", "COMMON_CONSUMER")).andExpect(status().isOk()));

        assertThat(lines(preview.get("sheets").get(0))).containsExactly(
                "ELECTRICITY QUARTER Квартал А - M-QA 2025-01-01 0.000 true 303 false",
                "ELECTRICITY COMMON_CONSUMER Свердловина - M-WELL 2025-01-01 0.000 true 303 false");
        // no plot meter listed: no "not charged" items to count
        assertThat(notes(preview)).isEmpty();
    }

    @Test
    void theResourceAndStreetFiltersNarrowTheList() throws Exception {
        workedMeters();

        JsonNode water = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("resource", "WATER")).andExpect(status().isOk()));
        assertThat(names(water)).containsExactly("4");
        assertThat(filters(water)).containsExactly("from=" + FROM, "to=" + TO, "resource=WATER", "meterKinds=PLOT");

        JsonNode street = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("street", STREET).param("meterKinds", "PLOT", "QUARTER")).andExpect(status().isOk()));
        // a street keeps the plot meters of its plots; the named meters have no plot
        assertThat(names(street)).containsExactly("2", "5");
    }

    @Test
    void theHeaderCountsTheOpenNotChargedIntervalItemsOfTheListedMeters() throws Exception {
        workedMeters();

        JsonNode preview = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()));
        assertThat(notes(preview)).containsExactly("OPEN_NOT_CHARGED");
        // two of plot 2's meter and one of plot 5's; the resolved one does not count
        assertThat(preview.get("headerNotes").get(0).get("params").get("count").asString()).isEqualTo("3");

        // only plot 4's water meter listed: it has no item, so no note
        JsonNode water = body(send(chairman, get(REPORT).param("from", FROM).param("to", TO)
                .param("resource", "WATER")).andExpect(status().isOk()));
        assertThat(notes(water)).isEmpty();
    }

    @Test
    void theFileSaysTheSameAsTheScreen() throws Exception {
        workedMeters();

        byte[] bytes = send(chairman, get(FILE).param("from", FROM).param("to", TO).param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"missing-readings_2025-10-01_2025-10-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        Map<Integer, Row> rows = sheetRows(bytes, 0);
        assertThat(rows.get(1).getCellText(0)).isEqualTo("Missing readings");
        assertThat(rows.get(2).getCellText(0)).isEqualTo("Filters: Reading dates: 01.10.2025 – 31.10.2025; "
                + "Resource: all; Meter kinds: Plot; Street: all");
        assertThat(rows.get(5).getCellText(0)).isEqualTo("Open “not charged” items of the listed meters: 3. "
                + "They wait in the “Not charged” list.");
        assertThat(cells(rows.get(7))).containsExactly("Resource", "Kind", "Plot or meter", "Street",
                "Serial number", "Last reading", "Last value", "Initial value of the installation", "Days since",
                "Reading waiting for approval");
        Row plot2 = rows.get(9);
        assertThat(plot2.getCellText(0)).isEqualTo("Electricity");
        assertThat(plot2.getCellText(1)).isEqualTo("Plot");
        assertThat(plot2.getCellText(2)).isEqualTo("2");
        assertThat(plot2.getCell(5).asDate().toLocalDate()).isEqualTo(LocalDate.parse("2025-09-10"));
        assertNumber(plot2.getCell(6), "1000", "#,##0.000");
        assertNumber(plot2.getCell(8), "51", "#,##0");
        assertThat(plot2.getCellText(9)).isNotEmpty();
        assertThat(rows.get(8).getCellText(0)).isEqualTo("Water");

        List<Map<String, Object>> entries = awaitReportEntries(1);
        assertThat(changes(entries.getFirst())).containsExactly("report=missing-readings", "output=XLSX",
                "filters=[{\"param\":\"from\",\"value\":\"2025-10-01\"},{\"param\":\"to\",\"value\":\"2025-10-31\"},"
                        + "{\"param\":\"meterKinds\",\"value\":\"PLOT\"}]",
                "rows=3", "personalData=false");
    }

    @Test
    void thePeriodIsThePreviousMonthByDefaultAndPlotMetersTheDefaultKind() throws Exception {
        YearMonth previous = YearMonth.now(KYIV).minusMonths(1);

        JsonNode preview = body(send(chairman, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("from=" + previous.atDay(1), "to=" + previous.atEndOfMonth(),
                "meterKinds=PLOT");
        send(chairman, get("/api/v1/reports"))
                .andExpect(jsonPath("$[5].code").value("missing-readings"))
                .andExpect(jsonPath("$[5].filters[0].basis").value("READING_DATES"))
                .andExpect(jsonPath("$[5].filters[1].key").value("resource"))
                .andExpect(jsonPath("$[5].filters[2].key").value("meterKinds"))
                .andExpect(jsonPath("$[5].filters[2].defaults[0].value").value("PLOT"))
                .andExpect(jsonPath("$[5].filters[3].key").value("street"));
    }

    @Test
    void aReportOverTheRowCapIsRefusedAndNotAudited() throws Exception {
        workedMeters();
        limits.rowCap(2);
        try {
            send(chairman, get(REPORT).param("from", FROM).param("to", TO))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
            limits.rowCap(3);
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

    // ---- the worked meters of the class comment

    private void workedMeters() {
        UUID p1 = plot("1", STREET);
        UUID p2 = plot("2", STREET);
        UUID p3 = plot("3", STREET);
        UUID p4 = plot("4", "Лісова");
        UUID p5 = plot("5", STREET);
        UUID p6 = plot("6", STREET);
        meter("COMMON_CONSUMER", "ELECTRICITY", null, "Свердловина", "M-WELL", INSTALLED, "0");
        meter("QUARTER", "ELECTRICITY", null, "Квартал А", "M-QA", INSTALLED, "0");

        connectWater(p1, null, INSTALLED, null);
        UUID w1 = meter("PLOT", "WATER", p1, null, "M-W1", INSTALLED, "0");
        reading(w1, FROM, "100", "APPROVED");
        reading(w1, TO, "130", "APPROVED");
        reading(meter("PLOT", "ELECTRICITY", p1, null, "M-E1", INSTALLED, "0"), "2025-10-15", "50", "APPROVED");

        UUID e2 = meter("PLOT", "ELECTRICITY", p2, null, "M-E2", INSTALLED, "0");
        reading(e2, "2025-09-10", "1000", "APPROVED");
        reading(e2, "2025-10-20", "1100", "PENDING");

        connectWater(p3, null, INSTALLED, "2025-09-30");
        meter("PLOT", "WATER", p3, null, "M-W3", INSTALLED, "0");
        meter("PLOT", "ELECTRICITY", p3, null, "M-E3", "2025-11-05", "0");

        connectWater(p4, null, INSTALLED, null);
        reading(meter("PLOT", "WATER", p4, null, "M-W4", INSTALLED, "0"), "2025-09-01", "40", "APPROVED");
        UUID e4 = meter("PLOT", "ELECTRICITY", p4, null, "M-E4", INSTALLED, "0");
        ownerJdbc().update("update meter set removed_on = date '2025-09-20', final_value = 90 where id = ?", e4);

        UUID e5 = meter("PLOT", "ELECTRICITY", p5, null, "M-E5", INSTALLED, "7");
        meter("PLOT", "ELECTRICITY", p6, null, "M-E6", "2025-10-10", "0");

        UUID electricityType = typeId("ELECTRICITY");
        notCharged(p2, electricityType, e2, "2025-09-01", "2025-09-30", "OPEN");
        notCharged(p2, electricityType, e2, "2025-10-01", "2025-10-31", "OPEN");
        notCharged(p2, electricityType, e2, "2025-08-01", "2025-08-31", "RESOLVED");
        notCharged(p5, electricityType, e5, "2025-10-01", "2025-10-31", "OPEN");
    }

    /** An item of kind {@code INTERVAL} of the "not charged" queue. */
    private void notCharged(UUID plot, UUID chargeType, UUID meter, String from, String to, String state) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into not_charged_item (id, tenant_id, item_key, kind, plot_id, charge_type_id, "
                + "meter_id, from_date, to_date, reason, status, resolved_at) values (?, ?, ?, 'INTERVAL', ?, ?, ?, "
                + "?::date, ?::date, 'NO_TARIFF_FOR_ALL_DAYS', ?, case when ? = 'OPEN' then null else now() end)",
                id, tenant, "TEST:" + id, plot, chargeType, meter, from, to, state, state);
    }

    // ---- reading the answers

    private static List<String> lines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            List<String> values = new ArrayList<>();
            row.get("cells").forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString() : "-"));
            lines.add(String.join(" ", values));
        }
        return lines;
    }

    private static List<String> names(JsonNode preview) {
        List<String> names = new ArrayList<>();
        preview.get("sheets").get(0).get("rows").forEach(row -> names.add(row.get("cells").get(2).get("value")
                .asString()));
        return names;
    }
}
