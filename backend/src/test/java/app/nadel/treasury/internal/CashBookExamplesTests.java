package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.CashGuard.Shortfall;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The worked examples of feature 018 (functional spec 2.17), one test per row, on the pure calculators: the cash opened
 * with 1 000,00 and the bank with 20 000,00 on 01.10.2026; each row adds its event to the journal of the rows before
 * it. Rows come with the slices that implement their rules.
 */
class CashBookExamplesTests {

    private static final Opening CASH = new Opening(money("1000.00"), day("2026-10-01"));
    private static final Opening BANK = new Opening(money("20000.00"), day("2026-10-01"));

    @Test
    void row1ACashPaymentOf500On5OctoberMakesTheCash1500() {
        assertThat(AccountBalances.balanceAt(CASH, cashAfterRow(1), day("2026-10-05"))).isEqualTo(money("1500.00"));
    }

    @Test
    void row2ACashExpenseOf300On6OctoberIsAllowedAndMakesTheCash1200() {
        assertThat(CashGuard.shortfall(CASH, cashAfterRow(1), day("2026-10-06"), money("-300.00"))).isEmpty();
        assertThat(AccountBalances.balanceAt(CASH, cashAfterRow(2), day("2026-10-06"))).isEqualTo(money("1200.00"));
    }

    @Test
    void row3AHandoverOf1000On7OctoberLeavesCash200AndBank21000() {
        assertThat(CashGuard.shortfall(CASH, cashAfterRow(2), day("2026-10-07"), money("-1000.00"))).isEmpty();
        assertThat(AccountBalances.balanceAt(CASH, cashAfterRow(3), day("2026-10-07"))).isEqualTo(money("200.00"));
        assertThat(AccountBalances.balanceAt(BANK, List.of(total("2026-10-07", "1000.00")), day("2026-10-07")))
                .isEqualTo(money("21000.00"));
    }

    @Test
    void row3TheHandoverReversedOn8OctoberGivesTheCash1200AndTheBank20000Back() {
        // the reversal lines: the cash gets the 1 000,00 back, the bank gives it; the cash rule only checks money out
        List<DayTotal> cash = new ArrayList<>(cashAfterRow(3));
        cash.add(total("2026-10-08", "1000.00"));
        List<DayTotal> bank = List.of(total("2026-10-07", "1000.00"), total("2026-10-08", "-1000.00"));

        assertThat(AccountBalances.balanceAt(CASH, cash, day("2026-10-08"))).isEqualTo(money("1200.00"));
        assertThat(AccountBalances.balanceAt(BANK, bank, day("2026-10-08"))).isEqualTo(money("20000.00"));
        assertThat(AccountBalances.balanceAt(CASH, cash, day("2026-10-07")))
                .as("the handover day keeps its balance").isEqualTo(money("200.00"));
    }

    @Test
    void row3AfterTheHandoverReversingA300CashIncomeOf6OctoberIsRefusedWithTheBalance200() {
        // a cash other income of 300,00 on 06.10 instead of the expense: cash 1 800,00, then 800,00 after the handover;
        // an expense of 600,00 on 07.10 leaves 200,00, so taking the 300,00 back on 08.10 would make it −100,00
        List<DayTotal> cash = List.of(total("2026-10-05", "500.00"), total("2026-10-06", "300.00"),
                total("2026-10-07", "-1600.00"));

        assertThat(CashGuard.shortfall(CASH, cash, day("2026-10-08"), money("-300.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-08")));
        assertThat(CashGuard.shortfall(CASH, cash, day("2026-10-08"), money("-200.00"))).isEmpty();
    }

    @Test
    void row4ACashExpenseOf250On7OctoberIsRefusedWithTheBalance200() {
        assertThat(CashGuard.shortfall(CASH, cashAfterRow(3), day("2026-10-07"), money("-250.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-07")));
    }

    @Test
    void row5ABackDatedExpenseOf400On6OctoberIsRefusedBecause7OctoberWouldBeMinus200() {
        // 06.10 itself would stay positive (800,00)
        assertThat(AccountBalances.balanceAt(CASH, cashAfterRow(3), day("2026-10-06")).minus(money("400.00")))
                .isEqualTo(money("800.00"));
        assertThat(CashGuard.shortfall(CASH, cashAfterRow(3), day("2026-10-06"), money("-400.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-07")));
    }

    /** The cash day totals after the events of rows 1 to {@code row}. */
    private static List<DayTotal> cashAfterRow(int row) {
        List<DayTotal> totals = new ArrayList<>();
        if (row >= 1) {
            totals.add(total("2026-10-05", "500.00"));
        }
        if (row >= 2) {
            totals.add(total("2026-10-06", "-300.00"));
        }
        if (row >= 3) {
            totals.add(total("2026-10-07", "-1000.00"));
        }
        return totals;
    }

    private static DayTotal total(String day, String amount) {
        return new DayTotal(day(day), money(amount));
    }

    private static LocalDate day(String day) {
        return LocalDate.parse(day);
    }

    private static Money money(String amount) {
        return Money.parse(amount);
    }
}
