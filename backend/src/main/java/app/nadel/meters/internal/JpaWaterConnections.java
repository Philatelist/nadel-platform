package app.nadel.meters.internal;

import app.nadel.meters.WaterConnections;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link WaterConnections} from {@code plot_water_connection}; RLS limits it to the caller's cooperative. */
@Component
class JpaWaterConnections implements WaterConnections {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    private final WaterConnectionRepository connections;

    JpaWaterConnections(WaterConnectionRepository connections) {
        this.connections = connections;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<ConnectedPeriod>> connectedDays(Collection<UUID> plotIds, LocalDate from, LocalDate to) {
        List<UUID> ids = List.copyOf(plotIds);
        List<WaterConnectionRecord> rows = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += CHUNK) {
            rows.addAll(connections.findOverlapping(ids.subList(start, Math.min(start + CHUNK, ids.size())), from,
                    to));
        }
        return periods(rows, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<ConnectedPeriod>> connectedToSource(@Nullable UUID sourceMeterId, LocalDate from,
            LocalDate to) {
        return periods(sourceMeterId == null ? connections.findOverlappingOfSupply(from, to)
                : connections.findOverlappingOfMeter(sourceMeterId, from, to), from, to);
    }

    /** The rows (ordered by plot, then start) as periods clipped to the range, per plot. */
    private static Map<UUID, List<ConnectedPeriod>> periods(List<WaterConnectionRecord> rows, LocalDate from,
            LocalDate to) {
        if (to.isBefore(from)) {
            return Map.of();
        }
        Map<UUID, List<ConnectedPeriod>> result = new LinkedHashMap<>();
        for (WaterConnectionRecord row : rows) {
            LocalDate first = row.getValidFrom().isAfter(from) ? row.getValidFrom() : from;
            LocalDate validTo = row.getValidTo();
            LocalDate last = validTo == null || validTo.isAfter(to) ? to : validTo;
            if (!last.isBefore(first)) {
                result.computeIfAbsent(row.getPlotId(), id -> new ArrayList<>()).add(new ConnectedPeriod(first, last));
            }
        }
        return result;
    }
}
