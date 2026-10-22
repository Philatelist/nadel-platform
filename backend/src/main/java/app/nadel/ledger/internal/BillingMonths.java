package app.nadel.ledger.internal;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the billing month a charge is booked in ({@link BillingCalendar}) and opens it when the cooperative does not
 * have it yet. Runs inside the caller's transaction and leaves the month locked {@code FOR SHARE} until its end, so a
 * closing (slice 2, {@code FOR UPDATE}) waits for the booking or the booking sees the month closed and asks the
 * calendar again.
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
            List<MonthState> states = months.findAllInOrder().stream()
                    .map(m -> new MonthState(m.yearMonth(), m.isClosed())).toList();
            YearMonth target = BillingCalendar.bookingMonth(date, states, LocalDate.now(clock));
            months.insertIfAbsent(tenantId, target.atDay(1));
            BillingMonthRecord month = months.findByMonthForShare(target.atDay(1)).orElseThrow();
            if (!month.isClosed()) {
                return month;
            }
        }
        throw new IllegalStateException("No open billing month found for a booking");
    }
}
