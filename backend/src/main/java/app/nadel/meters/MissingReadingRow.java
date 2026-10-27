package app.nadel.meters;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A meter that worked through a date range and has no point in it ({@link MeterReports#missingReadings}, the missing
 * readings report of feature 013 §3.6): no approved reading dated in the range, and neither installed nor removed in
 * it (its initial or final value would be a point of the range, money rules 6.4).
 *
 * @param meterId                the meter
 * @param resource               electricity or water
 * @param kind                   plot meter, substation, quarter meter or common consumer
 * @param plotId                 the plot of a plot meter; null for the other kinds
 * @param plotNumber             that plot's number; null for the other kinds
 * @param street                 that plot's street; null for the other kinds or a plot without a street
 * @param name                   the name of a meter that is not a plot meter (user content); null for a plot meter
 * @param serial                 the serial number
 * @param installedOn            the day it was installed (before the range)
 * @param lastReadingOn          the day of its last point before the range: the latest approved reading, else the
 *                               installation
 * @param lastReadingValue       the value of that point
 * @param lastReadingInitial     true when that point is the initial value of the installation, not a reading
 * @param daysSinceLastReading   the days from {@code lastReadingOn} to the last day of the range
 * @param pendingApprovalInRange whether a member's reading dated in the range is waiting for approval ({@code PENDING})
 */
public record MissingReadingRow(UUID meterId, MeterResource resource, MeterKind kind, @Nullable UUID plotId,
        @Nullable String plotNumber, @Nullable String street, @Nullable String name, String serial,
        LocalDate installedOn, LocalDate lastReadingOn, MeterValue lastReadingValue, boolean lastReadingInitial,
        long daysSinceLastReading, boolean pendingApprovalInRange) {

    public MissingReadingRow {
        Objects.requireNonNull(meterId, "meterId");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(installedOn, "installedOn");
        Objects.requireNonNull(lastReadingOn, "lastReadingOn");
        Objects.requireNonNull(lastReadingValue, "lastReadingValue");
        if ((kind == MeterKind.PLOT) != (plotId != null)) {
            throw new IllegalArgumentException("A plot meter names its plot, the other kinds none");
        }
        if (daysSinceLastReading < 1) {
            throw new IllegalArgumentException("The last point lies before the range");
        }
    }
}
