package app.nadel.plots.internal;

import app.nadel.plots.PlotCatalog;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serves the public {@link PlotCatalog} from the plot table; RLS limits it to the caller's cooperative. */
@Component
class JpaPlotCatalog implements PlotCatalog {

    private final PlotRepository plots;

    JpaPlotCatalog(PlotRepository plots) {
        this.plots = plots;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlotFacts> find(UUID id) {
        return plots.findById(id).map(JpaPlotCatalog::facts);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PlotFacts> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return plots.findAllById(ids).stream().map(JpaPlotCatalog::facts)
                .collect(Collectors.toMap(PlotFacts::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> inNaturalOrder(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : plots.findIdsInNaturalOrder(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public long countChargeable() {
        return plots.countByArchivedAtIsNullAndKindNot(PlotKind.COMMON);
    }

    private static PlotFacts facts(Plot plot) {
        return new PlotFacts(plot.getId(), plot.getNumber(), plot.getKind() == PlotKind.COMMON,
                plot.getArchivedAt() != null, plot.getAreaM2());
    }
}
