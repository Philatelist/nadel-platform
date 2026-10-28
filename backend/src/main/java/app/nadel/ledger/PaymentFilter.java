package app.nadel.ledger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which payments {@link LedgerReadModel#payments} lists: those booked in a range of billing months, or those received
 * in a range of days (exactly one of the two ranges is given), optionally of one source and of some plots.
 *
 * @param fromMonth the first billing month, with {@code toMonth}; null when the days choose
 * @param toMonth   the last billing month, not before {@code fromMonth}
 * @param from      the first day received, with {@code to}; null when the months choose
 * @param to        the last day received, not before {@code from}
 * @param source    the source; null means both
 * @param plotIds   the plots (a street resolved by the caller); null means every plot, empty gives no rows
 */
public record PaymentFilter(@Nullable YearMonth fromMonth, @Nullable YearMonth toMonth, @Nullable LocalDate from,
        @Nullable LocalDate to, @Nullable PaymentSource source, @Nullable Set<UUID> plotIds) {

    public PaymentFilter {
        boolean months = fromMonth != null && toMonth != null;
        boolean days = from != null && to != null;
        if (months == days || (fromMonth == null) != (toMonth == null) || (from == null) != (to == null)) {
            throw new IllegalArgumentException("Either a range of billing months or a range of days is given");
        }
        if (months && toMonth.isBefore(fromMonth) || days && to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends before it starts");
        }
        plotIds = plotIds == null ? null : Set.copyOf(plotIds);
    }

    /** The payments booked in the billing months from–to, of every source and plot. */
    public static PaymentFilter months(YearMonth fromMonth, YearMonth toMonth) {
        return new PaymentFilter(Objects.requireNonNull(fromMonth, "fromMonth"),
                Objects.requireNonNull(toMonth, "toMonth"), null, null, null, null);
    }

    /** The payments received on the days from–to, of every source and plot. */
    public static PaymentFilter days(LocalDate from, LocalDate to) {
        return new PaymentFilter(null, null, Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"),
                null, null);
    }

    /** The same payments of one source only (null: both). */
    public PaymentFilter withSource(@Nullable PaymentSource paymentSource) {
        return new PaymentFilter(fromMonth, toMonth, from, to, paymentSource, plotIds);
    }

    /** The same payments of these plots only (null: every plot). */
    public PaymentFilter withPlots(@Nullable Set<UUID> plots) {
        return new PaymentFilter(fromMonth, toMonth, from, to, source, plots);
    }
}
