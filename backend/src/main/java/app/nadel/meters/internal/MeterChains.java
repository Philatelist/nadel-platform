package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The chain of a meter: the meters that follow one another in its place through replacements ({@code replaces_meter_id}),
 * a counter that wrapped around included. A range of consumption adds up the meters of the chain, each over the days it
 * worked (money-rules 6.2, {@code ConsumptionCalculator.rangeConsumption(List, ...)}). Runs in the caller's transaction.
 */
@Component
class MeterChains {

    private final MeterRepository meters;
    private final ReadingFacts readings;

    MeterChains(MeterRepository meters, ReadingFacts readings) {
        this.meters = meters;
        this.readings = readings;
    }

    /**
     * The meter, the meters it replaced (back to the first one) and the meters that replaced it, in the order they were
     * installed: the oldest first. A meter that was never replaced and replaced nothing is a chain of one.
     */
    List<MeterRecord> chainOf(MeterRecord meter) {
        Set<UUID> seen = new HashSet<>();
        seen.add(meter.getId());
        Deque<MeterRecord> chain = new ArrayDeque<>();
        chain.add(meter);
        MeterRecord current = meter;
        while (current.getReplacesMeterId() != null && seen.add(current.getReplacesMeterId())) {
            MeterRecord previous = meters.findById(current.getReplacesMeterId()).orElse(null);
            if (previous == null) {
                break;
            }
            chain.addFirst(previous);
            current = previous;
        }
        Deque<MeterRecord> later = new ArrayDeque<>(List.of(meter));
        while (!later.isEmpty()) {
            for (MeterRecord next : meters.findByReplacesMeterId(later.removeFirst().getId())) {
                if (seen.add(next.getId())) {
                    chain.addLast(next);
                    later.addLast(next);
                }
            }
        }
        return List.copyOf(chain);
    }

    /**
     * The chains among {@code meters}, without a query: each chain from its first meter to its latest, the chains in
     * the order of their first meter in {@code meters}. A meter whose predecessor is not among {@code meters} starts a
     * chain; give every meter of a resource to get whole chains (a replacement keeps the resource).
     */
    static List<List<MeterRecord>> chainsAmong(Collection<MeterRecord> meters) {
        Map<UUID, MeterRecord> byId = new LinkedHashMap<>();
        meters.forEach(meter -> byId.put(meter.getId(), meter));
        Map<UUID, List<MeterRecord>> successors = new HashMap<>();
        for (MeterRecord meter : byId.values()) {
            if (meter.getReplacesMeterId() != null && byId.containsKey(meter.getReplacesMeterId())) {
                successors.computeIfAbsent(meter.getReplacesMeterId(), id -> new ArrayList<>()).add(meter);
            }
        }
        List<List<MeterRecord>> chains = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (MeterRecord head : byId.values()) {
            if (head.getReplacesMeterId() != null && byId.containsKey(head.getReplacesMeterId())) {
                continue;
            }
            List<MeterRecord> chain = new ArrayList<>();
            Deque<MeterRecord> next = new ArrayDeque<>(List.of(head));
            while (!next.isEmpty()) {
                MeterRecord meter = next.removeFirst();
                if (seen.add(meter.getId())) {
                    chain.add(meter);
                    next.addAll(successors.getOrDefault(meter.getId(), List.of()));
                }
            }
            chains.add(List.copyOf(chain));
        }
        return chains;
    }

    /**
     * The neighbours of each of {@code meters} in its chain: the meter it replaced and the meter that replaced it, with
     * two queries whatever the number of meters. A neighbour is given only when it belongs to the same plot (or is, like
     * the meter, not a plot meter): a replacement keeps the plot, and the check makes sure a link never shows a viewer a
     * meter of a plot they could not see. Meters without neighbours are left out of the map.
     */
    Map<UUID, Neighbours> neighboursOf(Collection<MeterRecord> meters) {
        if (meters.isEmpty()) {
            return Map.of();
        }
        Set<UUID> predecessorIds = new HashSet<>();
        meters.forEach(meter -> {
            if (meter.getReplacesMeterId() != null) {
                predecessorIds.add(meter.getReplacesMeterId());
            }
        });
        Map<UUID, MeterRecord> predecessors = new HashMap<>();
        if (!predecessorIds.isEmpty()) {
            this.meters.findAllById(predecessorIds).forEach(meter -> predecessors.put(meter.getId(), meter));
        }
        Map<UUID, MeterRecord> successors = new HashMap<>();
        this.meters.findByReplacesMeterIdIn(meters.stream().map(MeterRecord::getId).distinct().toList())
                .forEach(meter -> successors.putIfAbsent(meter.getReplacesMeterId(), meter));
        Map<UUID, Neighbours> result = new HashMap<>();
        for (MeterRecord meter : meters) {
            MeterRecord predecessor = meter.getReplacesMeterId() == null ? null
                    : samePlace(meter, predecessors.get(meter.getReplacesMeterId()));
            MeterRecord successor = samePlace(meter, successors.get(meter.getId()));
            if (predecessor != null || successor != null) {
                result.put(meter.getId(), new Neighbours(predecessor, successor));
            }
        }
        return result;
    }

    /** The neighbours of one meter, {@link Neighbours#NONE} when it has none. */
    Neighbours neighboursOf(MeterRecord meter) {
        return neighboursOf(List.of(meter)).getOrDefault(meter.getId(), Neighbours.NONE);
    }

    private static @Nullable MeterRecord samePlace(MeterRecord meter, @Nullable MeterRecord neighbour) {
        return neighbour != null && Objects.equals(neighbour.getPlotId(), meter.getPlotId()) ? neighbour : null;
    }

    /**
     * The meter a meter replaced and the meter that replaced it, each null when there is none.
     *
     * @param predecessor the meter it replaced ({@code replaces_meter_id})
     * @param successor   the meter that replaced it
     */
    record Neighbours(@Nullable MeterRecord predecessor, @Nullable MeterRecord successor) {

        static final Neighbours NONE = new Neighbours(null, null);
    }

    /** What the calculation needs of each meter: its working period and its approved readings, in the same order. */
    List<MeterHistory> histories(List<MeterRecord> chain) {
        Map<UUID, MeterHistory> byId = historiesById(chain);
        return chain.stream().map(meter -> byId.get(meter.getId())).toList();
    }

    /** The histories of many meters by id, with one bulk read of their approved readings. */
    Map<UUID, MeterHistory> historiesById(Collection<MeterRecord> meters) {
        Map<UUID, List<Reading>> approved = readings.approvedByMeter(meters.stream().map(MeterRecord::getId).toList());
        Map<UUID, MeterHistory> histories = new HashMap<>();
        for (MeterRecord meter : meters) {
            histories.put(meter.getId(), new MeterHistory(meter.getInstalledOn(), meter.getInitialValue(),
                    meter.getRemovedOn(), meter.getFinalValue(), approved.get(meter.getId())));
        }
        return histories;
    }
}
