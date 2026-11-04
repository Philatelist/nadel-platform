package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Income and expense («Доходи та витрати», {@code income-expense}, feature 018 §2.12, worked example 12) through the
 * API, on entries made through the real paths (payments and their reversals through the payments API, so the
 * treasury dates a reversal on the day it is made while the ledger keeps its payment's day).
 * <p>
 * Cash opening 2 000.00 and bank opening 10 000.00, 40 days ago. Plot C has a membership charge of 300.00 and a water
 * charge of 200.00, plot D a membership charge of 150.00, plot P none. Payments: A cash 250.00 on P 20 days ago, B cash
 * 50.00 on P 5 days ago, C cash 600.00 on C 3 days ago (300.00 + 200.00 allocated, 100.00 advance), D bank 150.00 on D
 * 2 days ago (membership); A, B and D are reversed today. A cash expense of 300.00 «Матеріали» and a bank expense of
 * 150.00 «Банківські комісії» 6 days ago, the bank one reversed today; bank interest of 12.50 7 days ago; a handover
 * of 400.00 4 days ago; a cash count yesterday with a shortage of 20.00, booked.
 * <p>
 * The last ten days up to today: only C counts (B and D were received and reversed in the period), A's reversal is a
 * reversal of an earlier payment (−250.00): income 362.50, expenses 300.00, differences −20.00, result 42.50. The last
 * ten days up to yesterday: B, C and D count in full, D split as it was allocated yesterday (its reversal released the
 * allocation today, and the ledger dates that reversal on D's own day, inside the period): income 812.50, expenses
 * 450.00, result 342.50. Both results equal the change of cash plus bank.
 */
class IncomeExpenseReportTests extends TreasuryReportsSupport {

    private String chairman;
    private UUID membership;
    private UUID water;

    @BeforeEach
    void theMonth() throws Exception {
        chairman = person(tenantA, "CHAIRMAN");
        membership = chargeType(tenantA, "MEMBERSHIP");
        water = chargeType(tenantA, "WATER");
        LocalDate opening = today().minusDays(40);
        setOpening(chairman, account(tenantA, "CASH"), "2000.00", opening).andExpect(status().isOk());
        setOpening(chairman, account(tenantA, "BANK"), "10000.00", opening).andExpect(status().isOk());
        UUID plotC = paymentPlot(tenantA);
        UUID plotD = paymentPlot(tenantA);
        UUID plotP = paymentPlot(tenantA);
        charge(tenantA, plotC, "MEMBERSHIP", "300.00", opening.plusDays(1));
        charge(tenantA, plotC, "WATER", "200.00", opening.plusDays(1));
        charge(tenantA, plotD, "MEMBERSHIP", "150.00", opening.plusDays(1));

        UUID a = pay(chairman, plotP, "CASH", "250.00", today().minusDays(20));
        UUID b = pay(chairman, plotP, "CASH", "50.00", today().minusDays(5));
        pay(chairman, plotC, "CASH", "600.00", today().minusDays(3));
        UUID d = pay(chairman, plotD, "BANK", "150.00", today().minusDays(2));
        recordExpense(chairman, expense("CASH", today().minusDays(6), "300.00",
                category(tenantA, "EXPENSE", "MATERIALS")));
        UUID bankFee = recordExpense(chairman, expense("BANK", today().minusDays(6), "150.00",
                category(tenantA, "EXPENSE", "BANK_FEES")));
        recordOtherIncome(chairman, otherIncome("BANK", today().minusDays(7), "12.50",
                category(tenantA, "INCOME", "BANK_INTEREST")));
        recordHandover(chairman, handover(today().minusDays(4), "400.00", "КВ-1"));
        String book = body(previewCount(chairman, count(kyiv(today().minusDays(1), 12), "0.00", null))
                .andExpect(status().isOk())).get("bookBalance").asString();
        UUID count = saveCount(chairman, count(kyiv(today().minusDays(1), 12),
                new BigDecimal(book).subtract(new BigDecimal("20.00")).toPlainString(), "Нестача"));
        bookDifference(chairman, count, "Нестача підтверджена").andExpect(status().isOk());

        reverse(chairman, a, "Помилковий платіж");
        reverse(chairman, b, "Помилковий платіж");
        reverse(chairman, d, "Помилковий платіж");
        reverseExpense(chairman, bankFee, "Комісію повернуто").andExpect(status().isOk());
    }

    @Test
    void thePeriodUpToTodayCountsOnlyWhatStayedAndTheReversalOfAnEarlierPayment() throws Exception {
        LocalDate from = today().minusDays(10);
        JsonNode preview = preview(chairman, "income-expense", from, today());
        JsonNode main = sheet(preview, "main");

        assertThat(lines(main)).containsExactly(
                Map.entry("MEMBER_BY_TYPE:" + membership, "300.00"),
                Map.entry("MEMBER_BY_TYPE:" + water, "200.00"),
                Map.entry("ADVANCES:", "100.00"),
                Map.entry("PAYMENT_REVERSALS:", "-250.00"),
                Map.entry("OTHER_INCOME:" + category(tenantA, "INCOME", "BANK_INTEREST"), "12.50"),
                Map.entry("EXPENSE:" + category(tenantA, "EXPENSE", "MATERIALS"), "300.00"),
                Map.entry("DIFFERENCES:COUNT_DIFFERENCE", "-20.00"),
                Map.entry("DIFFERENCES:BANK_DIFFERENCE", "0.00"));
        assertThat(cell(main, total(main, "income"), "amount")).isEqualTo("362.50");
        assertThat(cell(main, total(main, "expenses"), "amount")).isEqualTo("300.00");
        assertThat(cell(main, total(main, "differences"), "amount")).isEqualTo("-20.00");
        assertThat(cell(main, total(main, "result"), "amount")).isEqualTo("42.50")
                .isEqualTo(change(preview, from, today()));

        // the member lines are the payments report's allocations of the same payments (FS 2.17 #12)
        JsonNode payments = sheet(preview(chairman, "payments", from, today()), "main");
        JsonNode all = total(payments, "all");
        assertThat(new BigDecimal("500.00")).isEqualByComparingTo(cell(payments, all, "allocated"));
        assertThat(cell(payments, all, "prepayment")).isEqualTo("100.00");

        JsonNode balances = sheet(preview, "balances");
        assertThat(cell(balances, balances.get("rows").get(0), "account")).isEqualTo("CASH");
        assertThat(cell(balances, balances.get("rows").get(0), "end")).isEqualTo(balanceOn(chairman, "CASH", today()));
        assertThat(cell(balances, balances.get("rows").get(1), "end")).isEqualTo(balanceOn(chairman, "BANK", today()));
        assertThat(cell(balances, total(balances, "all"), "change")).isEqualTo("42.50");

        for (String lang : List.of("uk", "en")) {
            reportFile(chairman, "income-expense", "from", from.toString(), "to", today().toString(), "lang", lang)
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aPaymentReversedAfterThePeriodCountsInFullSplitAsAtThePeriodsEnd() throws Exception {
        LocalDate from = today().minusDays(10);
        LocalDate to = today().minusDays(1);
        JsonNode preview = preview(chairman, "income-expense", from, to);
        JsonNode main = sheet(preview, "main");

        Map<String, String> lines = lines(main);
        assertThat(lines).containsEntry("MEMBER_BY_TYPE:" + membership, "450.00")
                .containsEntry("MEMBER_BY_TYPE:" + water, "200.00")
                .containsEntry("ADVANCES:", "150.00")
                .containsEntry("PAYMENT_REVERSALS:", "0.00")
                .containsEntry("EXPENSE:" + category(tenantA, "EXPENSE", "BANK_FEES"), "150.00");
        assertThat(cell(main, total(main, "income"), "amount")).isEqualTo("812.50");
        assertThat(cell(main, total(main, "expenses"), "amount")).isEqualTo("450.00");
        assertThat(cell(main, total(main, "result"), "amount")).isEqualTo("342.50")
                .isEqualTo(change(preview, from, to));
    }

    @Test
    void bothOpeningBalancesAreNeeded() throws Exception {
        String other = person(tenantB, "CHAIRMAN");
        setOpening(other, account(tenantB, "CASH"), "10.00", today().minusDays(3)).andExpect(status().isOk());
        report(other, "income-expense", "from", today().minusDays(3).toString(), "to", today().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPENING_BALANCE_MISSING"));
        setOpening(other, account(tenantB, "BANK"), "10.00", today().minusDays(2)).andExpect(status().isOk());
        report(other, "income-expense", "from", today().minusDays(3).toString(), "to", today().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BEFORE_OPENING_BALANCE"));
        assertThat(cell(sheet(preview(other, "income-expense", today().minusDays(2), today()), "main"),
                total(sheet(preview(other, "income-expense", today().minusDays(2), today()), "main"), "result"),
                "amount")).isEqualTo("0.00");
    }

    /** The change of cash plus bank over the period as «Каса і банк» shows the balances, checked against the sheet. */
    private String change(JsonNode preview, LocalDate from, LocalDate to) throws Exception {
        BigDecimal start = new BigDecimal(balanceOn(chairman, "CASH", from.minusDays(1)))
                .add(new BigDecimal(balanceOn(chairman, "BANK", from.minusDays(1))));
        BigDecimal end = new BigDecimal(balanceOn(chairman, "CASH", to))
                .add(new BigDecimal(balanceOn(chairman, "BANK", to)));
        JsonNode balances = sheet(preview, "balances");
        assertThat(new BigDecimal(cell(balances, total(balances, "all"), "start"))).isEqualByComparingTo(start);
        assertThat(new BigDecimal(cell(balances, total(balances, "all"), "end"))).isEqualByComparingTo(end);
        return end.subtract(start).setScale(2).toPlainString();
    }

    /** The lines of the sheet as {@code SECTION:<type, category or difference>} → amount, in order. */
    private static Map<String, String> lines(JsonNode main) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (JsonNode row : main.get("rows")) {
            String name = cell(main, row, "chargeType") != null ? cell(main, row, "chargeType")
                    : cell(main, row, "category") != null ? cell(main, row, "category")
                    : cell(main, row, "difference") != null ? cell(main, row, "difference") : "";
            lines.put(cell(main, row, "section") + ":" + name, cell(main, row, "amount"));
        }
        return lines;
    }
}
