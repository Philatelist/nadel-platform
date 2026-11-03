package app.nadel.treasury.internal;

import app.nadel.money.Money;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.StatementRules.Figures;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The books' side of a monthly bank reconciliation and its differences from the statement (feature 018 §2.7, worked
 * examples 2.17 #7 and #8; technical considerations 2.5), pure. A month is a calendar month by the date of the lines.
 * <ul>
 *   <li>{@code opening}: the bank balance at the start of the month's first day (the {@link AccountBalances} rule);
 *       for the month that contains the opening date, the opening amount (the balance at the start of that date:
 *       nothing earlier is known).</li>
 *   <li>{@code incoming} and {@code outgoing}: the month's receipts and spendings from the opening date on, both
 *       <b>positive</b>, as a statement shows them. A reversal counts against the direction of the line it reverses:
 *       the reversal of a receipt (a negative amount) lowers {@code incoming}, the reversal of a spending (a positive
 *       amount) lowers {@code outgoing}, so a mistaken entry and its reversal in one month cancel out.</li>
 *   <li>{@code closing}: {@code opening + incoming − outgoing}, the balance at the end of the month's last day.</li>
 * </ul>
 * The differences are statement − books, figure by figure; the month balances when every one is 0.00. Nothing is
 * rounded: movements are exact {@code Money}.
 */
final class BankComparison {

    private BankComparison() {
    }

    /**
     * The sum of the bank lines of one day that share a sign and whether they are reversals (the grouping of
     * {@code BankReconciliationQueries#flows}).
     *
     * @param reversal the lines reverse other lines
     * @param total    the sum, of the one sign of those lines
     */
    record Flow(LocalDate day, boolean reversal, Money total) {

        Flow {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(total, "total");
        }

        /**
         * Whether the lines count in {@code incoming}: receipts (positive originals) and reversals of receipts
         * (negative reversals); all others count in {@code outgoing}.
         */
        boolean incoming() {
            return reversal ? total.signum() < 0 : total.signum() > 0;
        }
    }

    /**
     * The books of the month.
     *
     * @param flows the bank flows from the opening date to (at least) the month's last day; others are ignored
     * @throws IllegalArgumentException when the month ends before the opening date
     */
    static Figures books(Opening opening, YearMonth month, Collection<Flow> flows) {
        LocalDate last = month.atEndOfMonth();
        if (last.isBefore(opening.date())) {
            throw new IllegalArgumentException("The month ends before the opening date");
        }
        LocalDate start = month.atDay(1).isBefore(opening.date()) ? opening.date() : month.atDay(1);
        Money balance = opening.amount();
        Money incoming = Money.ZERO;
        Money outgoing = Money.ZERO;
        for (Flow flow : flows) {
            LocalDate day = flow.day();
            if (day.isBefore(opening.date()) || day.isAfter(last)) {
                continue;
            }
            if (day.isBefore(start)) {
                balance = balance.plus(flow.total());
            } else if (flow.incoming()) {
                incoming = incoming.plus(flow.total());
            } else {
                outgoing = outgoing.minus(flow.total());
            }
        }
        return new Figures(balance, incoming, outgoing, balance.plus(incoming).minus(outgoing));
    }

    /** Statement − books, figure by figure. */
    static Figures differences(Figures statement, Figures books) {
        return new Figures(statement.opening().minus(books.opening()),
                statement.incoming().minus(books.incoming()), statement.outgoing().minus(books.outgoing()),
                statement.closing().minus(books.closing()));
    }

    /** Whether there are differences and every one is 0.00 (false without a statement). */
    static boolean balanced(@Nullable Figures differences) {
        return differences != null && differences.opening().signum() == 0 && differences.incoming().signum() == 0
                && differences.outgoing().signum() == 0 && differences.closing().signum() == 0;
    }
}
