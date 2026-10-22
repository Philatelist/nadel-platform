package app.nadel.meters;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the ledger needs to know about the cooperative's meters as a network (feature 011). Today: the plot meters not
 * read for a while, for the check list before a billing month is closed. The cooperative is the one of the caller's
 * transaction (row-level security); every method runs inside it.
 */
public interface MeterNetwork {

    /**
     * A plot meter without a reading for too long.
     *
     * @param meterId       the meter
     * @param plotId        its plot
     * @param resource      what it measures
     * @param lastReadingOn the date of its last approved reading on or before the check date, or its installation date
     *                      when it has none
     */
    record StaleMeter(UUID meterId, UUID plotId, MeterResource resource, LocalDate lastReadingOn) {

        public StaleMeter {
            Objects.requireNonNull(meterId, "meterId");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(lastReadingOn, "lastReadingOn");
        }
    }

    /**
     * The plot meters that worked on {@code asOf} (installed on or before it and not removed on or before it) whose last
     * point on or before {@code asOf} (the latest approved reading, else the installation) is more than {@code days}
     * days before {@code asOf}: dated before {@code asOf - days}. Pending, rejected and superseded readings do not
     * count. In no particular order.
     */
    List<StaleMeter> staleMeters(LocalDate asOf, int days);
}
