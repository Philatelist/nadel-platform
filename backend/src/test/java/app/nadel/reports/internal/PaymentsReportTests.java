package app.nadel.reports.internal;

import static app.nadel.reports.internal.PlotBalancesReportTests.columnKeys;
import static app.nadel.reports.internal.PlotBalancesReportTests.filters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.reports.internal.PlotBalancesReportTests.Rows;
import app.nadel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The payments report (functional spec 2.11, feature 015 §6) through the report API, on payments of October and
 * November 2025 written as the database owner (day received, plot, what it pays):
 * <pre>
 * P-A  03.10.2025  cash 500.00, receipt 7, plot 1 (Лісова), payer Шевченко Тарас, note "Брат власника"
 *                  pays 300.00 of plot 1's October membership: allocated 300.00, prepayment 200.00
 * P-B  15.10.2025  bank 250.00, "ПД-55", plot 2 (Садова), no payer: prepayment 250.00
 * P-C  20.10.2025  bank 100.00, "ПД-56", plot 2, reversed
 * P-D  25.10.2025  cash 120.00, receipt 8, plot 1, no payer: prepayment 120.00
 * P-E  02.11.2025  cash 75.00, receipt 9, plot 1
 * </pre>
 * October: cash 620.00 (P-A and P-D), bank 250.00 (P-B; the reversed P-C brought no money), 870.00 in all.
 */
class PaymentsReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/payments";
    private static final String FILE = "/api/v1/reports/payments.xlsx";
    private static final String OCTOBER = "?fromMonth=2025-10&toMonth=2025-10";
    private static final String NOTE = "Брат власника";

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void book() {
        today = LocalDate.now(KYIV);
        p1 = plot("1", "Лісова");
        p2 = plot("2", "Садова");
        membership = typeId("MEMBERSHIP");
        UUID payer = owner(p1, "Шевченко", "Тарас", "Григорович", "{+380501112233}", "taras@reports.test",
                "2020-01-01", null);
        UUID october = charge(p1, membership, "REGULAR", "TARIFF", "300.00", "2025-10-01", "2025-10-31", "2025-10");

        UUID a = UUID.randomUUID();
        byte[] note = TenantContext.runAs(tenant, () -> tx.execute(status -> cipher.encrypt(tenant,
                new FieldRef("payment", "payer_note", a), NOTE)));
        payment(a, p1, "CASH", "500.00", LocalDate.of(2025, 10, 3), 7L, null, payer, note);
        allocate(a, october, "300.00");
        payment(p2, "BANK", "250.00", LocalDate.of(2025, 10, 15), null, "ПД-55", null);
        reversePayment(payment(p2, "BANK", "100.00", LocalDate.of(2025, 10, 20), null, "ПД-56", null));
        payment(p1, "CASH", "120.00", LocalDate.of(2025, 10, 25), 8L, null, null);
        payment(p1, "CASH", "75.00", LocalDate.of(2025, 11, 2), 9L, null, null);
    }

    @Test
    void theCashPaymentsOfOctoberAreOneRowEachAndTheTotalIsTheirSum() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT + OCTOBER).param("source", "CASH"))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        assertThat(columnKeys(sheet)).containsExactly("receivedOn", "billingMonth", "source", "document", "number",
                "street", "payer", "payerNote", "amount", "allocated", "prepayment", "reversed");
        assertThat(lines(sheet)).containsExactly(
                "2025-10-03 2025-10 CASH 7 1 Лісова Шевченко Тарас " + NOTE + " 500.00 300.00 200.00 false",
                "2025-10-25 2025-10 CASH 8 1 Лісова - - 120.00 0.00 120.00 false",
                "TOTAL CASH - - - - - - - 620.00 300.00 320.00 -",
                "TOTAL all - - - - - - - 620.00 300.00 320.00 -");
        assertThat(filters(preview)).containsExactly("fromMonth=2025-10", "toMonth=2025-10", "source=CASH");
        assertThat(preview.get("headerNotes")).isEmpty();
    }

    @Test
    void everySourceHasItsTotalAndAReversedPaymentIsListedButBroughtNoMoney() throws Exception {
        JsonNode sheet = body(send(treasurer, get(REPORT + OCTOBER)).andExpect(status().isOk())).get("sheets").get(0);

        assertThat(lines(sheet)).containsExactly(
                "2025-10-03 2025-10 CASH 7 1 Лісова Шевченко Тарас " + NOTE + " 500.00 300.00 200.00 false",
                "2025-10-15 2025-10 BANK ПД-55 2 Садова - - 250.00 0.00 250.00 false",
                "2025-10-20 2025-10 BANK ПД-56 2 Садова - - 100.00 0.00 0.00 true",
                "2025-10-25 2025-10 CASH 8 1 Лісова - - 120.00 0.00 120.00 false",
                "TOTAL CASH - - - - - - - 620.00 300.00 320.00 -",
                "TOTAL BANK - - - - - - - 250.00 0.00 250.00 -",
                "TOTAL all - - - - - - - 870.00 300.00 570.00 -");
    }

    @Test
    void theDaysReceivedChooseThePaymentsInsteadOfTheMonthsAndTheStreetNarrowsThem() throws Exception {
        JsonNode byDays = body(send(treasurer, get(REPORT + OCTOBER).param("from", "2025-10-10")
                .param("to", "2025-11-05")).andExpect(status().isOk()));
        assertThat(lines(byDays.get("sheets").get(0))).extracting(line -> line.substring(0, 10)).containsExactly(
                "2025-10-15", "2025-10-20", "2025-10-25", "2025-11-02", "TOTAL CASH", "TOTAL BANK", "TOTAL all ");
        assertThat(filters(byDays)).containsExactly("fromMonth=2025-10", "toMonth=2025-10", "from=2025-10-10",
                "to=2025-11-05");

        JsonNode onAStreet = body(send(treasurer, get(REPORT + OCTOBER).param("street", " садова "))
                .andExpect(status().isOk()));
        assertThat(lines(onAStreet.get("sheets").get(0))).containsExactly(
                "2025-10-15 2025-10 BANK ПД-55 2 Садова - - 250.00 0.00 250.00 false",
                "2025-10-20 2025-10 BANK ПД-56 2 Садова - - 100.00 0.00 0.00 true",
                "TOTAL CASH - - - - - - - 0.00 0.00 0.00 -",
                "TOTAL BANK - - - - - - - 250.00 0.00 250.00 -",
                "TOTAL all - - - - - - - 250.00 0.00 250.00 -");

        // one day of the two is not enough
        send(treasurer, get(REPORT).param("from", "2025-10-10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(treasurer, get(REPORT).param("from", "2025-10-10").param("to", "2025-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void thePayerNoteIsAColumnOnlyForThoseWhoMayViewMembers() throws Exception {
        String cashier = personWithPermissions("Касир", "REPORTS_VIEW", "PAYMENTS_VIEW");

        String json = send(cashier, get(REPORT + OCTOBER)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode sheet = json(json).get("sheets").get(0);
        assertThat(columnKeys(sheet)).containsExactly("receivedOn", "billingMonth", "source", "document", "number",
                "street", "payer", "amount", "allocated", "prepayment", "reversed");
        assertThat(json).doesNotContain(NOTE).contains("Шевченко Тарас");
        byte[] file = send(cashier, get(FILE + OCTOBER + "&lang=uk")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(allTexts(PlotBalancesReportTests.rowsOf(file))).doesNotContain(NOTE).contains("Платник");
    }

    @Test
    void withoutPaymentsViewTheReportIsNotListedAndRefused() throws Exception {
        String viewer = personWithPermissions("Звіти", "REPORTS_VIEW", "MEMBERS_VIEW");
        send(viewer, get("/api/v1/reports")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", not(hasItem("payments"))));
        send(viewer, get(REPORT)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(viewer, get(FILE)).andExpect(status().isForbidden());
        send(member, get(REPORT)).andExpect(status().isForbidden());

        send(treasurer, get("/api/v1/reports")).andExpect(status().isOk())
                .andExpect(jsonPath("$[8].code").value("payments"))
                .andExpect(jsonPath("$[8].personalData").value(true))
                .andExpect(jsonPath("$[8].filters[0].key").value("period"))
                .andExpect(jsonPath("$[8].filters[0].type").value("MONTH_RANGE"))
                .andExpect(jsonPath("$[8].filters[0].basis").value("BILLING_MONTHS"))
                .andExpect(jsonPath("$[8].filters[1].key").value("received"))
                .andExpect(jsonPath("$[8].filters[1].type").value("DATE_RANGE"))
                .andExpect(jsonPath("$[8].filters[1].basis").value("PAYMENT_DATES"))
                .andExpect(jsonPath("$[8].filters[1].required").value(false))
                .andExpect(jsonPath("$[8].filters[1].defaults").isEmpty())
                .andExpect(jsonPath("$[8].filters[2].key").value("source"))
                .andExpect(jsonPath("$[8].filters[2].type").value("SOURCE"))
                .andExpect(jsonPath("$[8].filters[2].params[0]").value("source"))
                .andExpect(jsonPath("$[8].filters[3].key").value("street"));
    }

    @Test
    void theFileHasEveryRowAndTheTotalsPerSourceAndTheAuditEntryIsPersonalDataWithoutContents() throws Exception {
        byte[] bytes = send(chairman, get(FILE + OCTOBER + "&lang=uk"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"payments_2025-10-01_2025-10-31.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        Rows rows = PlotBalancesReportTests.rowsOf(bytes);
        assertThat(rows.text(1, 0)).isEqualTo("Платежі");
        assertThat(rows.text(2, 0)).isEqualTo("Фільтри: Місяці нарахувань: 10.2025 – 10.2025; Дні надходження: усі; "
                + "Джерело: усі; Вулиця: усі");
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < rows.width(6); column++) {
            headers.add(rows.text(6, column));
        }
        assertThat(headers).containsExactly("Дата надходження", "Місяць обліку", "Джерело", "Квитанція / документ",
                "Ділянка", "Вулиця", "Платник", "Примітка про платника", "Сума", "Розподілено", "Аванс",
                "Сторновано");
        assertThat(rows.text(7, 2)).isEqualTo("Готівка");
        assertThat(rows.text(7, 7)).isEqualTo(NOTE);
        assertThat(rows.text(8, 2)).isEqualTo("Банк");
        // a totals line names itself in its first empty text cell, the source
        assertThat(rows.text(11, 2)).isEqualTo("Усього готівкою");
        assertThat(rows.text(12, 2)).isEqualTo("Усього через банк");
        assertThat(rows.text(13, 2)).isEqualTo("Усього");
        assertThat(rows.byIndex().get(13).getCellAsNumber(8).orElseThrow()).isEqualByComparingTo("870.00");
        assertThat(rows.byIndex().get(11).getCellAsNumber(8).orElseThrow()).isEqualByComparingTo("620.00");

        Rows english = PlotBalancesReportTests.rowsOf(send(chairman, get(FILE + OCTOBER + "&lang=en")
                .param("source", "BANK").param("from", "2025-10-01").param("to", "2025-10-31"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(english.text(2, 0)).isEqualTo("Filters: Billing months: 10.2025 – 10.2025; Days received: "
                + "01.10.2025 – 31.10.2025; Source: Bank; Street: all");
        assertThat(english.text(6, 3)).isEqualTo("Receipt or document");

        List<Map<String, Object>> entries = awaitReportEntries(2);
        for (Map<String, Object> entry : entries) {
            assertThat(entry.get("entity_label")).isEqualTo("payments");
            assertThat(changes(entry)).contains("personalData=true");
            assertThat((String) entry.get("changes")).doesNotContain(NOTE, "Шевченко", "500.00", "ПД-55");
        }
    }

    // ---- helpers

    private static String allTexts(Rows rows) {
        List<String> texts = new ArrayList<>();
        rows.byIndex().values().forEach(row -> row.forEach(cell -> texts.add(cell == null ? "" : cell.getText())));
        return String.join("|", texts);
    }

    private JsonNode json(String text) {
        return this.json.readTree(text);
    }

    /** Every cell of every row; per totals line its key and its cells after the first (the empty day received). */
    private static List<String> lines(JsonNode sheet) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            lines.add(String.join(" ", values(row.get("cells"))));
        }
        for (JsonNode total : sheet.get("totals")) {
            List<String> values = values(total.get("cells"));
            lines.add("TOTAL " + total.get("key").asString() + " " + String.join(" ", values.subList(1,
                    values.size())));
        }
        return lines;
    }

    private static List<String> values(JsonNode cells) {
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString() : "-"));
        return values;
    }
}
