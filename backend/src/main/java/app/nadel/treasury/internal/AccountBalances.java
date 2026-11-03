package app.nadel.treasury.internal;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The balance rule of a money account (feature 018 §2.1, technical considerations 2.5), pure. The opening balance is
 * the balance at the start of its date; the balance at the end of a day is the opening amount plus every movement
 * dated from the opening date up to and including that day (money in positive, money out negative, a reversed entry
 * and its reversal both counted). A movement dated before the opening date counts in no balance. Without an opening
 * balance an account has no balance at all. Nothing is rounded: movements are exact {@code Money}.
 */
final class AccountBalances {

    private AccountBalances() {
    }

    /** The opening balance of an account: the balance at the start of {@code date}. */
    record Opening(Money amount, LocalDate date) {

        Opening {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(date, "date");
        }
    }

    /** The sum of an account's movements dated on one day. */
    record DayTotal(LocalDate day, Money total) {

        DayTotal {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(total, "total");
        }
    }

    /**
     * Whether a movement dated {@code day} is listed «до вхідного залишку» and left out of every balance: it is dated
     * before the opening date. False while the account has no opening balance (nothing counts then, but nothing is
     * before it either).
     */
    static boolean beforeOpening(@Nullable Opening opening, LocalDate day) {
        return opening != null && day.isBefore(opening.date());
    }

    /**
     * The balance at the end of {@code day}: the opening amount plus the totals of the days from the opening date to
     * {@code day}. The end of the day before the opening date is the opening amount itself.
     *
     * @param totals the day totals of the account; days outside the counted range are ignored
     * @return null without an opening balance, or when {@code day} is before the day before the opening date (no
     *         balance is known that early)
     */
    static @Nullable Money balanceAt(@Nullable Opening opening, Collection<DayTotal> totals, LocalDate day) {
        if (opening == null || day.isBefore(opening.date().minusDays(1))) {
            return null;
        }
        Money balance = opening.amount();
        for (DayTotal total : totals) {
            if (!total.day().isBefore(opening.date()) && !total.day().isAfter(day)) {
                balance = balance.plus(total.total());
            }
        }
        return balance;
    }

    /** Whether a balance is below {@code 0.00}; false without one. */
    static boolean negative(@Nullable Money balance) {
        return balance != null && balance.signum() < 0;
    }
}
