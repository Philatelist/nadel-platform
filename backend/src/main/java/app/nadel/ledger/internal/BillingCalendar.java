package app.nadel.ledger.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Which billing month a charge is booked in (feature 011, functional spec 2.2). Pure: no Spring, no database, no
 * clock; the caller passes the cooperative's months and today.
 * <p>
 * The rule: the month of the charge's date, but never a month after the current one. When that month is open (or does
 * not exist yet, so it is opened) the charge goes there. When it is closed (months close in order, so a month that does
 * not exist before the last closed month counts as closed), the charge goes to the earliest open month after it that is
 * not after the current month, and when there is none, to a new month right after the last closed one. The charge keeps
 * its own service period and date; only the month it is booked in moves.
 */
final class BillingCalendar {

    /** A billing month of the cooperative and whether it is closed. */
    record MonthState(YearMonth month, boolean closed) {

        MonthState {
            Objects.requireNonNull(month, "month");
        }
    }

    private BillingCalendar() {
    }

    /**
     * @param date   the date the charge is about (an opening balance's date, the month of the charge a reversal
     *               cancels)
     * @param months every billing month the cooperative has, in any order
     * @param today  today in the cooperative's time zone
     * @return the month to book the charge in; it may not exist yet (the caller opens it)
     */
    static YearMonth bookingMonth(LocalDate date, Collection<MonthState> months, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth own = YearMonth.from(date);
        YearMonth wanted = own.isAfter(current) ? current : own;
        Optional<YearMonth> lastClosed = months.stream().filter(MonthState::closed).map(MonthState::month)
                .max(Comparator.naturalOrder());
        Optional<MonthState> existing = months.stream().filter(m -> m.month().equals(wanted)).findFirst();
        boolean wantedIsOpen = existing.map(m -> !m.closed())
                .orElseGet(() -> lastClosed.map(wanted::isAfter).orElse(true));
        if (wantedIsOpen) {
            return wanted;
        }
        return months.stream()
                .filter(m -> !m.closed() && m.month().isAfter(wanted) && !m.month().isAfter(current))
                .map(MonthState::month).min(Comparator.naturalOrder())
                .orElseGet(() -> lastClosed.orElseThrow().plusMonths(1));
    }
}
