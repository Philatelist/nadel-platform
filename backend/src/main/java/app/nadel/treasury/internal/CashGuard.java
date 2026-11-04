package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.treasury.internal.AccountBalances.DayTotal;
import app.nadel.treasury.internal.AccountBalances.Opening;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The no-negative-cash rule (feature 018 §2.2, technical considerations 2.5), pure: an entry that takes money out of
 * the cash ({@code delta < 0}) dated {@code t} is refused when the cash balance would go below 0.00 on {@code t} or on
 * any later day. The balance after the entry on a day {@code d ≥ t} is the balance at the end of {@code d} plus
 * {@code delta}, so the entry fits exactly when the lowest balance from {@code t} on (the running balance at the end of
 * {@code t} and of every later movement day) plus {@code delta} is 0.00 or more. A back-dated entry is therefore refused
 * when a later day would go negative although its own day stays positive (worked example 5).
 * <p>
 * The caller holds the cash account row {@code FOR UPDATE} before it reads the day totals (READ COMMITTED: the totals
 * then include every committed entry), so two entries saved at the same moment are checked one after the other and
 * never together take the cash below zero. Member payment reversals are not checked (the money is already gone).
 */
final class CashGuard {

    private CashGuard() {
    }

    /** The lowest balance from the entry's day on (before the entry) and the earliest day it is reached. */
    record Shortfall(Money balance, LocalDate date) {

        Shortfall {
            Objects.requireNonNull(balance, "balance");
            Objects.requireNonNull(date, "date");
        }
    }

    /**
     * Refuses the entry with 409 {@code CASH_INSUFFICIENT} (extension fields {@code balance} and {@code date}) when it
     * would make the cash negative on {@code day} or a later day.
     *
     * @param opening the cash opening balance; the caller has refused an entry without one or dated before it
     * @param totals  the day totals of the cash account from the opening date on, in any order
     * @param day     the date of the entry, not before the opening date
     * @param delta   the signed amount of the entry, below 0.00
     * @throws ApiProblemException 409 {@code CASH_INSUFFICIENT}
     */
    static void check(Opening opening, Collection<DayTotal> totals, LocalDate day, Money delta) {
        Optional<Shortfall> shortfall = shortfall(opening, totals, day, delta);
        if (shortfall.isPresent()) {
            throw ApiProblemException.cashInsufficient(shortfall.get().balance().toString(), shortfall.get().date());
        }
    }

    /**
     * The lowest balance and its earliest day when the entry does not fit, empty when it does.
     *
     * @throws IllegalArgumentException when {@code delta} is not below 0.00 or {@code day} is before the opening date
     */
    static Optional<Shortfall> shortfall(Opening opening, Collection<DayTotal> totals, LocalDate day, Money delta) {
        if (delta.signum() >= 0) {
            throw new IllegalArgumentException("Only an entry that takes money out of the cash is checked");
        }
        if (day.isBefore(opening.date())) {
            throw new IllegalArgumentException("The entry is dated before the opening date");
        }
        List<DayTotal> counted = totals.stream()
                .filter(total -> !total.day().isBefore(opening.date()))
                .sorted(Comparator.comparing(DayTotal::day))
                .toList();
        Money running = opening.amount();
        for (DayTotal total : counted) {
            if (!total.day().isAfter(day)) {
                running = running.plus(total.total());
            }
        }
        // the balance at the end of the entry's day, then every later day with movements
        Money lowest = running;
        LocalDate lowestDay = day;
        for (DayTotal total : counted) {
            if (total.day().isAfter(day)) {
                running = running.plus(total.total());
                if (running.minus(lowest).signum() < 0) {
                    lowest = running;
                    lowestDay = total.day();
                }
            }
        }
        return lowest.plus(delta).signum() < 0 ? Optional.of(new Shortfall(lowest, lowestDay)) : Optional.empty();
    }
}
