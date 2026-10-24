package app.nadel.meters;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A quantity of a {@link MeterTreeReport} row in kWh (a consumption, a sum of children, losses): an exact
 * {@link Quantity}, or not known with the reason. An unknown quantity is never zero.
 *
 * @param kwh           the exact quantity; null when unknown. Losses may be negative (the children used more than the
 *                      node shows)
 * @param calculated    true when a value it was read from was interpolated between two readings; false when unknown
 * @param reason        why it is unknown; null when known
 * @param lastReadingOn when unknown, the latest day after which the meter that is not known has no reading; null when
 *                      known, or when that meter has no reading before the day either
 */
public record MeterTreeQuantity(@Nullable Quantity kwh, boolean calculated, @Nullable MeterTreeUnknownReason reason,
        @Nullable LocalDate lastReadingOn) {

    public MeterTreeQuantity {
        if ((kwh == null) == (reason == null)) {
            throw new IllegalArgumentException("A quantity is either known or unknown with a reason");
        }
        if (kwh == null && calculated) {
            throw new IllegalArgumentException("An unknown quantity is not calculated");
        }
        if (kwh != null && lastReadingOn != null) {
            throw new IllegalArgumentException("Only an unknown quantity has a last reading date");
        }
    }

    /** A known quantity. */
    public static MeterTreeQuantity known(Quantity kwh, boolean calculated) {
        return new MeterTreeQuantity(Objects.requireNonNull(kwh, "kwh"), calculated, null, null);
    }

    /** A quantity that is not known, with the reason. */
    public static MeterTreeQuantity unknown(MeterTreeUnknownReason reason, @Nullable LocalDate lastReadingOn) {
        return new MeterTreeQuantity(null, false, Objects.requireNonNull(reason, "reason"), lastReadingOn);
    }

    /** Whether the quantity is known. */
    public boolean isKnown() {
        return kwh != null;
    }
}
