package app.nadel.treasury;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the act of a cash count shows («Акт перерахунку каси», feature 018 §2.6), for the documents module: the moment
 * and the day of the count, the book balance it kept, the counted cash, the difference (counted minus book), the
 * explanation (user content, shown as typed) and, once booked, how the difference was booked. Amounts are exact
 * {@code Money}; nothing is computed by the reader. No names of people: the act's signers come from the documents
 * module's own sources.
 *
 * @param countedAt   the moment of the count
 * @param countedOn   its day in Europe/Kyiv
 * @param explanation null when none was given (a matching count may have none)
 * @param booking     null while the difference is not booked (and always for a matching count)
 */
public record CashCountAct(UUID countId, Instant countedAt, LocalDate countedOn, Money bookBalance, Money counted,
        Money difference, Status status, @Nullable String explanation, @Nullable Booking booking) {

    public CashCountAct {
        Objects.requireNonNull(countId, "countId");
        Objects.requireNonNull(countedAt, "countedAt");
        Objects.requireNonNull(countedOn, "countedOn");
        Objects.requireNonNull(bookBalance, "bookBalance");
        Objects.requireNonNull(counted, "counted");
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(status, "status");
    }

    /** The state of a count (the contract's {@code CashCountStatus}). */
    public enum Status {

        /** The difference is 0.00 («Збігається»). */
        MATCHES,

        /** A difference not booked yet. */
        UNRESOLVED,

        /** The difference is booked as a journal line; it stays booked when that line is reversed. */
        BOOKED
    }

    /**
     * The booked line of the difference: dated the count's day, in {@code bookingMonth}, of the signed
     * {@code amount} (the difference: a surplus positive, a shortage negative), with the chairman's reason.
     *
     * @param reversal null while the line is not reversed
     */
    public record Booking(LocalDate date, YearMonth bookingMonth, Money amount, String reason, Instant bookedAt,
            @Nullable Reversal reversal) {

        public Booking {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(bookingMonth, "bookingMonth");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(bookedAt, "bookedAt");
        }
    }

    /** The reversal of the booked line: its day and reason. */
    public record Reversal(LocalDate date, String reason) {

        public Reversal {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
