package app.nadel.meters;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The value of a node's meter on a day in a {@link MeterTreeReport} (money rules 6.4): a reading, a value calculated
 * between two readings, or not known.
 *
 * @param kind          real, calculated or unknown
 * @param meterId       the meter of the node's chain the value belongs to (the old one at the end of a slice that ends
 *                      on a replacement day)
 * @param value         the exact value; null when {@link MeterValueKind#UNKNOWN}. A calculated value is an exact
 *                      fraction, rounded only for display ({@link Quantity#toMeterValue()})
 * @param lastReadingOn for {@link MeterValueKind#UNKNOWN}, the day after which there is no reading; null when there is
 *                      none before the day either, and for the other kinds
 */
public record MeterTreeValue(MeterValueKind kind, UUID meterId, @Nullable Quantity value,
        @Nullable LocalDate lastReadingOn) {

    public MeterTreeValue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(meterId, "meterId");
        if ((kind == MeterValueKind.UNKNOWN) != (value == null)) {
            throw new IllegalArgumentException("A value is known unless its kind is UNKNOWN");
        }
        if (kind != MeterValueKind.UNKNOWN && lastReadingOn != null) {
            throw new IllegalArgumentException("Only an unknown value has a last reading date");
        }
    }
}
