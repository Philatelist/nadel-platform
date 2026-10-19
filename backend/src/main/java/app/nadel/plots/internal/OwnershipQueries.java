package app.nadel.plots.internal;

import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.PlotOwnerSummary;
import nadel.contract.model.PlotOwnerships;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads who owns plots. Member names come from {@link MemberDirectory}; the member tables are never read here. */
@Service
class OwnershipQueries {

    private final PlotRepository plots;
    private final OwnershipRepository ownerships;
    private final MemberDirectory members;

    OwnershipQueries(PlotRepository plots, OwnershipRepository ownerships, MemberDirectory members) {
        this.plots = plots;
        this.ownerships = ownerships;
        this.members = members;
    }

    /**
     * The current owners (by member name) and the history (newest first) of a plot. Empty when the plot does not exist
     * or belongs to another cooperative (RLS).
     */
    @Transactional(readOnly = true)
    Optional<PlotOwnerships> ofPlot(UUID plotId) {
        if (!plots.existsById(plotId)) {
            return Optional.empty();
        }
        List<Ownership> current = ownerships.findCurrentByPlotId(plotId);
        List<Ownership> history = ownerships.findHistoryByPlotId(plotId);
        Map<UUID, MemberRef> refs = members.find(memberIds(current, history));

        var result = new PlotOwnerships();
        result.setPlotId(plotId);
        result.setCurrent(current.stream()
                .sorted(Comparator.comparing((Ownership o) -> nameOf(refs, o), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Ownership::getValidFrom)
                        .thenComparing(Ownership::getId))
                .map(o -> toDto(o, refs))
                .toList());
        result.setHistory(history.stream().map(o -> toDto(o, refs)).toList());
        return Optional.of(result);
    }

    /**
     * The current owners of the given plots (name and share, ordered by name), keyed by plot id. A plot without a
     * current owner has no entry. One query for the ownership rows and one for the member names, whatever the number
     * of plots.
     */
    @Transactional(readOnly = true)
    Map<UUID, List<PlotOwnerSummary>> currentOwners(Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return Map.of();
        }
        List<Ownership> current = ownerships.findCurrentByPlotIds(plotIds);
        Map<UUID, MemberRef> refs = members.find(memberIds(current, List.of()));
        return current.stream()
                .collect(Collectors.groupingBy(Ownership::getPlotId, Collectors.collectingAndThen(
                        Collectors.toList(), rows -> rows.stream()
                                .sorted(Comparator.comparing((Ownership o) -> nameOf(refs, o),
                                        String.CASE_INSENSITIVE_ORDER).thenComparing(Ownership::getMemberId))
                                .map(o -> toSummary(o, refs))
                                .toList())));
    }

    private static Set<UUID> memberIds(List<Ownership> current, List<Ownership> history) {
        Set<UUID> ids = new HashSet<>();
        current.forEach(o -> ids.add(o.getMemberId()));
        history.forEach(o -> ids.add(o.getMemberId()));
        return ids;
    }

    private static String nameOf(Map<UUID, MemberRef> refs, Ownership ownership) {
        return refs.get(ownership.getMemberId()).shortName();
    }

    private static nadel.contract.model.Ownership toDto(Ownership o, Map<UUID, MemberRef> refs) {
        MemberRef member = refs.get(o.getMemberId());
        var dto = new nadel.contract.model.Ownership();
        dto.setId(o.getId());
        dto.setMemberId(o.getMemberId());
        dto.setMemberName(member.shortName());
        dto.setMemberArchived(member.archived());
        dto.setShare(o.getShare().toString());
        dto.setValidFrom(o.getValidFrom());
        dto.setValidTo(o.getValidTo());
        dto.setBasis(o.getBasis());
        return dto;
    }

    private static PlotOwnerSummary toSummary(Ownership o, Map<UUID, MemberRef> refs) {
        var dto = new PlotOwnerSummary();
        dto.setMemberId(o.getMemberId());
        dto.setName(nameOf(refs, o));
        dto.setShare(o.getShare().toString());
        return dto;
    }
}
