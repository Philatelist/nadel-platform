package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Income and expense of a period for the general meeting («Доходи та витрати», feature 018 §2.12, example 12;
 * technical considerations 2.7). Every amount is an exact sum of journal lines dated in the period (and of the
 * ledger's allocations for the split of member payments); nothing is rounded.
 * <ul>
 * <li><b>Member income</b>: the payments received in the period and not reversed by its end, split by charge type as
 * they were allocated at the end of the period ({@code memberByChargeType}) with the part on no charge as
 * {@code advances} («Аванси»), plus {@code paymentReversals} (zero or negative): the reversals made in the period of
 * payments received before it. A payment received and reversed in the period counts in neither.</li>
 * <li><b>Other income</b> per income category, net of its reversals made in the period.</li>
 * <li><b>Expenses</b> per expense category, positive, net of their reversals made in the period.</li>
 * <li><b>Differences</b>: the booked count differences and bank differences of the period, signed, net of
 * reversals.</li>
 * </ul>
 * {@link #result()} = {@link #income()} − {@link #expenses()} + {@link #differences()} equals the change of cash plus
 * bank over the period ({@code cashEnd + bankEnd − cashStart − bankStart}): handovers move money between the two
 * accounts and cancel out.
 *
 * @param cashStart the cash balance at the start of {@code from}
 * @param cashEnd   the cash balance at the end of {@code to}
 * @param bankStart the bank balance at the start of {@code from}
 * @param bankEnd   the bank balance at the end of {@code to}
 */
public record IncomeExpense(LocalDate from, LocalDate to, Map<UUID, Money> memberByChargeType, Money advances,
        Money paymentReversals, Map<UUID, Money> otherIncomeByCategory, Map<UUID, Money> expensesByCategory,
        Money countDifferences, Money bankDifferences, Money cashStart, Money cashEnd, Money bankStart,
        Money bankEnd) {

    public IncomeExpense {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(advances, "advances");
        Objects.requireNonNull(paymentReversals, "paymentReversals");
        Objects.requireNonNull(countDifferences, "countDifferences");
        Objects.requireNonNull(bankDifferences, "bankDifferences");
        Objects.requireNonNull(cashStart, "cashStart");
        Objects.requireNonNull(cashEnd, "cashEnd");
        Objects.requireNonNull(bankStart, "bankStart");
        Objects.requireNonNull(bankEnd, "bankEnd");
        memberByChargeType = Collections.unmodifiableMap(new LinkedHashMap<>(memberByChargeType));
        otherIncomeByCategory = Collections.unmodifiableMap(new LinkedHashMap<>(otherIncomeByCategory));
        expensesByCategory = Collections.unmodifiableMap(new LinkedHashMap<>(expensesByCategory));
    }

    /** The member payments of the period: by charge type, advances and the reversals of earlier payments. */
    public Money memberIncome() {
        return sum(memberByChargeType).plus(advances).plus(paymentReversals);
    }

    /** Member income plus other income. */
    public Money income() {
        return memberIncome().plus(sum(otherIncomeByCategory));
    }

    /** Every expense category's amount, positive. */
    public Money expenses() {
        return sum(expensesByCategory);
    }

    /** Count differences plus bank differences, signed. */
    public Money differences() {
        return countDifferences.plus(bankDifferences);
    }

    /** Income − expenses + differences: the change of cash plus bank over the period. */
    public Money result() {
        return income().minus(expenses()).plus(differences());
    }

    private static Money sum(Map<UUID, Money> amounts) {
        return amounts.values().stream().reduce(Money.ZERO, Money::plus);
    }
}
