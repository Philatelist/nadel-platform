package app.nadel.meters;

import app.nadel.meters.WaterConnections.ConnectedPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The water use of one plot from one water source over a date range ({@link MeterReports#waterUse}, the water report
 * of feature 013 §3.5): the days it was connected to that source and the cubic metres its water meters measured on
 * those days. A plot connected to two sources in the range (it changed source) has one row per source.
 * <p>
 * The cubic metres are the range consumption of feature 010 (money rules 6.4) restricted to the connected days: one
 * slice per connection, from the later of the range start and the connection's first day to the earlier of the range
 * end and the day after its last connected day (the tree slices of 6.4, {@link ConsumptionCalculator#splitByParent}),
 * so a plot connected for the whole range has exactly the range consumption of its meters, and neighbouring
 * connections share their boundary day. Each meter of the plot counts only in the days it worked (a replacement adds
 * up). A value that is not known is never zero.
 *
 * @param plotId        the plot
 * @param plotNumber    the plot number as the cooperative writes it
 * @param street        the plot's street; null when it has none
 * @param source        the kind of source
 * @param sourceMeterId the well's common consumer meter; null for the cooperative's water supply
 * @param sourceName    the well's meter name (user content); null for the cooperative's water supply
 * @param periods       the connected days to this source within the range, clipped to it, oldest first; not empty
 * @param connectedDays the number of connected days in the range (both ends of each period included); at least 1
 * @param meterId       the plot's water meter shown: the one installed last among the meters that worked on a day of
 *                      the connected slices; null when the plot had no such meter (nothing measured: then
 *                      {@code cubicMetres} and {@code reason} are null, an empty cell, not an unknown one)
 * @param meterSerial   that meter's serial; null when there is no meter
 * @param cubicMetres   the exact m³ of the connected days; null when there is no meter or when a value is not known
 * @param calculated    true when a value it was read from was interpolated between two readings
 * @param reason        {@link MeterTreeUnknownReason#VALUE_UNKNOWN} when a value at an end of a slice is not known (no
 *                      approved reading after it); null otherwise
 * @param lastReadingOn when unknown, the latest day after which a meter needed has no reading; null otherwise
 */
public record WaterUseRow(UUID plotId, String plotNumber, @Nullable String street, WaterSourceKind source,
        @Nullable UUID sourceMeterId, @Nullable String sourceName, List<ConnectedPeriod> periods, int connectedDays,
        @Nullable UUID meterId, @Nullable String meterSerial, @Nullable Quantity cubicMetres, boolean calculated,
        @Nullable MeterTreeUnknownReason reason, @Nullable LocalDate lastReadingOn) {

    public WaterUseRow {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(plotNumber, "plotNumber");
        Objects.requireNonNull(source, "source");
        periods = List.copyOf(periods);
        if (periods.isEmpty() || connectedDays < 1) {
            throw new IllegalArgumentException("A water use row has connected days");
        }
        if ((source == WaterSourceKind.METER) != (sourceMeterId != null)) {
            throw new IllegalArgumentException("A well names its meter, the water supply has none");
        }
        if (meterId == null && (cubicMetres != null || reason != null || calculated || lastReadingOn != null)) {
            throw new IllegalArgumentException("Without a meter nothing is measured");
        }
        if (meterId != null && (cubicMetres == null) == (reason == null)) {
            throw new IllegalArgumentException("The cubic metres are either known or unknown with a reason");
        }
        if (cubicMetres == null && calculated) {
            throw new IllegalArgumentException("An unknown quantity is not calculated");
        }
        if (cubicMetres != null && lastReadingOn != null) {
            throw new IllegalArgumentException("Only an unknown quantity has a last reading date");
        }
    }

    /** Whether the cubic metres are known (a meter measured them and every value needed is known). */
    public boolean isKnown() {
        return cubicMetres != null;
    }
}
