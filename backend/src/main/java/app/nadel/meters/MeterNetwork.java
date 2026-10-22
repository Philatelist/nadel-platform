package app.nadel.meters;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger needs to know about the cooperative's meters as a network (feature 011): the plot meters not read for
 * a while, for the check list before a billing month is closed, and the points of one meter, locked, for the charges
 * of its intervals. The cooperative is the one of the caller's transaction (row-level security); every method runs
 * inside it.
 */
public interface MeterNetwork {

    /** Where a point of a meter comes from. */
    enum PointKind {
        /** The initial value on the installation date. */
        INSTALLATION,
        /** An approved reading. */
        READING,
        /** The final value on the removal date. */
        REMOVAL
    }

    /**
     * A point of a meter's history (money-rules 6.4): a value on a date with a key that stays the same while the point
     * does. The key is {@code INSTALL:{meter}} for the initial value, the reading's id for an approved reading and
     * {@code REMOVAL:{meter}} for the final value; a reading on the installation or removal date takes the place of
     * that boundary point (and its key). A correction is a new reading, so it has a new key.
     */
    record Point(LocalDate on, MeterValue value, String key, PointKind kind) {

        public Point {
            Objects.requireNonNull(on, "on");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * A meter with its points, oldest first, one per date.
     *
     * @param plotId the plot of a plot meter; null for the other kinds
     */
    record MeterPoints(UUID meterId, MeterKind kind, MeterResource resource, @Nullable UUID plotId,
            List<Point> points) {

        public MeterPoints {
            Objects.requireNonNull(meterId, "meterId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(resource, "resource");
            points = List.copyOf(points);
        }
    }

    /**
     * Locks the meter {@code FOR SHARE} until the end of the caller's transaction and reads its points under the lock:
     * a reading write, a removal and a replacement hold the meter {@code FOR UPDATE}, so they wait for a transaction
     * that charges from the points and then see its charges ({@link ReadingUsage}), or it waits for them and sees the
     * committed points. Pending, rejected and superseded readings are not points. Empty for an unknown meter (or one
     * of another cooperative).
     */
    Optional<MeterPoints> lockSharedPoints(UUID meterId);

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
