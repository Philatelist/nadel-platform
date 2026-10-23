package app.nadel.meters;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger needs to know about the cooperative's meters as a network (feature 011): the plot meters not read for
 * a while, for the check list before a billing month is closed, the points of one meter, locked, for the charges of
 * its intervals, and the electricity tree with the histories of its meters over a range, for the losses runs. The
 * cooperative is the one of the caller's transaction (row-level security); every method runs inside it.
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

    /**
     * The days a node of the electricity tree counted under one parent, both included ({@code to} null while it
     * lasts), as the runs of feature 011 read them: a day belongs to the parent the node had on it, and a node removed
     * on day D still counts on D (its final value is the end of that day). {@code parentKey} is the key of the parent's
     * chain ({@link NetworkChain#key}), null for the days the node had no parent.
     */
    record Placement(@Nullable UUID parentKey, LocalDate from, @Nullable LocalDate to) {

        public Placement {
            Objects.requireNonNull(from, "from");
            if (to != null && to.isBefore(from)) {
                throw new IllegalArgumentException("A placement does not end before it starts");
            }
        }
    }

    /**
     * A node of the electricity network: a chain of meters that follow one another through replacements (a counter that
     * wrapped around included), so a replacement never splits it.
     *
     * @param key          the chain's first meter: the same for the whole life of the node
     * @param kind         the kind of its meters
     * @param plotId       the plot of a plot meter chain; null for the other kinds
     * @param shownMeterId the meter of the chain in place at the end of the range (the latest installed by then)
     * @param name         the name of that meter; null for a plot meter
     * @param meterIds     the meters of the chain, oldest first
     * @param histories    their histories for {@link ConsumptionCalculator}, in the same order (approved readings only)
     * @param placements   its places in the tree, in date order and without overlaps, clipped to the days the chain
     *                     worked (from its first installation through its final removal)
     */
    record NetworkChain(UUID key, MeterKind kind, @Nullable UUID plotId, UUID shownMeterId, @Nullable String name,
            List<UUID> meterIds, List<ConsumptionCalculator.MeterHistory> histories, List<Placement> placements) {

        public NetworkChain {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(shownMeterId, "shownMeterId");
            meterIds = List.copyOf(meterIds);
            histories = List.copyOf(histories);
            placements = List.copyOf(placements);
            if (meterIds.size() != histories.size()) {
                throw new IllegalArgumentException("A history per meter of the chain");
            }
        }
    }

    /** The electricity network of the cooperative for a date range: every chain that worked on a day of it. */
    record Network(LocalDate from, LocalDate to, List<NetworkChain> chains) {

        public Network {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            chains = List.copyOf(chains);
        }
    }

    /**
     * The electricity meters as a network for the days {@code from} to {@code to} (the losses runs of feature 011):
     * every chain of electricity meters that worked on a day of the range (installed on or before {@code to}, not
     * removed before {@code from}), with the histories of its meters (every approved reading, also those after the
     * range, which an interpolated value needs) and its places in the tree. Reads without locks (a preview).
     */
    Network network(LocalDate from, LocalDate to);

    /**
     * {@link #network} with every electricity meter of the cooperative locked {@code FOR SHARE} first, in id order,
     * until the end of the caller's transaction: a reading write, a removal, a replacement and a move in the tree hold
     * their meter {@code FOR UPDATE}, so they wait for a run that books from the values and then see its usage
     * ({@link ReadingUsage}), or the run waits for them and reads the committed values. Needs a read-write transaction.
     */
    Network lockSharedNetwork(LocalDate from, LocalDate to);
}
