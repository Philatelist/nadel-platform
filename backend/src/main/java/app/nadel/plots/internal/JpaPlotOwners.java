package app.nadel.plots.internal;

import app.nadel.plots.PlotOwners;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link PlotOwners} from the current ownership rows; RLS limits them to the caller's cooperative. */
@Component
class JpaPlotOwners implements PlotOwners {

    /** Values per {@code IN} list. */
    private static final int CHUNK = 500;

    private static final Comparator<Ownership> EARLIEST_FIRST = Comparator.comparing(Ownership::getValidFrom)
            .thenComparing(Ownership::getMemberId, UuidOrder.POSTGRES);

    private final OwnershipRepository ownerships;

    JpaPlotOwners(OwnershipRepository ownerships) {
        this.ownerships = ownerships;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Integer> ownerCounts(Collection<UUID> plotIds) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        currentOwnerIds(plotIds).forEach((plotId, owners) -> counts.put(plotId, owners.size()));
        return counts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<UUID>> currentOwnerIds(Collection<UUID> plotIds) {
        Map<UUID, List<UUID>> owners = new LinkedHashMap<>();
        current(plotIds).forEach((plotId, rows) -> owners.put(plotId, rows.stream().map(Ownership::getMemberId)
                .toList()));
        return owners;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, List<OwnerShare>> currentOwnerShares(Collection<UUID> plotIds) {
        Map<UUID, List<OwnerShare>> owners = new LinkedHashMap<>();
        current(plotIds).forEach((plotId, rows) -> owners.put(plotId, rows.stream()
                .map(row -> new OwnerShare(row.getMemberId(), row.getShare().numerator(),
                        row.getShare().denominator()))
                .toList()));
        return owners;
    }

    /** The current ownership rows of each plot, the earliest owner first; every distinct id is a key. */
    private Map<UUID, List<Ownership>> current(Collection<UUID> plotIds) {
        List<UUID> ids = plotIds.stream().distinct().toList();
        Map<UUID, List<Ownership>> rows = new LinkedHashMap<>();
        ids.forEach(id -> rows.put(id, new ArrayList<>()));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            ownerships.findCurrentByPlotIds(ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .forEach(ownership -> rows.get(ownership.getPlotId()).add(ownership));
        }
        rows.replaceAll((plotId, current) -> current.stream().sorted(EARLIEST_FIRST).toList());
        return rows;
    }
}
