package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The balance rule of a money account (feature 018 §2.1; worked examples FS 2.17 #1 and #10 on the cash account opened
 * with 1 000,00 on 01.10.2026).
 */
class AccountBalancesTests {

    private static final Opening CASH = new Opening(Money.parse("1000.00"), LocalDate.of(2026, 10, 1));

    @Test
    void example1ACashPaymentOf500On5OctoberMakesTheCash1500() {
        List<DayTotal> totals = List.of(total("2026-10-05", "500.00"));

        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 4))).isEqualTo(money("1000.00"));
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 5))).isEqualTo(money("1500.00"));
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 31))).isEqualTo(money("1500.00"));
    }

    @Test
    void example10TheReversalOfACashPaymentTakesItsAmountOffOnTheDayOfTheReversal() {
        List<DayTotal> totals = List.of(total("2026-10-05", "500.00"), total("2026-10-12", "-500.00"));

        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 11))).isEqualTo(money("1500.00"));
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 12))).isEqualTo(money("1000.00"));
    }

    @Test
    void example10AReversalThatTakesTheCashBelowZeroCountsAndFlagsItNegative() {
        Opening empty = new Opening(Money.ZERO, LocalDate.of(2026, 10, 1));
        // the payment was received before the opening date: only its reversal counts
        List<DayTotal> totals = List.of(total("2026-09-30", "300.00"), total("2026-10-12", "-300.00"));

        Money balance = AccountBalances.balanceAt(empty, totals, LocalDate.of(2026, 10, 12));

        assertThat(balance).isEqualTo(money("-300.00"));
        assertThat(AccountBalances.negative(balance)).isTrue();
        assertThat(AccountBalances.negative(money("0.00"))).isFalse();
        assertThat(AccountBalances.negative(null)).isFalse();
    }

    @Test
    void movementsBeforeTheOpeningDateAreListedAsSuchAndNeverCounted() {
        List<DayTotal> totals = List.of(total("2026-09-29", "200.00"), total("2026-10-01", "50.00"));

        assertThat(AccountBalances.beforeOpening(CASH, LocalDate.of(2026, 9, 30))).isTrue();
        assertThat(AccountBalances.beforeOpening(CASH, LocalDate.of(2026, 10, 1))).isFalse();
        assertThat(AccountBalances.beforeOpening(null, LocalDate.of(2026, 9, 30))).isFalse();
        // the end of the day before the opening date is the opening amount; earlier no balance is known
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 9, 30))).isEqualTo(money("1000.00"));
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 9, 29))).isNull();
        assertThat(AccountBalances.balanceAt(CASH, totals, LocalDate.of(2026, 10, 1))).isEqualTo(money("1050.00"));
    }

    @Test
    void withoutAnOpeningBalanceThereIsNoBalance() {
        assertThat(AccountBalances.balanceAt(null, List.of(total("2026-10-05", "500.00")),
                LocalDate.of(2026, 10, 5))).isNull();
    }

    private static DayTotal total(String day, String amount) {
        return new DayTotal(LocalDate.parse(day), money(amount));
    }

    private static Money money(String amount) {
        return Money.parse(amount);
    }
}
