package app.nadel.ledger.internal;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the billing month a charge is booked in ({@link BillingCalendar}) and opens it when the cooperative does not
 * have it yet. Every booking path goes through here. Runs inside the caller's transaction and leaves the month locked
 * {@code FOR SHARE} until its end, so a closing ({@link MonthClosing}, {@code FOR UPDATE}) waits for the booking, or the
 * booking waits for the closing and then sees the month closed and asks the calendar again. The status is read as a
 * scalar under the lock, never from a managed entity that an earlier read of this transaction could have left stale.
 */
@Component
class BillingMonths {

    /** More attempts than months can close while one booking runs; reaching it means a bug, not a race. */
    private static final int ATTEMPTS = 12;

    private final BillingMonthRepository months;
    private final Clock clock;

    BillingMonths(BillingMonthRepository months, Clock clock) {
        this.months = months;
        this.clock = clock;
    }

    /**
     * The open month to book a charge about {@code date} in, locked {@code FOR SHARE}; opened when it does not exist.
     *
     * @param tenantId the caller's cooperative (from the token)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    BillingMonthRecord openForBooking(UUID tenantId, LocalDate date) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            List<MonthState> states = months.statesInOrder().stream()
                    .map(m -> new MonthState(YearMonth.from(m.month()),
                            m.status() == BillingMonthRecord.Status.CLOSED))
                    .toList();
            YearMonth target = BillingCalendar.bookingMonth(date, states, LocalDate.now(clock));
            months.insertIfAbsent(tenantId, target.atDay(1));
            String status = months.lockStatusForShare(target.atDay(1));
            if (BillingMonthRecord.Status.OPEN.name().equals(status)) {
                // the id and the month of a row never change, so a managed instance serves for both
                return months.findByMonth(target.atDay(1)).orElseThrow();
            }
        }
        throw new IllegalStateException("No open billing month found for a booking");
    }

    /**
     * The month itself, locked {@code FOR SHARE} and opened when it does not exist, for the month run, which books in
     * its own month only; empty when the month is closed (or counts as closed: a missing month before the last
     * closed one). The caller has refused a month after the current one.
     *
     * @param tenantId the caller's cooperative (from the token)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<BillingMonthRecord> openMonthForRun(UUID tenantId, YearMonth month) {
        if (!isOpen(month)) {
            return Optional.empty();
        }
        months.insertIfAbsent(tenantId, month.atDay(1));
        String status = months.lockStatusForShare(month.atDay(1));
        if (!BillingMonthRecord.Status.OPEN.name().equals(status)) {
            return Optional.empty();
        }
        return months.findByMonth(month.atDay(1));
    }

    /**
     * Records that the month run of the open month was booked ({@code run_at}). The caller holds the month
     * {@code FOR SHARE} ({@link #openMonthForRun}). Clears the persistence context.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void markRun(YearMonth month, Instant at) {
        months.markRun(month.atDay(1), at);
    }

    /**
     * Whether a charge about the month would be booked in the month itself: it is open, or does not exist yet after
     * the last closed month. Reads the committed state without a lock (a preview).
     */
    boolean isOpen(YearMonth month) {
        List<MonthState> states = months.statesInOrder().stream()
                .map(m -> new MonthState(YearMonth.from(m.month()), m.status() == BillingMonthRecord.Status.CLOSED))
                .toList();
        return BillingCalendar.bookingMonth(month.atDay(1), states, LocalDate.now(clock)).equals(month);
    }
}
