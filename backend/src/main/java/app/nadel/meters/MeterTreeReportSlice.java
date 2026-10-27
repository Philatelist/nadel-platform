package app.nadel.meters;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A part of the range a node of a {@link MeterTreeReport} spent under one parent, from {@code from} to {@code to}
 * (money rules 6.4, tree slices): the values at both ends and the consumption between them, as {@code GET /meter-tree}
 * serves them.
 *
 * @param from        the first day of the slice
 * @param to          the last day of the slice (the next slice of the node starts on it)
 * @param start       the value on {@code from}; on a replacement day the new meter's (its initial reading)
 * @param end         the value on {@code to}; on a replacement day the old meter's (its final reading)
 * @param consumption the value on {@code to} minus the value on {@code from} over the chain; unknown
 *                    ({@link MeterTreeUnknownReason#VALUE_UNKNOWN}) when a value at an end is
 */
public record MeterTreeReportSlice(LocalDate from, LocalDate to, MeterTreeValue start, MeterTreeValue end,
        MeterTreeQuantity consumption) {

    public MeterTreeReportSlice {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(consumption, "consumption");
    }
}
