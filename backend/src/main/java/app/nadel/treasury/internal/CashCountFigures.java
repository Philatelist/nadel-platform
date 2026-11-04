package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.CashCountAct.Status;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;

/**
 * The figures of a cash count (feature 018 §2.6, technical considerations 2.5), pure: the book balance is the cash
 * balance at the end of the count's day ({@link AccountBalances#balanceAt}) over the day totals the caller read (at a
 * save: under the cash account lock, so every committed line counts); the difference is the counted cash minus the book
 * balance (a shortage negative, a surplus positive). Nothing is rounded: both are exact {@code Money}.
 */
final class CashCountFigures {

    private CashCountFigures() {
    }

    /** The book balance, the counted cash and their difference. */
    record Figures(Money bookBalance, Money counted, Money difference) {

        Figures {
            Objects.requireNonNull(bookBalance, "bookBalance");
            Objects.requireNonNull(counted, "counted");
            Objects.requireNonNull(difference, "difference");
        }
    }

    /**
     * The figures of a count of {@code counted} on {@code day}.
     *
     * @throws IllegalArgumentException when {@code day} is before the opening date (the caller refuses it first)
     */
    static Figures of(Opening opening, Collection<DayTotal> totals, LocalDate day, Money counted) {
        if (day.isBefore(opening.date())) {
            throw new IllegalArgumentException("The count is dated before the opening date");
        }
        Money book = Objects.requireNonNull(AccountBalances.balanceAt(opening, totals, day), "book balance");
        return new Figures(book, counted, counted.minus(book));
    }

    /** {@code MATCHES} for 0.00, otherwise {@code BOOKED} once booked and {@code UNRESOLVED} before. */
    static Status status(Money difference, boolean booked) {
        if (difference.signum() == 0) {
            return Status.MATCHES;
        }
        return booked ? Status.BOOKED : Status.UNRESOLVED;
    }
}
