package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One day of an account's book (feature 018 §2.12): the balance at the start of the day, the day's lines oldest first,
 * the sum of its receipts and of its spendings (both {@code 0.00} or more) and the balance at the end of the day, which
 * is the balance of that day on «Каса і банк» ({@code AccountBalances}). {@code opening + receipts − spendings =
 * closing}; nothing is rounded.
 */
public record CashBookDay(LocalDate day, Money opening, List<MovementLine> lines, Money receipts, Money spendings,
        Money closing) {

    public CashBookDay {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(spendings, "spendings");
        Objects.requireNonNull(closing, "closing");
        lines = List.copyOf(lines);
    }
}
