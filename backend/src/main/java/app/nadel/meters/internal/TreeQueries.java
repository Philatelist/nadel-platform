package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator;
import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.ParentPeriod;
import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.Slice;
import app.nadel.meters.ConsumptionCalculator.ValueAt;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.Quantity;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.MeterPlot;
import nadel.contract.model.MeterRangeConsumption;
import nadel.contract.model.MeterTree;
import nadel.contract.model.MeterTreeMeter;
import nadel.contract.model.MeterTreeNode;
import nadel.contract.model.MeterTreeSlice;
import nadel.contract.model.MeterValueAt;
import nadel.contract.model.MeterValueAtKind;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The electricity meter tree as it was over a date range, with the consumption of every node (spec 010, sections 2.2
 * and 2.5). The calculation is {@link ConsumptionCalculator#splitByParent}, exact until the one display rounding of a
 * value (ADR 0014); only counted readings are used. Four queries, whatever the size of the tree: the meters, the
 * parent periods, the approved readings and the plots.
 * <p>
 * A node is a chain of meters ({@link MeterChains#chainsAmong}): a replacement or a counter that wrapped around does
 * not split it, and a parent that was replaced stays one node. The parent periods of the chain's meters, with the
 * parents read as chains too, become the chain's placements: clipped to the days the chain worked (up to the day before
 * its final removal, the day its last slice ends), the period of a replaced meter cut where its successor's period
 * starts, neighbouring periods under the same parent joined, and the days without a parent put under {@link #ROOT}. A
 * node is listed under each parent it had in the range, with the slices it spent there; its children are found within
 * those slices only, so a quarter moved between substations shows under each the plots it fed then.
 */
@Service
class TreeQueries {

    /** The parent of the days a chain had no parent: the roots. Never a meter id (those are random, version 4). */
    private static final UUID ROOT = new UUID(0L, 0L);

    /** Deeper than the kinds allow (three levels); a guard against inconsistent data only. */
    private static final int MAX_DEPTH = 8;

    private static final Map<MeterKind, Integer> KIND_ORDER = Map.of(MeterKind.SUBSTATION, 0, MeterKind.QUARTER, 1,
            MeterKind.COMMON_CONSUMER, 2, MeterKind.PLOT, 3);

    private final MeterRepository meters;
    private final MeterParentRepository parentRows;
    private final MeterChains chains;
    private final PlotCatalog plots;

    TreeQueries(MeterRepository meters, MeterParentRepository parentRows, MeterChains chains, PlotCatalog plots) {
        this.meters = meters;
        this.parentRows = parentRows;
        this.chains = chains;
        this.plots = plots;
    }

    /** The tree from {@code from} to {@code to}; 400 when {@code to} is before {@code from}. */
    @Transactional(readOnly = true)
    MeterTree tree(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("to", ProblemCodes.INVALID)));
        }
        List<MeterRecord> electricity = meters.findByResource(MeterResource.ELECTRICITY);
        Map<UUID, MeterHistory> histories = chains.historiesById(electricity);
        Map<UUID, UUID> chainKeyOf = new HashMap<>();
        List<List<MeterRecord>> found = MeterChains.chainsAmong(electricity);
        for (List<MeterRecord> chain : found) {
            chain.forEach(meter -> chainKeyOf.put(meter.getId(), chain.getFirst().getId()));
        }
        Map<UUID, List<MeterParentRecord>> rowsByChain = new HashMap<>();
        for (MeterParentRecord row : parentRows.findAll()) {
            UUID key = chainKeyOf.get(row.getMeterId());
            if (key != null && chainKeyOf.containsKey(row.getParentMeterId())) {
                rowsByChain.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        Map<UUID, List<Chain>> under = new HashMap<>();
        for (List<MeterRecord> members : found) {
            List<ParentPeriod> placements = placements(members,
                    rowsByChain.getOrDefault(members.getFirst().getId(), List.of()), chainKeyOf);
            Chain chain = new Chain(members, members.stream().map(meter -> histories.get(meter.getId())).toList(),
                    placements);
            placements.stream().map(ParentPeriod::parentMeterId).distinct()
                    .forEach(parent -> under.computeIfAbsent(parent, k -> new ArrayList<>()).add(chain));
        }

        List<UUID> plotIds = electricity.stream().map(MeterRecord::getPlotId).filter(Objects::nonNull).distinct()
                .toList();
        Map<UUID, PlotFacts> plotFacts = plots.findAll(plotIds);
        Map<UUID, Integer> streetOrder = new HashMap<>();
        List<UUID> ordered = plots.inStreetOrder(plotIds);
        for (int i = 0; i < ordered.size(); i++) {
            streetOrder.put(ordered.get(i), i);
        }
        Builder builder = new Builder(under, plotFacts, streetOrder, to);
        return new MeterTree(from, to, builder.nodesUnder(ROOT, List.of(new Window(from, to)), 0, Set.of()));
    }

    /**
     * The places of a chain in the tree, in date order and without overlaps: the parent periods of its meters, clipped
     * to the days the chain worked up to the day before its final removal, a period cut on the day before the next one
     * starts (the replaced meter's period ends on the replacement day, its successor's starts on it), neighbouring
     * periods under the same parent joined, and the days without a parent under {@link #ROOT}.
     */
    private static List<ParentPeriod> placements(List<MeterRecord> chain, List<MeterParentRecord> rows,
            Map<UUID, UUID> chainKeyOf) {
        LocalDate start = chain.getFirst().getInstalledOn();
        LocalDate removedOn = chain.getLast().getRemovedOn();
        LocalDate lastDay = removedOn == null ? null : removedOn.minusDays(1);
        if (lastDay != null && lastDay.isBefore(start)) {
            return List.of();
        }
        List<MutablePeriod> sorted = rows.stream()
                .map(row -> new MutablePeriod(chainKeyOf.get(row.getParentMeterId()),
                        later(row.getValidFrom(), start), earlier(row.getValidTo(), lastDay)))
                .filter(MutablePeriod::hasDays)
                .sorted(Comparator.comparing((MutablePeriod period) -> period.from)
                        .thenComparing(period -> period.to, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<MutablePeriod> periods = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            MutablePeriod period = sorted.get(i);
            if (i + 1 < sorted.size()) {
                period.to = earlier(period.to, sorted.get(i + 1).from.minusDays(1));
            }
            if (!period.hasDays()) {
                continue;
            }
            MutablePeriod previous = periods.isEmpty() ? null : periods.getLast();
            if (previous != null && previous.parent.equals(period.parent) && previous.to != null
                    && previous.to.plusDays(1).equals(period.from)) {
                previous.to = period.to;
            } else {
                periods.add(period);
            }
        }
        List<ParentPeriod> placements = new ArrayList<>();
        LocalDate cursor = start;
        for (MutablePeriod period : periods) {
            if (cursor != null && period.from.isAfter(cursor)) {
                placements.add(new ParentPeriod(ROOT, cursor, period.from.minusDays(1)));
            }
            placements.add(new ParentPeriod(period.parent, period.from, period.to));
            cursor = period.to == null ? null : period.to.plusDays(1);
        }
        if (cursor != null && (lastDay == null || !cursor.isAfter(lastDay))) {
            placements.add(new ParentPeriod(ROOT, cursor, lastDay));
        }
        return placements;
    }

    private static LocalDate later(LocalDate date, LocalDate other) {
        return date.isBefore(other) ? other : date;
    }

    private static @Nullable LocalDate earlier(@Nullable LocalDate date, @Nullable LocalDate other) {
        if (date == null) {
            return other;
        }
        return other != null && other.isBefore(date) ? other : date;
    }

    /** A chain of meters with the histories of its meters (same order) and its places in the tree. */
    private record Chain(List<MeterRecord> meters, List<MeterHistory> histories, List<ParentPeriod> placements) {

        UUID key() {
            return meters.getFirst().getId();
        }
    }

    /** A part of the range in which children are looked for: the slices of their parent. */
    private record Window(LocalDate from, LocalDate to) {
    }

    private static final class MutablePeriod {

        private final UUID parent;
        private final LocalDate from;
        private @Nullable LocalDate to;

        MutablePeriod(UUID parent, LocalDate from, @Nullable LocalDate to) {
            this.parent = parent;
            this.from = from;
            this.to = to;
        }

        boolean hasDays() {
            return to == null || !to.isBefore(from);
        }
    }

    /** Builds the nodes, recursively, and maps them to the API model. */
    private static final class Builder {

        private final Map<UUID, List<Chain>> under;
        private final Map<UUID, PlotFacts> plotFacts;
        private final Map<UUID, Integer> streetOrder;
        private final LocalDate rangeEnd;
        private final Collator collator = Collator.getInstance(Locale.forLanguageTag("uk"));

        Builder(Map<UUID, List<Chain>> under, Map<UUID, PlotFacts> plotFacts, Map<UUID, Integer> streetOrder,
                LocalDate rangeEnd) {
            this.under = under;
            this.plotFacts = plotFacts;
            this.streetOrder = streetOrder;
            this.rangeEnd = rangeEnd;
        }

        /** The nodes under {@code parent} within the windows, in the order of the tree. */
        List<MeterTreeNode> nodesUnder(UUID parent, List<Window> windows, int depth, Set<UUID> path) {
            List<Placed> placed = new ArrayList<>();
            for (Chain chain : under.getOrDefault(parent, List.of())) {
                if (path.contains(chain.key())) {
                    continue;
                }
                List<ParentPeriod> here = chain.placements().stream()
                        .filter(period -> period.parentMeterId().equals(parent))
                        .toList();
                List<Slice> slices = new ArrayList<>();
                for (Window window : windows) {
                    slices.addAll(ConsumptionCalculator.splitByParent(chain.histories(), here, window.from(),
                            window.to()));
                }
                if (!slices.isEmpty()) {
                    placed.add(new Placed(chain, shown(chain), slices));
                }
            }
            placed.sort(Comparator.comparing((Placed node) -> KIND_ORDER.get(node.shown().getKind()))
                    .thenComparing(node -> Objects.requireNonNullElse(node.shown().getName(), ""), collator)
                    .thenComparing(node -> node.shown().getPlotId() == null ? 0
                            : streetOrder.getOrDefault(node.shown().getPlotId(), Integer.MAX_VALUE))
                    .thenComparing(node -> node.shown().getId()));
            List<MeterTreeNode> nodes = new ArrayList<>(placed.size());
            for (Placed node : placed) {
                Set<UUID> deeper = new HashSet<>(path);
                deeper.add(node.chain().key());
                List<MeterTreeNode> children = depth + 1 >= MAX_DEPTH ? List.of()
                        : nodesUnder(node.chain().key(), node.slices().stream()
                                .map(slice -> new Window(slice.from(), slice.to())).toList(), depth + 1, deeper);
                nodes.add(new MeterTreeNode(meter(node.shown()),
                        node.chain().meters().stream().map(MeterRecord::getId).toList(),
                        node.slices().stream().map(slice -> slice(node.chain(), slice)).toList(),
                        consumption(total(node.slices())), children));
            }
            return nodes;
        }

        /** The meter of the chain in place at the end of the range: the latest one installed by then. */
        private MeterRecord shown(Chain chain) {
            MeterRecord shown = chain.meters().getFirst();
            for (MeterRecord meter : chain.meters()) {
                if (!meter.getInstalledOn().isAfter(rangeEnd)) {
                    shown = meter;
                }
            }
            return shown;
        }

        private MeterTreeMeter meter(MeterRecord meter) {
            MeterTreeMeter dto = new MeterTreeMeter(meter.getId(),
                    nadel.contract.model.MeterKind.fromValue(meter.getKind().name()), meter.getSerial(),
                    meter.getInstalledOn(), meter.isActive());
            dto.setName(meter.getName());
            PlotFacts plot = meter.getPlotId() == null ? null : plotFacts.get(meter.getPlotId());
            if (plot != null) {
                MeterPlot ref = new MeterPlot(plot.id(), plot.number());
                ref.setStreet(plot.street());
                dto.setPlot(ref);
            }
            dto.setRemovedOn(meter.getRemovedOn());
            return dto;
        }

        private static MeterTreeSlice slice(Chain chain, Slice slice) {
            return new MeterTreeSlice(slice.from(), slice.to(), valueAt(chain, slice.from(), true),
                    valueAt(chain, slice.to(), false), consumption(slice.consumption()));
        }

        /**
         * The value of the chain on {@code date}, read off the meter that works then. On a replacement day the start of
         * a slice takes the new meter (its initial reading, the counting goes on from there) and the end of a slice
         * the old one (its final reading).
         */
        private static MeterValueAt valueAt(Chain chain, LocalDate date, boolean start) {
            int index = memberOn(chain.meters(), date, start);
            MeterRecord meter = chain.meters().get(index);
            ValueAt value = ConsumptionCalculator.valueAt(chain.histories().get(index), date);
            return switch (value) {
                case ValueAt.Real real -> {
                    MeterValueAt dto = new MeterValueAt(MeterValueAtKind.REAL, meter.getId());
                    dto.setValue(real.value().toString());
                    yield dto;
                }
                case ValueAt.Calculated calculated -> {
                    MeterValueAt dto = new MeterValueAt(MeterValueAtKind.CALCULATED, meter.getId());
                    dto.setValue(calculated.value().toMeterValue().toString());
                    yield dto;
                }
                case ValueAt.Unknown unknown -> {
                    MeterValueAt dto = new MeterValueAt(MeterValueAtKind.UNKNOWN, meter.getId());
                    dto.setLastReadingOn(unknown.lastReadingOn());
                    yield dto;
                }
            };
        }

        /** The index of the meter of the chain that works on {@code date}, from that day on or up to it. */
        private static int memberOn(List<MeterRecord> chain, LocalDate date, boolean start) {
            for (int i = 0; i < chain.size(); i++) {
                MeterRecord meter = chain.get(i);
                LocalDate removedOn = meter.getRemovedOn();
                boolean works = start
                        ? !meter.getInstalledOn().isAfter(date) && (removedOn == null || removedOn.isAfter(date))
                        : meter.getInstalledOn().isBefore(date) && (removedOn == null || !removedOn.isBefore(date));
                if (works) {
                    return i;
                }
            }
            int latest = 0;
            for (int i = 0; i < chain.size(); i++) {
                if (!chain.get(i).getInstalledOn().isAfter(date)) {
                    latest = i;
                }
            }
            return latest;
        }

        /** The consumption over the slices: their sum, unknown when any slice is (the latest "no reading after"). */
        private static RangeConsumption total(List<Slice> slices) {
            Quantity sum = Quantity.ZERO;
            boolean calculated = false;
            boolean unknown = false;
            LocalDate lastReadingOn = null;
            for (Slice slice : slices) {
                switch (slice.consumption()) {
                    case RangeConsumption.Known known -> {
                        sum = sum.plus(known.consumption());
                        calculated |= known.calculated();
                    }
                    case RangeConsumption.Unknown part -> {
                        unknown = true;
                        if (part.lastReadingOn() != null
                                && (lastReadingOn == null || part.lastReadingOn().isAfter(lastReadingOn))) {
                            lastReadingOn = part.lastReadingOn();
                        }
                    }
                }
            }
            return unknown ? new RangeConsumption.Unknown(lastReadingOn) : new RangeConsumption.Known(sum, calculated);
        }

        private static MeterRangeConsumption consumption(RangeConsumption range) {
            return switch (range) {
                case RangeConsumption.Known known -> {
                    MeterRangeConsumption dto = new MeterRangeConsumption(true, known.calculated());
                    dto.setConsumption(known.consumption().toMeterValue().toString());
                    yield dto;
                }
                case RangeConsumption.Unknown unknown -> {
                    MeterRangeConsumption dto = new MeterRangeConsumption(false, false);
                    dto.setLastReadingOn(unknown.lastReadingOn());
                    yield dto;
                }
            };
        }
    }

    /** A chain placed under a parent: the meter it shows and its slices there. */
    private record Placed(Chain chain, MeterRecord shown, List<Slice> slices) {
    }
}
