package app.nadel.ledger.internal;

import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterNetwork.Placement;
import app.nadel.meters.Quantity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The losses of the electricity network over a date range, per node of the meter tree (feature 011 §2.5, money-rules 6
 * and 6.3). Pure: no Spring, no database, no clock; every row of the feature's worked examples is a unit test
 * ({@code LossesCalculatorTests}). Money is not calculated here: the result is exact kWh ({@link Quantity}), priced
 * later per plot.
 * <p>
 * Days and values. The range is the days {@code from} to {@code to}, both included. The consumption of a chain of meters
 * over the days {@code a} to {@code b} is its end-of-day value on {@code b} minus its end-of-day value on {@code a - 1}
 * ({@link ConsumptionCalculator#rangeConsumption(List, LocalDate, LocalDate)} from {@code a - 1} to {@code b}), so two
 * runs back to back neither overlap nor leave a gap. A value between two readings is interpolated and marks the numbers
 * that rest on it as calculated; a value with no reading after it is unknown.
 * <p>
 * Nodes. A node is a substation or quarter chain that had a meter under it on a day of the range. Its children are the
 * chains placed under it on those days (plot meters, common consumers, quarter meters), each counted over the days it
 * was under the node (the tree as it was on each day). For every node:
 * <ul>
 * <li>{@code losses = consumption of the node over the range - sum of the children's consumption under it};</li>
 * <li>{@link Outcome#UNKNOWN} when the node's own value, or a child's, is unknown;</li>
 * <li>{@link Outcome#SKIPPED_ABOVE} when a node below it (within the days it was below) is unknown, so the node is
 * skipped too: its plots' consumption is not known either;</li>
 * <li>{@link Outcome#NEGATIVE} when the children used more than the node shows: nothing is charged;</li>
 * <li>{@link Outcome#CHARGED} otherwise: the losses are shared among the plots under the node (directly, or through
 * child nodes within the days those were under it) in proportion to each plot's consumption under it; a plot with zero
 * consumption gets nothing. Each share is exact.</li>
 * </ul>
 * The values a charged node read (the points around the ends of every slice of the node, its children and the plots it
 * shares among) are listed as {@link ValueUse}s, so the readings behind a booked run can no longer be corrected.
 */
final class LossesCalculator {

    /** Deeper than the kinds allow (three levels); a guard against inconsistent data only. */
    private static final int MAX_DEPTH = 8;

    private LossesCalculator() {
    }

    /** What a run does with a node. */
    enum Outcome {
        CHARGED, NEGATIVE, UNKNOWN, SKIPPED_ABOVE
    }

    /** A point of a meter that a calculation read: its value on that date (a reading, or a boundary value). */
    record ValueUse(UUID meterId, LocalDate on) {
    }

    /**
     * A value that is not known: the chain whose value it is, its meter that has no reading after the date and that
     * meter's last point before it (null when it has none).
     */
    record UnknownValue(UUID chainKey, UUID meterId, @Nullable LocalDate lastReadingOn) {
    }

    /**
     * The result for one node.
     *
     * @param nodeKey     the node's chain
     * @param outcome     what the run does with it
     * @param consumption the node's own consumption over the range; null when unknown
     * @param children    the children's consumption under it; null when unknown
     * @param calculated  whether a value the numbers rest on was interpolated
     * @param unknown     for {@link Outcome#UNKNOWN}: the value that is not known
     * @param below       for {@link Outcome#SKIPPED_ABOVE}: the unknown node below
     * @param shares      for {@link Outcome#CHARGED}: the kWh of each plot, in the order the plots were met; empty when
     *                    the losses are zero or nobody under the node used anything
     * @param uses        for {@link Outcome#CHARGED}: the values it read
     */
    record NodeResult(UUID nodeKey, Outcome outcome, @Nullable Quantity consumption, @Nullable Quantity children,
            boolean calculated, @Nullable UnknownValue unknown, @Nullable UUID below, Map<UUID, Quantity> shares,
            Set<ValueUse> uses) {

        NodeResult {
            Objects.requireNonNull(nodeKey, "nodeKey");
            Objects.requireNonNull(outcome, "outcome");
            shares = Collections.unmodifiableMap(new LinkedHashMap<>(shares));
            uses = Set.copyOf(uses);
        }

        /** {@code consumption - children}; null when either is unknown. */
        @Nullable Quantity losses() {
            return consumption == null || children == null ? null : consumption.minus(children);
        }

        /** A charged node with losses above zero that nobody under it shares, since none of them used anything. */
        boolean noPlotConsumption() {
            Quantity losses = losses();
            return outcome == Outcome.CHARGED && losses != null && losses.signum() > 0 && shares.isEmpty();
        }
    }

    /**
     * The losses of every node of the network over the days {@code from} to {@code to}, in the order of the chains.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    static List<NodeResult> calculate(List<NetworkChain> chains, LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A range does not end before it starts");
        }
        Map<UUID, NetworkChain> byKey = new LinkedHashMap<>();
        chains.forEach(chain -> byKey.put(chain.key(), chain));
        Map<UUID, List<NetworkChain>> under = new HashMap<>();
        for (NetworkChain chain : chains) {
            chain.placements().stream().map(Placement::parentKey).filter(Objects::nonNull).distinct()
                    .filter(byKey::containsKey)
                    .forEach(parent -> under.computeIfAbsent(parent, k -> new ArrayList<>()).add(chain));
        }
        Window range = new Window(from, to);

        Map<UUID, Local> locals = new LinkedHashMap<>();
        for (NetworkChain node : chains) {
            if (node.kind() != MeterKind.SUBSTATION && node.kind() != MeterKind.QUARTER) {
                continue;
            }
            Walk walk = new Walk(under);
            walk.walk(node, List.of(range), 0, Set.of(node.key()));
            if (walk.directSlices == 0) {
                continue; // no meter under it on a day of the range: not a node of this run
            }
            locals.put(node.key(), new Local(node, measure(node, from, to), walk));
        }

        List<NodeResult> results = new ArrayList<>();
        for (Local local : locals.values()) {
            results.add(result(local, locals));
        }
        return results;
    }

    /** The kWh of every plot over the charged nodes, the plots in the order they were met. */
    static Map<UUID, Quantity> plotTotals(Collection<NodeResult> nodes) {
        Map<UUID, Quantity> totals = new LinkedHashMap<>();
        for (NodeResult node : nodes) {
            if (node.outcome() == Outcome.CHARGED) {
                node.shares().forEach((plot, kwh) -> totals.merge(plot, kwh, Quantity::plus));
            }
        }
        return totals;
    }

    /** {@code value x numerator / denominator}, exact; the denominator is above zero. */
    static Quantity proportion(Quantity value, Quantity numerator, Quantity denominator) {
        return Quantity.ofFraction(
                value.numerator().multiply(numerator.numerator()).multiply(denominator.denominator()),
                value.denominator().multiply(numerator.denominator()).multiply(denominator.numerator()));
    }

    private static NodeResult result(Local local, Map<UUID, Local> locals) {
        NetworkChain node = local.node();
        Measured own = local.own();
        Walk walk = local.walk();
        Quantity consumption = own.known();
        Quantity children = walk.childSum.known();
        boolean calculated = own.calculated() || walk.calculated;
        if (consumption == null) {
            return unknown(node, null, children, calculated, own.unknown());
        }
        for (UUID below : walk.nodesBelow) {
            Local lower = locals.get(below);
            if (lower != null && lower.locallyUnknown()) {
                return new NodeResult(node.key(), Outcome.SKIPPED_ABOVE, consumption, children, calculated, null,
                        below, Map.of(), Set.of());
            }
        }
        if (children == null) {
            return unknown(node, consumption, null, calculated, walk.childSum.unknown());
        }
        Quantity losses = consumption.minus(children);
        if (losses.signum() < 0) {
            return new NodeResult(node.key(), Outcome.NEGATIVE, consumption, children, calculated, null, null,
                    Map.of(), Set.of());
        }
        Map<UUID, Quantity> shares = new LinkedHashMap<>();
        Quantity total = walk.plots.values().stream().reduce(Quantity.ZERO, Quantity::plus);
        if (losses.signum() > 0 && total.signum() > 0) {
            walk.plots.forEach((plot, used) -> {
                if (used.signum() > 0) {
                    shares.put(plot, proportion(losses, used, total));
                }
            });
        }
        Set<ValueUse> uses = new LinkedHashSet<>(own.uses());
        uses.addAll(walk.uses);
        return new NodeResult(node.key(), Outcome.CHARGED, consumption, children, calculated, null, null, shares,
                uses);
    }

    private static NodeResult unknown(NetworkChain node, @Nullable Quantity consumption, @Nullable Quantity children,
            boolean calculated, @Nullable UnknownValue unknown) {
        return new NodeResult(node.key(), Outcome.UNKNOWN, consumption, children, calculated, unknown, null, Map.of(),
                Set.of());
    }

    /** A node before the outcome: its own consumption and what the walk under it found. */
    private record Local(NetworkChain node, Measured own, Walk walk) {

        /** Its own value or a child's is unknown (the nodes above it are then skipped). */
        boolean locallyUnknown() {
            return own.known() == null || walk.childSum.known() == null;
        }
    }

    /** Days from {@code from} to {@code to}, both included. */
    private record Window(LocalDate from, LocalDate to) {
    }

    /**
     * Everything under one node, within the days the walk looks at: the sum of its direct children, the consumption of
     * every plot below it (through child nodes within the days they were under it), the nodes below it, and the values
     * read.
     */
    private static final class Walk {

        private final Map<UUID, List<NetworkChain>> under;
        private final Sum childSum = new Sum();
        private final Map<UUID, Quantity> plots = new LinkedHashMap<>();
        private final Set<UUID> nodesBelow = new LinkedHashSet<>();
        private final Set<ValueUse> uses = new LinkedHashSet<>();
        private boolean calculated;
        private int directSlices;

        Walk(Map<UUID, List<NetworkChain>> under) {
            this.under = under;
        }

        void walk(NetworkChain parent, List<Window> windows, int depth, Set<UUID> path) {
            for (NetworkChain child : under.getOrDefault(parent.key(), List.of())) {
                if (path.contains(child.key())) {
                    continue;
                }
                List<Window> slices = slices(child, parent.key(), windows);
                for (Window slice : slices) {
                    Measured measured = measure(child, slice.from(), slice.to());
                    calculated |= measured.calculated();
                    uses.addAll(measured.uses());
                    if (depth == 0) {
                        directSlices++;
                        childSum.add(measured);
                    }
                    if (child.kind() == MeterKind.PLOT && child.plotId() != null && measured.known() != null) {
                        plots.merge(child.plotId(), measured.known(), Quantity::plus);
                    }
                }
                if (!slices.isEmpty() && (child.kind() == MeterKind.SUBSTATION || child.kind() == MeterKind.QUARTER)) {
                    nodesBelow.add(child.key());
                    if (depth + 1 < MAX_DEPTH) {
                        Set<UUID> deeper = new HashSet<>(path);
                        deeper.add(child.key());
                        walk(child, slices, depth + 1, deeper);
                    }
                }
            }
        }

        /** The days of the windows on which the chain was under the parent, in date order. */
        private static List<Window> slices(NetworkChain chain, UUID parent, List<Window> windows) {
            List<Window> slices = new ArrayList<>();
            for (Placement placement : chain.placements()) {
                if (!parent.equals(placement.parentKey())) {
                    continue;
                }
                for (Window window : windows) {
                    LocalDate start = placement.from().isAfter(window.from()) ? placement.from() : window.from();
                    LocalDate end = placement.to() == null || placement.to().isAfter(window.to()) ? window.to()
                            : placement.to();
                    if (!end.isBefore(start)) {
                        slices.add(new Window(start, end));
                    }
                }
            }
            return slices;
        }
    }

    /** A sum of consumptions that turns unknown when one of them is. */
    private static final class Sum {

        private Quantity total = Quantity.ZERO;
        private @Nullable UnknownValue unknown;

        void add(Measured measured) {
            if (measured.known() == null) {
                if (unknown == null) {
                    unknown = measured.unknown();
                }
            } else {
                total = total.plus(measured.known());
            }
        }

        @Nullable Quantity known() {
            return unknown == null ? total : null;
        }

        @Nullable UnknownValue unknown() {
            return unknown;
        }
    }

    /**
     * The consumption of a chain over some days, with the values it read.
     *
     * @param known      the consumption; null when unknown
     * @param calculated whether a value was interpolated
     * @param unknown    the value that is not known, when {@code known} is null
     * @param uses       the points read
     */
    private record Measured(@Nullable Quantity known, boolean calculated, @Nullable UnknownValue unknown,
            Set<ValueUse> uses) {
    }

    /**
     * The consumption of the chain on the days {@code first} to {@code last}: its value at the end of {@code last} minus
     * its value at the end of the day before {@code first}, and the points those values rest on.
     */
    private static Measured measure(NetworkChain chain, LocalDate first, LocalDate last) {
        LocalDate before = first.minusDays(1);
        RangeConsumption consumption = ConsumptionCalculator.rangeConsumption(chain.histories(), before, last);
        Set<ValueUse> uses = new LinkedHashSet<>();
        UnknownValue unknown = null;
        for (int i = 0; i < chain.histories().size(); i++) {
            MeterHistory history = chain.histories().get(i);
            UUID meterId = chain.meterIds().get(i);
            LocalDate start = before.isBefore(history.installedOn()) ? history.installedOn() : before;
            LocalDate end = history.removedOn() != null && last.isAfter(history.removedOn()) ? history.removedOn()
                    : last;
            if (!start.isBefore(end)) {
                continue;
            }
            NavigableMap<LocalDate, ?> points = points(history);
            for (LocalDate day : List.of(start, end)) {
                if (points.containsKey(day)) {
                    uses.add(new ValueUse(meterId, day));
                    continue;
                }
                LocalDate lower = points.floorKey(day);
                LocalDate higher = points.ceilingKey(day);
                if (lower != null && higher != null) {
                    uses.add(new ValueUse(meterId, lower));
                    uses.add(new ValueUse(meterId, higher));
                } else if (unknown == null) {
                    unknown = new UnknownValue(chain.key(), meterId, lower);
                }
            }
        }
        return switch (consumption) {
            case RangeConsumption.Known known -> new Measured(known.consumption(), known.calculated(), null, uses);
            case RangeConsumption.Unknown missing -> new Measured(null, false,
                    unknown != null ? unknown
                            : new UnknownValue(chain.key(), chain.meterIds().getLast(), missing.lastReadingOn()),
                    Set.of());
        };
    }

    /** The dates of the points of a meter: its installation, its approved readings and its removal. */
    private static NavigableMap<LocalDate, Boolean> points(MeterHistory history) {
        NavigableMap<LocalDate, Boolean> points = new TreeMap<>();
        points.put(history.installedOn(), Boolean.TRUE);
        if (history.removedOn() != null) {
            points.put(history.removedOn(), Boolean.TRUE);
        }
        for (Reading reading : history.readings()) {
            points.put(reading.takenOn(), Boolean.TRUE);
        }
        return points;
    }
}
