package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.BankComparison.Flow;
import app.nadel.treasury.internal.StatementRules.Figures;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The books of a bank month and the differences from the statement (feature 018 §2.7): the balance at the start of the
 * month, receipts and spendings as positive figures with reversals counted against the line they reverse, the balance
 * at its end; lines before the opening date or outside the month do not count in it.
 */
class BankComparisonTests {

    private static final Opening OCTOBER_1 = new Opening(money("20000.00"), day("2026-10-01"));
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Test
    void theOpeningMonthStartsFromTheOpeningAmountAndSumsReceiptsAndSpendingsAsPositiveFigures() {
        List<Flow> flows = List.of(flow("2026-10-05", false, "2000.00"), flow("2026-10-07", false, "1000.00"),
                flow("2026-10-31", false, "-150.00"));

        assertThat(BankComparison.books(OCTOBER_1, OCTOBER, flows))
                .isEqualTo(figures("20000.00", "3000.00", "150.00", "22850.00"));
    }

    @Test
    void aLaterMonthStartsFromTheBalanceAtTheEndOfThePreviousMonthAndIgnoresLaterLines() {
        List<Flow> flows = List.of(flow("2026-10-05", false, "2000.00"), flow("2026-10-31", false, "-150.00"),
                flow("2026-11-03", false, "500.00"), flow("2026-11-20", false, "-20.00"),
                flow("2026-12-01", false, "999.00"));

        assertThat(BankComparison.books(OCTOBER_1, YearMonth.of(2026, 11), flows))
                .isEqualTo(figures("21850.00", "500.00", "20.00", "22330.00"));
    }

    @Test
    void aMistakenEntryAndItsReversalInOneMonthCancelOutInBothFigures() {
        // a bank expense of 150,00 entered by mistake and reversed, a payment of 1 000,00 entered and reversed, and the
        // right payment of 100,00
        List<Flow> flows = List.of(flow("2026-10-10", false, "-150.00"), flow("2026-10-11", true, "150.00"),
                flow("2026-10-12", false, "1000.00"), flow("2026-10-12", true, "-1000.00"),
                flow("2026-10-12", false, "100.00"));

        assertThat(BankComparison.books(OCTOBER_1, OCTOBER, flows))
                .isEqualTo(figures("20000.00", "100.00", "0.00", "20100.00"));
    }

    @Test
    void aReversalInALaterMonthLowersThatMonthsFigureOfItsOriginal() {
        List<Flow> flows = List.of(flow("2026-10-10", false, "-150.00"), flow("2026-11-02", true, "150.00"));

        assertThat(BankComparison.books(OCTOBER_1, YearMonth.of(2026, 11), flows))
                .isEqualTo(figures("19850.00", "0.00", "-150.00", "20000.00"));
    }

    @Test
    void anOpeningInsideTheMonthStartsThereAndLinesBeforeItCountInNothing() {
        Opening midMonth = new Opening(money("500.00"), day("2026-10-15"));
        List<Flow> flows = List.of(flow("2026-10-03", false, "70.00"), flow("2026-10-15", false, "30.00"),
                flow("2026-10-20", false, "-10.00"));

        assertThat(BankComparison.books(midMonth, OCTOBER, flows))
                .isEqualTo(figures("500.00", "30.00", "10.00", "520.00"));
        assertThat(BankComparison.books(midMonth, YearMonth.of(2026, 11), flows))
                .isEqualTo(figures("520.00", "0.00", "0.00", "520.00"));
    }

    @Test
    void aMonthThatEndsBeforeTheOpeningDateHasNoBooks() {
        assertThatThrownBy(() -> BankComparison.books(OCTOBER_1, YearMonth.of(2026, 9), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDifferencesAreStatementMinusBooksAndTheMonthBalancesOnlyWhenEachIs0() {
        Figures statement = figures("20000.00", "3000.00", "150.00", "22850.00");
        Figures books = figures("20000.00", "3000.00", "0.00", "23000.00");

        Figures differences = BankComparison.differences(statement, books);

        assertThat(differences).isEqualTo(figures("0.00", "0.00", "150.00", "-150.00"));
        assertThat(BankComparison.balanced(differences)).isFalse();
        assertThat(BankComparison.balanced(BankComparison.differences(statement, statement))).isTrue();
        assertThat(BankComparison.balanced(null)).as("no statement").isFalse();
        assertThat(BankComparison.balanced(figures("0.01", "0.00", "0.00", "0.00"))).isFalse();
        assertThat(BankComparison.balanced(figures("0.00", "0.01", "0.00", "0.00"))).isFalse();
        assertThat(BankComparison.balanced(figures("0.00", "0.00", "0.00", "0.01"))).isFalse();
    }

    @Test
    void aFlowIsIncomingWhenItIsAReceiptOrTheReversalOfOne() {
        assertThat(flow("2026-10-01", false, "1.00").incoming()).isTrue();
        assertThat(flow("2026-10-01", true, "-1.00").incoming()).isTrue();
        assertThat(flow("2026-10-01", false, "-1.00").incoming()).isFalse();
        assertThat(flow("2026-10-01", true, "1.00").incoming()).isFalse();
    }

    static Figures figures(String opening, String incoming, String outgoing, String closing) {
        return new Figures(money(opening), money(incoming), money(outgoing), money(closing));
    }

    private static Flow flow(String day, boolean reversal, String total) {
        return new Flow(day(day), reversal, money(total));
    }

    private static LocalDate day(String day) {
        return LocalDate.parse(day);
    }

    private static Money money(String amount) {
        return Money.parse(amount);
    }
}
