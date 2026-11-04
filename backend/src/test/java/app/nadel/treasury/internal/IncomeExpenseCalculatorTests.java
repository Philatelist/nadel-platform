package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.PaymentSplit;
import app.nadel.money.Money;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.IncomeExpenseCalculator.Balances;
import app.nadel.treasury.internal.IncomeExpenseCalculator.Line;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The income and expense of a period (feature 018 §2.12, worked example 12; technical considerations 2.7) on the pure
 * calculator: income − expenses + differences equals the change of cash plus bank over the period, with the
 * treasury's dating of payment reversals (the reversal's own day) and the ledger only for the split.
 * <p>
 * October 2026, cash opening 1 500.00 and bank opening 20 000.00 on 01.10. Member payments: P1 cash 500.00 on 05.10
 * (membership 400.00, advance 100.00); P2 bank 1 200.00 on 12.10 (membership); P3 bank 800.00 on 15.10 (water 500.00,
 * advance 300.00); P4 cash 200.00 on 08.10 reversed on 09.10 (received and reversed in October: counts in neither);
 * P0 cash 250.00 received on 20.09, before the opening date, reversed on 14.10 (a reversal of an earlier payment:
 * −250.00); P5 bank 100.00 on 28.10 reversed on 03.11 (after October: counts in full, split as at 31.10: membership
 * 100.00). Expenses: 300.00 «Матеріали» from cash on 06.10, 150.00 «Банківські комісії» from the bank on 20.10, 40.00
 * «Канцелярія» from cash on 11.10 reversed on 13.10 (nets to nothing). A handover of 1 000.00 on 07.10, a count
 * shortage of −20.00 on 10.10, a bank difference of +5.00 and bank interest of 12.50 on 31.10.
 * <p>
 * Member income 1 700.00 + 500.00 + 400.00 − 250.00 = 2 350.00; income 2 362.50; expenses 450.00; differences −15.00;
 * result 1 897.50 = cash −1 070.00 + bank +2 967.50.
 */
