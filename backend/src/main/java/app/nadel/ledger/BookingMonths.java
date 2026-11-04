package app.nadel.ledger;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The billing month a money entry of another module about a day belongs to (feature 018 §2.2: "a line dated in a closed
 * billing month belongs to the earliest open billing month and keeps its date, as payments do"). The rule of the
 * ledger's own bookings: the month of the day while it is open, otherwise the earliest open month after it; a month that
 * does not exist yet is opened.
 */
public interface BookingMonths {

    /**
     * The open month an entry dated {@code date} is booked in. Runs in the caller's transaction
     * ({@code Propagation.MANDATORY}) for the cooperative bound to it, and leaves that month locked {@code FOR SHARE}
     * until the transaction ends, so a closing waits for the entry (or the entry waits for the closing and then takes
     * the next open month). Take it before any lock of the caller's own module (lock order in the ledger README).
     *
     * @param date the day of the entry
     * @throws IllegalStateException when no cooperative is bound
     */
    YearMonth bookFor(LocalDate date);
}
