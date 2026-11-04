package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The cash book report («Касова книга», {@code cash-book}, feature 018 §2.12) through the API: the period's opening
 * row, every cash line with its kind, document, plot, counterparty and category, a day line per day with lines whose
 * closing balance is the cash balance «Каса і банк» shows for that day (the same calculator), the closing row and the
 * totals; the file in both languages; the opening balance rules.
 */
class CashBookReportTests extends TreasuryReportsSupport {

    @Test
    void everyDaysClosingBalanceIsTheCashBalanceOfThatDay() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        LocalDate opening = today().minusDays(20);
        setOpening(chairman, account(tenantA, "CASH"), "1000.00", opening).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "0.00", opening).andExpect(status().isOk());
        UUID plot = paymentPlot(tenantA);
        UUID first = pay(chairman, plot, "CASH", "500.00", today().minusDays(15));
        UUID materials = category(tenantA, "EXPENSE", "MATERIALS");
        recordExpense(chairman, with(with(expense("CASH", today().minusDays(14), "300.00", materials),
                "counterparty", "ТОВ «Будматеріали»"), "documentNumber", "Н-17"));
        recordHandover(chairman, handover(today().minusDays(10), "600.00", "КВ-5"));
        pay(chairman, plot, "CASH", "120.00", today().minusDays(10));
        reverse(chairman, first, "Помилковий платіж");

        JsonNode preview = preview(chairman, "cash-book", opening, today());
        JsonNode sheet = sheet(preview, "main");
        List<JsonNode> rows = toList(sheet.get("rows"));

        JsonNode openingRow = rows.getFirst();
        assertThat(cell(sheet, openingRow, "line")).isEqualTo("OPENING");
        assertThat(cell(sheet, openingRow, "date")).isEqualTo(opening.toString());
        assertThat(cell(sheet, openingRow, "opening")).isEqualTo("1000.00");
        JsonNode closingRow = rows.getLast();
        assertThat(cell(sheet, closingRow, "line")).isEqualTo("CLOSING");
        assertThat(cell(sheet, closingRow, "closing")).isEqualTo(balanceOn(chairman, "CASH", today()))
                .isEqualTo("220.00");

        List<JsonNode> days = rowsWith(sheet, "line", "DAY");
        assertThat(days).hasSize(4);
        for (JsonNode day : days) {
            LocalDate date = LocalDate.parse(cell(sheet, day, "date"));
            assertThat(cell(sheet, day, "closing")).as("closing of %s", date)
                    .isEqualTo(balanceOn(chairman, "CASH", date));
            assertThat(cell(sheet, day, "opening")).as("opening of %s", date)
                    .isEqualTo(balanceOn(chairman, "CASH", date.minusDays(1)));
        }

        List<JsonNode> expenses = rowsWith(sheet, "kind", "EXPENSE");
        assertThat(expenses).hasSize(1);
        JsonNode expense = expenses.getFirst();
        assertThat(cell(sheet, expense, "counterparty")).isEqualTo("ТОВ «Будматеріали»");
        assertThat(cell(sheet, expense, "document")).isEqualTo("Н-17");
        assertThat(cell(sheet, expense, "category")).isEqualTo(materials.toString());
        assertThat(cell(sheet, expense, "spending")).isEqualTo("300.00");
        assertThat(cell(sheet, expense, "receipt")).isNull();
        assertThat(cell(sheet, expense, "time")).matches("\\d{2}:\\d{2}");

        List<JsonNode> payments = rowsWith(sheet, "kind", "MEMBER_PAYMENT");
        assertThat(payments).hasSize(3);
        JsonNode payment = payments.getFirst();
        assertThat(cell(sheet, payment, "receipt")).isEqualTo("500.00");
        assertThat(cell(sheet, payment, "document")).matches("\\d+");
        assertThat(cell(sheet, payment, "plot")).startsWith("T-");
        assertThat(cell(sheet, payment, "reversal")).isEqualTo("false");
        JsonNode reversal = payments.getLast();
        assertThat(cell(sheet, reversal, "reversal")).isEqualTo("true");
        assertThat(cell(sheet, reversal, "spending")).isEqualTo("500.00");
        assertThat(cell(sheet, reversal, "document")).isEqualTo(cell(sheet, payment, "document"));
        assertThat(cell(sheet, rowsWith(sheet, "kind", "HANDOVER_OUT").getFirst(), "document")).isEqualTo("КВ-5");

        JsonNode all = total(sheet, "all");
        assertThat(cell(sheet, all, "receipt")).isEqualTo("620.00");
        assertThat(cell(sheet, all, "spending")).isEqualTo("1400.00");
        assertThat(preview.get("expenseCategories")).hasSize(1);
        assertThat(preview.get("expenseCategories").get(0).get("systemCode").asString()).isEqualTo("MATERIALS");

        for (String lang : List.of("uk", "en")) {
            reportFile(chairman, "cash-book", "from", opening.toString(), "to", today().toString(), "lang", lang)
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"cash-book_" + opening + "_" + today() + ".xlsx\""));
        }
    }

    @Test
    void aPeriodWithoutLinesHasItsOpeningAndClosingRowsOnly() throws Exception {
        String treasurer = person(tenantA, "TREASURER");
        String chairman = person(tenantA, "CHAIRMAN");
        setOpening(chairman, account(tenantA, "CASH"), "250.00", today().minusDays(3)).andExpect(status().isOk());

        JsonNode sheet = sheet(preview(treasurer, "cash-book", today().minusDays(3), today()), "main");

        assertThat(sheet.get("rows")).hasSize(2);
        assertThat(cell(sheet, sheet.get("rows").get(0), "opening")).isEqualTo("250.00");
        assertThat(cell(sheet, sheet.get("rows").get(1), "closing")).isEqualTo("250.00");
        assertThat(cell(sheet, total(sheet, "all"), "receipt")).isEqualTo("0.00");
    }

    @Test
    void theCashBookNeedsTheOpeningBalanceAndAPeriodFromItsDate() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        report(chairman, "cash-book", "from", today().minusDays(5).toString(), "to", today().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));

        setOpening(chairman, account(tenantA, "CASH"), "10.00", today().minusDays(5)).andExpect(status().isOk());
        report(chairman, "cash-book", "from", today().minusDays(6).toString(), "to", today().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        report(chairman, "cash-book", "from", today().minusDays(5).toString(), "to", today().toString())
                .andExpect(status().isOk());
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }
}
