package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.CashGuard.Shortfall;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The no-negative-cash rule (feature 018 §2.2, technical considerations 2.5): the cash opened with 1 000,00 on
 * 01.10.2026 (FS 2.17), a cash payment of 500,00 on 05.10, an expense of 300,00 on 06.10 and a handover of 1 000,00 on
 * 07.10 leave 1 500,00 / 1 200,00 / 200,00 at the end of those days.
 */
class CashGuardTests {

    private static final Opening CASH = new Opening(Money.parse("1000.00"), LocalDate.of(2026, 10, 1));
    private static final List<DayTotal> OCTOBER = List.of(total("2026-10-05", "500.00"),
            total("2026-10-06", "-300.00"), total("2026-10-07", "-1000.00"));

    @Test
    void example2AnExpenseThatTheCashCoversIsAllowed() {
        List<DayTotal> before = List.of(total("2026-10-05", "500.00"));

        assertThat(CashGuard.shortfall(CASH, before, day("2026-10-06"), money("-300.00"))).isEmpty();
        CashGuard.check(CASH, before, day("2026-10-06"), money("-300.00"));
    }

    @Test
    void example4AnExpenseAboveTheCashOfItsDayIsRefusedWithThatBalance() {
        assertThat(CashGuard.shortfall(CASH, OCTOBER, day("2026-10-07"), money("-250.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-07")));

        assertThatThrownBy(() -> CashGuard.check(CASH, OCTOBER, day("2026-10-07"), money("-250.00")))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("CASH_INSUFFICIENT");
                    assertThat(e.balance()).isEqualTo("200.00");
                    assertThat(e.date()).isEqualTo(day("2026-10-07"));
                });
    }

    @Test
    void example5ABackDatedExpenseIsRefusedWhenALaterDayWouldGoNegative() {
        // 06.10 itself would stay at 800,00, but 07.10 would be -200,00 after the handover
        assertThat(CashGuard.shortfall(CASH, OCTOBER, day("2026-10-06"), money("-400.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-07")));
        // what 07.10 can still take is allowed
        assertThat(CashGuard.shortfall(CASH, OCTOBER, day("2026-10-06"), money("-200.00"))).isEmpty();
    }

    @Test
    void theWholeBalanceMayBeSpentButNotOneKopiykaMore() {
        assertThat(CashGuard.shortfall(CASH, OCTOBER, day("2026-10-07"), money("-200.00"))).isEmpty();
        assertThat(CashGuard.shortfall(CASH, OCTOBER, day("2026-10-07"), money("-200.01"))).isPresent();
    }

    @Test
    void theLowestBalanceOfTheSuffixIsReportedOnItsEarliestDay() {
        // 1 000 -> 05.10: 400 -> 08.10: 900 -> 10.10: 400 -> 12.10: 600
        List<DayTotal> totals = List.of(total("2026-10-05", "-600.00"), total("2026-10-08", "500.00"),
                total("2026-10-10", "-500.00"), total("2026-10-12", "200.00"));

        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-06"), money("-450.00")))
                .contains(new Shortfall(money("400.00"), day("2026-10-06")));
        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-09"), money("-450.00")))
                .contains(new Shortfall(money("400.00"), day("2026-10-10")));
        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-11"), money("-450.00")))
                .contains(new Shortfall(money("400.00"), day("2026-10-11")));
        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-13"), money("-450.00"))).isEmpty();
    }

    @Test
    void aLaterDayAlreadyBelowZeroAfterAPaymentReversalBlocksEveryCashOut() {
        // a member payment reversal took 12.10 to -100,00 (allowed for the reversal, the card warns)
        List<DayTotal> totals = List.of(total("2026-10-12", "-1100.00"));

        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-03"), money("-0.01")))
                .contains(new Shortfall(money("-100.00"), day("2026-10-12")));
    }

    @Test
    void theTotalsMayComeInAnyOrderAndDaysBeforeTheOpeningAreIgnored() {
        List<DayTotal> totals = List.of(total("2026-10-07", "-1000.00"), total("2026-09-20", "5000.00"),
                total("2026-10-05", "500.00"), total("2026-10-06", "-300.00"));

        assertThat(CashGuard.shortfall(CASH, totals, day("2026-10-01"), money("-250.00")))
                .contains(new Shortfall(money("200.00"), day("2026-10-07")));
        assertThat(CashGuard.shortfall(CASH, List.of(), day("2026-10-01"), money("-1000.00"))).isEmpty();
    }

    @Test
    void onlyMoneyOutOnOrAfterTheOpeningDateIsChecked() {
        assertThatThrownBy(() -> CashGuard.shortfall(CASH, OCTOBER, day("2026-10-07"), money("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashGuard.shortfall(CASH, OCTOBER, day("2026-10-07"), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashGuard.shortfall(CASH, OCTOBER, day("2026-09-30"), money("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
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
