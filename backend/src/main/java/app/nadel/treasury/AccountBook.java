package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * The book of a money account for a period (feature 018 §2.12: «Касова книга», «Рух коштів по банку»): the balance at
 * the start of {@code from} and at the end of {@code to}, the days of the period that have lines (oldest first) and,
 * for the bank, the reconciliation status of every calendar month the period touches. The closing balance is the
 * opening plus every line of the days; a day without lines is not listed (its balance is the previous day's closing).
 *
 * @param months the bank months from the month of {@code from} to the month of {@code to}, oldest first; empty for
 *               the cash
 */
public record AccountBook(MoneyAccountKind account, LocalDate from, LocalDate to, Money opening, Money closing,
        List<CashBookDay> days, List<BankMonth> months) {

    public AccountBook {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(closing, "closing");
        days = List.copyOf(days);
        months = List.copyOf(months);
    }

    /** The reconciliation status of a bank month; a month without a reconciliation is {@code NOT_RECONCILED}. */
    public record BankMonth(YearMonth month, BankMonthStatus status) {

        public BankMonth {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(status, "status");
        }
    }
}
