package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.MeterHistory;
import app.nadel.meters.ConsumptionCalculator.Reading;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** What the calculation needs of each meter: its working period and its approved readings, in the same order. */
    List<MeterHistory> histories(List<MeterRecord> chain) {
        Map<UUID, List<Reading>> approved = readings.approvedByMeter(chain.stream().map(MeterRecord::getId).toList());
        List<MeterHistory> histories = new ArrayList<>(chain.size());
        for (MeterRecord meter : chain) {
            histories.add(new MeterHistory(meter.getInstalledOn(), meter.getInitialValue(), meter.getRemovedOn(),
                    meter.getFinalValue(), approved.get(meter.getId())));
        }
        return histories;
    }
}
