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
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Corrections (functional spec 2.10, feature 013 §3.8) through the report API, on a ledger of a cooperative of its own
 * (booked at, Kyiv time):
 * <pre>
 * January 2025 (closed)   J1  plot 1 membership 30.00, 01.01-31.01
 * February 2025 (closed)  F2  plot 2 membership 30.00, 01.02-28.02; reversed by hand on 20.02 10:00 without an author,
 *                             "Помилкове нарахування"
 * March 2025 (open)       M1  plot 1 membership 25.00, 01.03-31.03
 *                         recalculation R (cause TARIFF_VERSION, "Виправлено ставку") by the chairman: adjustment of
 *                         J1 -5.00 and reversal of M1 -25.00 (11.03 01:30 Kyiv)
 *                         losses run L reversed as a whole on 15.03 12:00 by the chairman: L1 plot 2 electricity 12.40,
 *                         01.03-14.03, reversed with it
 * </pre>
 * The regular charges are never listed. The chairman is "Звітний CHAIRMAN".
 */
class CorrectionsReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/corrections";
    private static final String FILE = "/api/v1/reports/corrections.xlsx";

    private static final String F2_REVERSAL = "2025-02-20 2025-02 2 MEMBERSHIP REVERSAL 2025-02-01 2025-02-28 30.00"
            + " -30.00 MANUAL_REVERSAL - Помилкове нарахування -";
    private static final String J1_ADJUSTMENT = "2025-03-11 2025-03 1 MEMBERSHIP ADJUSTMENT 2025-01-01 2025-01-31"
            + " 30.00 -5.00 RECALCULATION TARIFF_VERSION Виправлено ставку Звітний CHAIRMAN";
    private static final String M1_REVERSAL = "2025-03-11 2025-03 1 MEMBERSHIP REVERSAL 2025-03-01 2025-03-31 25.00"
            + " -25.00 RECALCULATION TARIFF_VERSION Виправлено ставку Звітний CHAIRMAN";
    private static final String L1_RUN_REVERSAL = "2025-03-15 2025-03 2 ELECTRICITY REVERSAL 2025-03-01 2025-03-14"
            + " 12.40 -12.40 RUN_REVERSAL - Помилковий розрахунок Звітний CHAIRMAN";

    private Map<String, String> typeCodes;

    @BeforeEach
    void book() {
        UUID p1 = plot("1", "Лісова");
        UUID p2 = plot("2", "Садова");
        membership = typeId("MEMBERSHIP");
        electricity = typeId("ELECTRICITY");
        typeCodes = Map.of(membership.toString(), "MEMBERSHIP", electricity.toString(), "ELECTRICITY");
        UUID chairmanId = memberId("CHAIRMAN");

        UUID j1 = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        UUID f2 = charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        reversal(f2, "MANUAL_REVERSAL", null, "Помилкове нарахування", null, "2025-02-20 10:00:00+02");
        UUID m1 = charge(p1, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        UUID recalculation = recalculation("TARIFF_VERSION", chairmanId);
        adjustment(j1, recalculation, "-5.00", "2025-03", chairmanId, "2025-03-10 23:30:00+00");
        reversal(m1, "RECALCULATION", recalculation, "Виправлено ставку", chairmanId, "2025-03-10 23:30:01+00");
        UUID run = reversedLossesRun("2025-03", chairmanId, "2025-03-15 12:00:00+02");
        UUID l1 = runCharge(p2, electricity, run, "12.40", "2025-03-01", "2025-03-14", "2025-03");
        reversal(l1, "MANUAL_REVERSAL", null, "Помилковий розрахунок", chairmanId, "2025-03-15 12:00:00+02");

        closeMonth("2025-01");
        closeMonth("2025-02");
    }

    @Test
    void everyCorrectionIsListedWithTheOriginalChargeSourceCauseReasonAndWhoBookedIt() throws Exception {
        JsonNode preview = body(send(chairman, get(REPORT).param("fromMonth", "2025-01").param("toMonth", "2025-04"))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        assertThat(columnKeys(sheet)).containsExactly("bookedOn", "month", "number", "chargeType", "kind",
                "originalFrom", "originalTo", "originalAmount", "amount", "source", "cause", "reason", "bookedBy");
        assertThat(lines(sheet)).containsExactly(F2_REVERSAL, J1_ADJUSTMENT, M1_REVERSAL, L1_RUN_REVERSAL,
                "TOTAL - - - - - - - - -72.40 - - - -");
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(4);
        assertThat(filters(preview)).containsExactly("fromMonth=2025-01", "toMonth=2025-04");
        assertThat(notes(preview)).isEmpty();
        assertThat(preview.get("chargeTypes")).extracting(type -> type.get("code").asString())
                .containsExactly("MEMBERSHIP", "ELECTRICITY");
    }

    @Test
    void theKindFilterListsOnlyThatKind() throws Exception {
        JsonNode reversals = body(send(chairman, get(REPORT).param("fromMonth", "2025-01").param("toMonth", "2025-04")
                .param("correctionKinds", "REVERSAL")).andExpect(status().isOk()));
        assertThat(lines(reversals.get("sheets").get(0))).containsExactly(F2_REVERSAL, M1_REVERSAL, L1_RUN_REVERSAL,
                "TOTAL - - - - - - - - -67.40 - - - -");
        assertThat(filters(reversals)).containsExactly("fromMonth=2025-01", "toMonth=2025-04",
                "correctionKinds=REVERSAL");

        JsonNode adjustments = body(send(chairman, get(REPORT).param("fromMonth", "2025-01")
                .param("toMonth", "2025-04").param("correctionKinds", "ADJUSTMENT")).andExpect(status().isOk()));
        assertThat(lines(adjustments.get("sheets").get(0))).containsExactly(J1_ADJUSTMENT,
                "TOTAL - - - - - - - - -5.00 - - - -");
    }

    @Test
    void theMonthsAreTheBillingMonthsTheCorrectionsAreBookedInAndTheChargeTypesFilterThem() throws Exception {
        JsonNode march = body(send(chairman, get(REPORT).param("fromMonth", "2025-03").param("toMonth", "2025-03"))
                .andExpect(status().isOk()));
        // the adjustment of January's charge is booked in March: listed under March
        assertThat(lines(march.get("sheets").get(0))).containsExactly(J1_ADJUSTMENT, M1_REVERSAL, L1_RUN_REVERSAL,
                "TOTAL - - - - - - - - -42.40 - - - -");

        JsonNode electricityOnly = body(send(chairman, get(REPORT).param("fromMonth", "2025-01")
                .param("toMonth", "2025-04").param("chargeTypeIds", electricity.toString()))
                .andExpect(status().isOk()));
        assertThat(lines(electricityOnly.get("sheets").get(0))).containsExactly(L1_RUN_REVERSAL,
                "TOTAL - - - - - - - - -12.40 - - - -");
    }

    @Test
    void aMonthWithoutCorrectionsGivesASheetWithoutRowsAndWithoutTotalsAndAFileWithTheHeadersOnly()
            throws Exception {
        JsonNode january = body(send(chairman, get(REPORT).param("fromMonth", "2025-01").param("toMonth", "2025-01"))
                .andExpect(status().isOk()));
        JsonNode sheet = january.get("sheets").get(0);
        assertThat(sheet.get("rows")).isEmpty();
        assertThat(sheet.get("totals")).isEmpty();
        assertThat(sheet.get("rowCount").asInt()).isZero();
        assertThat(sheet.get("truncated").asBoolean()).isFalse();

        byte[] bytes = send(chairman, get(FILE).param("fromMonth", "2025-01").param("toMonth", "2025-01")
                .param("lang", "en")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<Integer, Row> rows = rows(bytes);
        assertThat(rows.get(1).getCellText(0)).isEqualTo("Corrections");
        assertThat(rows.get(2).getCellText(0)).isEqualTo(
                "Filters: Billing months: 01.2025 – 01.2025; Charge types: all; Kinds of correction: all");
        assertThat(rows.get(6).getCellText(0)).isEqualTo("Booked on");
        assertThat(rows.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow()).isEqualTo(6);
    }

    @Test
    void byDefaultThePreviousMonthIsListed() throws Exception {
        YearMonth previous = YearMonth.from(LocalDate.now(KYIV)).minusMonths(1);

        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));

        assertThat(filters(preview)).containsExactly("fromMonth=" + previous, "toMonth=" + previous);
        assertThat(preview.get("sheets").get(0).get("rowCount").asInt()).isZero();
        send(treasurer, get("/api/v1/reports"))
                .andExpect(jsonPath("$[4].code").value("corrections"))
                .andExpect(jsonPath("$[4].personalData").value(false))
                .andExpect(jsonPath("$[4].filters[0].key").value("period"))
                .andExpect(jsonPath("$[4].filters[0].basis").value("BILLING_MONTHS"))
                .andExpect(jsonPath("$[4].filters[1].key").value("chargeTypes"))
                .andExpect(jsonPath("$[4].filters[2].key").value("correctionKinds"))
                .andExpect(jsonPath("$[4].filters[2].type").value("CORRECTION_KIND"))
                .andExpect(jsonPath("$[4].filters[2].defaults").isEmpty());
    }

    @Test
    void theFileHoldsTheSameRowsWithDatesMonthsAmountsAndTheCodesInTheLanguage() throws Exception {
        byte[] bytes = send(chairman, get(FILE).param("fromMonth", "2025-02").param("toMonth", "2025-03")
                .param("lang", "uk"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"corrections_2025-02-01_2025-03-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        Map<Integer, Row> rows = rows(bytes);
        assertThat(rows.get(1).getCellText(0)).isEqualTo("Виправлення");
        List<String> headers = new ArrayList<>();
        rows.get(6).forEach(cell -> headers.add(cell.getText()));
        assertThat(headers).containsExactly("Дата проведення", "Місяць нарахування", "Ділянка", "Вид нарахування",
                "Вид виправлення", "Послуга з", "Послуга по", "Початкова сума", "Сума", "Джерело",
                "Причина перерахунку", "Підстава", "Провів(ла)");
        Row f2 = rows.get(7);
        assertThat(f2.getCell(0).asDate().toLocalDate()).isEqualTo(LocalDate.of(2025, 2, 20));
        assertThat(f2.getCell(1).asDate().toLocalDate()).isEqualTo(LocalDate.of(2025, 2, 1));
        assertThat(f2.getCell(1).getDataFormatString()).isEqualTo("mm.yyyy");
        assertThat(f2.getCellText(2)).isEqualTo("2");
        assertThat(f2.getCellText(3)).isEqualTo("Членський внесок");
        assertThat(f2.getCellText(4)).isEqualTo("Сторно");
        assertNumber(f2.getCell(7), "30", "#,##0.00");
        assertNumber(f2.getCell(8), "-30", "#,##0.00");
        assertThat(f2.getCellText(9)).isEqualTo("Сторно вручну");
        assertThat(f2.getCellText(10)).isEmpty();
        assertThat(f2.getCellText(11)).isEqualTo("Помилкове нарахування");
        assertThat(f2.getCellText(12)).isEmpty();
        Row adjustment = rows.get(8);
        assertThat(adjustment.getCellText(4)).isEqualTo("Коригування");
        assertThat(adjustment.getCellText(9)).isEqualTo("Перерахунок");
        assertThat(adjustment.getCellText(10)).isEqualTo("Версія тарифу");
        assertThat(adjustment.getCellText(12)).isEqualTo("Звітний CHAIRMAN");
        assertThat(rows.get(10).getCellText(3)).isEqualTo("Електроенергія");
        assertThat(rows.get(10).getCellText(9)).isEqualTo("Сторно розрахунку");
        Row totals = rows.get(11);
        assertThat(totals.getCellText(2)).isEqualTo("Усього");
        assertNumber(totals.getCell(8), "-72.4", "#,##0.00");

        Map<String, Object> entry = awaitReportEntries(1).getFirst();
        assertThat(entry.get("entity_label")).isEqualTo("corrections");
        assertThat(changes(entry)).containsExactly("report=corrections", "output=XLSX",
                "filters=[{\"param\":\"fromMonth\",\"value\":\"2025-02\"},"
                        + "{\"param\":\"toMonth\",\"value\":\"2025-03\"}]",
                "rows=4", "personalData=false");
    }

    @Test
    void aMemberAndTheElectricianAreRefusedAndNothingIsAudited() throws Exception {
        String electrician = personWithPermissions("Електрик");
        for (String caller : List.of(member, electrician)) {
            send(caller, get(REPORT)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(caller, get(FILE)).andExpect(status().isForbidden());
        }
        awaitOutbox();
        assertThat(reportEntries()).isEmpty();
    }

    // ---- helpers

    /** Every cell of every row and the totals, an empty cell as "-", a charge type by its code. */
    private List<String> lines(JsonNode sheet) {
        int chargeType = columnKeys(sheet).indexOf("chargeType");
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            List<String> values = values(row.get("cells"));
            values.set(chargeType, typeCodes.getOrDefault(values.get(chargeType), values.get(chargeType)));
            lines.add(String.join(" ", values));
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

    /** The rows of the first worksheet by their index from 0 (the reader leaves empty rows out). */
    private static Map<Integer, Row> rows(byte[] workbook) throws Exception {
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(workbook),
                new ReadingOptions(true, false))) {
            Map<Integer, Row> byIndex = new HashMap<>();
            book.getFirstSheet().read().forEach(row -> byIndex.put(row.getRowNum() - 1, row));
            return byIndex;
        }
    }
}
