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