class IncomeExpenseCalculatorTests {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);
    private static final Opening CASH_OPENING = new Opening(Money.parse("1500.00"), FROM);
    private static final Opening BANK_OPENING = new Opening(Money.parse("20000.00"), FROM);

    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID WATER = UUID.randomUUID();
    private static final UUID MATERIALS = UUID.randomUUID();
    private static final UUID BANK_FEES = UUID.randomUUID();
    private static final UUID OFFICE = UUID.randomUUID();
    private static final UUID BANK_INTEREST = UUID.randomUUID();

    private static final UUID P0 = UUID.randomUUID();
    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();
    private static final UUID P4 = UUID.randomUUID();
    private static final UUID P5 = UUID.randomUUID();

    /** A journal line with its date, so the test can work out the balances the way the screen does. */
    private record Dated(LocalDate day, Line line) {
    }

    /** Every line of both accounts: September's payment P0, October, and P5's reversal in November. */
    private static List<Dated> journal() {
        List<Dated> lines = new ArrayList<>();
        lines.add(payment(day(9, 20), MoneyAccountKind.CASH, P0, "250.00"));
        lines.add(payment(day(10, 5), MoneyAccountKind.CASH, P1, "500.00"));
        lines.add(other(day(10, 6), MoneyAccountKind.CASH, MovementKind.EXPENSE, "-300.00", MATERIALS));
        lines.add(other(day(10, 7), MoneyAccountKind.CASH, MovementKind.HANDOVER_OUT, "-1000.00", null));
        lines.add(other(day(10, 7), MoneyAccountKind.BANK, MovementKind.HANDOVER_IN, "1000.00", null));
        lines.add(payment(day(10, 8), MoneyAccountKind.CASH, P4, "200.00"));
        lines.add(reversal(day(10, 9), MoneyAccountKind.CASH, P4, "-200.00", day(10, 8)));
        lines.add(other(day(10, 10), MoneyAccountKind.CASH, MovementKind.COUNT_DIFFERENCE, "-20.00", null));
        lines.add(other(day(10, 11), MoneyAccountKind.CASH, MovementKind.EXPENSE, "-40.00", OFFICE));
        lines.add(payment(day(10, 12), MoneyAccountKind.BANK, P2, "1200.00"));
        lines.add(new Dated(day(10, 13), new Line(MoneyAccountKind.CASH, MovementKind.EXPENSE, Money.parse("40.00"),
                null, true, day(10, 11), OFFICE)));
        lines.add(reversal(day(10, 14), MoneyAccountKind.CASH, P0, "-250.00", day(9, 20)));
        lines.add(payment(day(10, 15), MoneyAccountKind.BANK, P3, "800.00"));
        lines.add(other(day(10, 20), MoneyAccountKind.BANK, MovementKind.EXPENSE, "-150.00", BANK_FEES));
        lines.add(payment(day(10, 28), MoneyAccountKind.BANK, P5, "100.00"));
        lines.add(other(day(10, 31), MoneyAccountKind.BANK, MovementKind.BANK_DIFFERENCE, "5.00", null));
        lines.add(other(day(10, 31), MoneyAccountKind.BANK, MovementKind.OTHER_INCOME, "12.50", BANK_INTEREST));
        lines.add(reversal(day(11, 3), MoneyAccountKind.BANK, P5, "-100.00", day(10, 28)));
        return lines;
    }

    /** The splits the ledger gives as of the end of October (P5's reversal released nothing yet). */
    private static Map<UUID, PaymentSplit> splits() {
        Map<UUID, PaymentSplit> splits = new LinkedHashMap<>();
        splits.put(P1, split(P1, "500.00", Map.of(MEMBERSHIP, Money.parse("400.00"))));
        splits.put(P2, split(P2, "1200.00", Map.of(MEMBERSHIP, Money.parse("1200.00"))));
        splits.put(P3, split(P3, "800.00", Map.of(WATER, Money.parse("500.00"))));
        splits.put(P5, split(P5, "100.00", Map.of(MEMBERSHIP, Money.parse("100.00"))));
        return splits;
    }

    @Test
    void incomeMinusExpensesPlusDifferencesIsTheChangeOfCashAndBank() {
        IncomeExpense figures = october();

        assertThat(figures.memberByChargeType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                MEMBERSHIP, Money.parse("1700.00"), WATER, Money.parse("500.00")));
        assertThat(figures.advances()).isEqualTo(Money.parse("400.00"));
        assertThat(figures.paymentReversals()).isEqualTo(Money.parse("-250.00"));
        assertThat(figures.memberIncome()).isEqualTo(Money.parse("2350.00"));
        assertThat(figures.otherIncomeByCategory()).containsExactlyEntriesOf(Map.of(BANK_INTEREST,
                Money.parse("12.50")));
        assertThat(figures.income()).isEqualTo(Money.parse("2362.50"));
        assertThat(figures.expensesByCategory()).containsExactlyInAnyOrderEntriesOf(Map.of(
                MATERIALS, Money.parse("300.00"), BANK_FEES, Money.parse("150.00")));
        assertThat(figures.expenses()).isEqualTo(Money.parse("450.00"));
        assertThat(figures.countDifferences()).isEqualTo(Money.parse("-20.00"));
        assertThat(figures.bankDifferences()).isEqualTo(Money.parse("5.00"));
        assertThat(figures.differences()).isEqualTo(Money.parse("-15.00"));

        assertThat(figures.cashStart()).isEqualTo(Money.parse("1500.00"));
        assertThat(figures.cashEnd()).isEqualTo(Money.parse("430.00"));
        assertThat(figures.bankStart()).isEqualTo(Money.parse("20000.00"));
        assertThat(figures.bankEnd()).isEqualTo(Money.parse("22967.50"));
        Money change = figures.cashEnd().plus(figures.bankEnd()).minus(figures.cashStart())
                .minus(figures.bankStart());
        assertThat(change).isEqualTo(Money.parse("1897.50"));
        assertThat(figures.result()).isEqualTo(change);
    }

    @Test
    void aPaymentReceivedAndReversedInThePeriodCountsInNeitherAndOneReversedLaterCountsInFull() {
        List<Line> lines = linesOf(FROM, TO);

        assertThat(IncomeExpenseCalculator.countedPayments(lines, FROM)).containsExactly(P1, P2, P3, P5);
        // the payment line of P0 is dated before the period: only its reversal is in it, as a negative line
        assertThat(october().paymentReversals()).isEqualTo(Money.parse("-250.00"));
    }

    @Test
    void novemberSeesTheLaterReversalAsAReversalOfAnEarlierPayment() {
        LocalDate from = day(11, 1);
        LocalDate to = day(11, 30);
        List<Line> lines = linesOf(from, to);
        IncomeExpense figures = IncomeExpenseCalculator.calculate(from, to, lines, Map.of(), balances(from, to));

        assertThat(IncomeExpenseCalculator.countedPayments(lines, from)).isEmpty();
        assertThat(figures.paymentReversals()).isEqualTo(Money.parse("-100.00"));
        assertThat(figures.result()).isEqualTo(Money.parse("-100.00"));
        assertThat(figures.result()).isEqualTo(figures.bankEnd().minus(figures.bankStart())
                .plus(figures.cashEnd()).minus(figures.cashStart()));
    }

    @Test
    void aTypeOrCategoryThatNetsToZeroIsLeftOut() {
        assertThat(october().expensesByCategory()).doesNotContainKey(OFFICE);
        Map<UUID, PaymentSplit> splits = Map.of(P1, split(P1, "500.00", Map.of(MEMBERSHIP, Money.parse("0.00"))));
        IncomeExpense figures = IncomeExpenseCalculator.calculate(FROM, TO, List.of(
                payment(day(10, 5), MoneyAccountKind.CASH, P1, "500.00").line()), splits, balances(FROM, TO));
        assertThat(figures.memberByChargeType()).isEmpty();
        assertThat(figures.advances()).isEqualTo(Money.parse("500.00"));
    }

    @Test
    void anEmptyPeriodIsAllZeros() {
        Money zero = Money.ZERO;
        IncomeExpense figures = IncomeExpenseCalculator.calculate(FROM, TO, List.of(), Map.of(),
                new Balances(zero, zero, zero, zero));
        assertThat(figures.income()).isEqualTo(zero);
        assertThat(figures.expenses()).isEqualTo(zero);
        assertThat(figures.differences()).isEqualTo(zero);
        assertThat(figures.result()).isEqualTo(zero);
    }

    @Test
    void aCountedPaymentNeedsItsSplitAndLinesNameWhatTheyNeed() {
        List<Line> lines = List.of(payment(day(10, 5), MoneyAccountKind.CASH, P1, "500.00").line());
        assertThatThrownBy(() -> IncomeExpenseCalculator.calculate(FROM, TO, lines, Map.of(), balances(FROM, TO)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Line(MoneyAccountKind.CASH, MovementKind.MEMBER_PAYMENT, Money.parse("1.00"),
                null, false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Line(MoneyAccountKind.CASH, MovementKind.MEMBER_PAYMENT, Money.parse("-1.00"),
                P1, true, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IncomeExpenseCalculator.calculate(FROM, TO, List.of(new Line(MoneyAccountKind.CASH,
                MovementKind.EXPENSE, Money.parse("-1.00"), null, false, null, null)), Map.of(), balances(FROM, TO)))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- the worked month

    private static IncomeExpense october() {
        return IncomeExpenseCalculator.calculate(FROM, TO, linesOf(FROM, TO), splits(), balances(FROM, TO));
    }

    private static List<Line> linesOf(LocalDate from, LocalDate to) {
        return journal().stream().filter(dated -> !dated.day().isBefore(from) && !dated.day().isAfter(to))
                .map(Dated::line).toList();
    }

    /** The balances as «Каса і банк» works them out ({@link AccountBalances}) over the whole journal. */
    private static Balances balances(LocalDate from, LocalDate to) {
        List<DayTotal> cash = new ArrayList<>();
        List<DayTotal> bank = new ArrayList<>();
        for (Dated dated : journal()) {
            (dated.line().account() == MoneyAccountKind.CASH ? cash : bank)
                    .add(new DayTotal(dated.day(), dated.line().amount()));
        }
        return new Balances(AccountBalances.balanceAt(CASH_OPENING, cash, from.minusDays(1)),
                AccountBalances.balanceAt(CASH_OPENING, cash, to),
                AccountBalances.balanceAt(BANK_OPENING, bank, from.minusDays(1)),
                AccountBalances.balanceAt(BANK_OPENING, bank, to));
    }

    private static Dated payment(LocalDate day, MoneyAccountKind account, UUID payment, String amount) {
        return new Dated(day, new Line(account, MovementKind.MEMBER_PAYMENT, Money.parse(amount), payment, false,
                null, null));
    }

    private static Dated reversal(LocalDate day, MoneyAccountKind account, UUID payment, String amount,
            LocalDate original) {
        return new Dated(day, new Line(account, MovementKind.MEMBER_PAYMENT, Money.parse(amount), payment, true,
                original, null));
    }

    private static Dated other(LocalDate day, MoneyAccountKind account, MovementKind kind, String amount,
            UUID category) {
        return new Dated(day, new Line(account, kind, Money.parse(amount), null, false, null, category));
    }

    private static PaymentSplit split(UUID payment, String amount, Map<UUID, Money> byType) {
        Money total = Money.parse(amount);
        Money allocated = byType.values().stream().reduce(Money.ZERO, Money::plus);
        return new PaymentSplit(payment, total, byType, total.minus(allocated));
    }

    private static LocalDate day(int month, int day) {
        return LocalDate.of(2026, month, day);
    }
}
