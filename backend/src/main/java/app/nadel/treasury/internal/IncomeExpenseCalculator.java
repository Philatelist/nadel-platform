package app.nadel.treasury.internal;

import app.nadel.ledger.PaymentSplit;
import app.nadel.money.Money;
import app.nadel.treasury.IncomeExpense;
import app.nadel.treasury.MoneyAccountKind;
import app.nadel.treasury.MovementKind;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The income and expense of a period (feature 018 §2.12, example 12; technical considerations 2.7), pure. Its input
 * is the journal lines of both accounts dated in the period (a period that starts on or after both opening dates, so
 * every line counts in the balances) and how the member payments received in it were allocated at its end. Nothing is
 * rounded: every figure is a sum of {@code Money}.
 * <p>
 * <b>Member payments</b> come from the treasury's own dating (a payment's line on the day it was received, a
 * reversal's line on the day of the reversal), not the ledger's debt dating (a reversal on its payment's day): the
 * report is about money that moved. A payment line dated in the period counts with its split
 * ({@link #countedPayments}) unless a reversal line of it is dated in the period too: then neither counts (a payment
 * received and reversed in the period brought nothing). A reversal line of a payment whose line is dated before the
 * period is a negative line of its own ({@code paymentReversals}). A payment received in the period and reversed after
 * it counts in full, split as it was allocated at the period's end (the ledger's cut-off).
 * <p>
 * <b>Other lines</b>: other income per category and expenses per category (positive) net of their reversals in the
 * period, count and bank differences signed; handover lines move money between the accounts and add up to zero. So
 * income − expenses + differences = the change of cash plus bank, the identity {@code IncomeExpenseCalculatorTests}
 * checks.
 */
final class IncomeExpenseCalculator {

    private IncomeExpenseCalculator() {
    }

    /**
     * A journal line of the period, or several of one kind and category added up (a sum is only allowed for a line
     * without {@code paymentId}).
     *
     * @param paymentId    for a member payment line, the payment (for its reversal, the payment it reverses)
     * @param reversal     whether the line reverses another line
     * @param originalDate for a reversal of a member payment, the date of the payment's line
     * @param categoryId   for an expense or other income line, its category
     */
    record Line(MoneyAccountKind account, MovementKind kind, Money amount, @Nullable UUID paymentId,
            boolean reversal, @Nullable LocalDate originalDate, @Nullable UUID categoryId) {

        Line {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(amount, "amount");
            if (kind == MovementKind.MEMBER_PAYMENT && paymentId == null) {
                throw new IllegalArgumentException("A member payment line names its payment");
            }
            if (kind == MovementKind.MEMBER_PAYMENT && reversal && originalDate == null) {
                throw new IllegalArgumentException("A payment reversal line names the date of its payment's line");
            }
        }
    }

    /** The balances of both accounts at the start of the period and at its end. */
    record Balances(Money cashStart, Money cashEnd, Money bankStart, Money bankEnd) {

        Balances {
            Objects.requireNonNull(cashStart, "cashStart");
            Objects.requireNonNull(cashEnd, "cashEnd");
            Objects.requireNonNull(bankStart, "bankStart");
            Objects.requireNonNull(bankEnd, "bankEnd");
        }
    }

    /**
     * The payments whose received money counts in the period: a payment line dated in it, and no reversal line of it
     * dated in it, in the order of the lines.
     *
     * @param from the first day of the period
     */
    static Set<UUID> countedPayments(List<Line> lines, LocalDate from) {
        Set<UUID> counted = new LinkedHashSet<>();
        Set<UUID> reversedInPeriod = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.kind() != MovementKind.MEMBER_PAYMENT) {
                continue;
            }
            if (!line.reversal()) {
                counted.add(line.paymentId());
            } else if (!Objects.requireNonNull(line.originalDate()).isBefore(from)) {
                reversedInPeriod.add(line.paymentId());
            }
        }
        counted.removeAll(reversedInPeriod);
        return counted;
    }

    /**
     * The income and expense of the period.
     *
     * @param from     the first day of the period
     * @param to       the last day of the period
     * @param lines    every journal line of both accounts dated from {@code from} to {@code to}
     * @param splits   the splits of at least the {@link #countedPayments} at the end of the period
     * @param balances the balances of both accounts at the start and the end of the period
     * @throws IllegalStateException when a counted payment has no split
     */
    static IncomeExpense calculate(LocalDate from, LocalDate to, List<Line> lines, Map<UUID, PaymentSplit> splits,
            Balances balances) {
        Map<UUID, Money> byChargeType = new LinkedHashMap<>();
        Money advances = Money.ZERO;
        for (UUID paymentId : countedPayments(lines, from)) {
            PaymentSplit split = splits.get(paymentId);
            if (split == null) {
                throw new IllegalStateException("No split of a counted payment");
            }
            split.byChargeType().forEach((type, amount) -> byChargeType.merge(type, amount, Money::plus));
            advances = advances.plus(split.unallocated());
        }
        Money paymentReversals = Money.ZERO;
        Map<UUID, Money> otherIncome = new LinkedHashMap<>();
        Map<UUID, Money> expenses = new LinkedHashMap<>();
        Money countDifferences = Money.ZERO;
        Money bankDifferences = Money.ZERO;
        for (Line line : lines) {
            switch (line.kind()) {
                case MEMBER_PAYMENT -> {
                    if (line.reversal() && Objects.requireNonNull(line.originalDate()).isBefore(from)) {
                        paymentReversals = paymentReversals.plus(line.amount());
                    }
                }
                case OTHER_INCOME -> otherIncome.merge(category(line), line.amount(), Money::plus);
                case EXPENSE -> expenses.merge(category(line), line.amount().negate(), Money::plus);
                case COUNT_DIFFERENCE -> countDifferences = countDifferences.plus(line.amount());
                case BANK_DIFFERENCE -> bankDifferences = bankDifferences.plus(line.amount());
                case HANDOVER_OUT, HANDOVER_IN -> {
                    // money moves from the cash to the bank: the two lines of a handover add up to zero
                }
            }
        }
        dropZeros(byChargeType);
        dropZeros(otherIncome);
        dropZeros(expenses);
        return new IncomeExpense(from, to, byChargeType, advances, paymentReversals, otherIncome, expenses,
                countDifferences, bankDifferences, balances.cashStart(), balances.cashEnd(), balances.bankStart(),
                balances.bankEnd());
    }

    private static UUID category(Line line) {
        return Objects.requireNonNull(line.categoryId(), "An expense or other income line has a category");
    }

    /** A type or category whose lines net to {@code 0.00} is left out of the lists. */
    private static void dropZeros(Map<UUID, Money> amounts) {
        amounts.values().removeIf(amount -> amount.signum() == 0);
    }
}
