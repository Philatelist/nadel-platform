package app.nadel.plots.internal;

import app.nadel.plots.PlotCatalog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serves the public {@link PlotCatalog} from the plot table; RLS limits it to the caller's cooperative. */
@Component
class JpaPlotCatalog implements PlotCatalog {

    /** Upper bound of one {@code IN (...)} list. */
    private static final int CHUNK = 500;

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
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlotFacts> findAndLockShared(UUID id) {
        return plots.findByIdForShare(id).map(JpaPlotCatalog::facts);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PlotFacts> findAllAndLockShared(Collection<UUID> ids) {
        List<UUID> sorted = ids.stream().distinct().sorted(UuidOrder.POSTGRES).toList();
        List<PlotFacts> found = new ArrayList<>();
        for (int from = 0; from < sorted.size(); from += CHUNK) {
            plots.lockAllForShare(sorted.subList(from, Math.min(sorted.size(), from + CHUNK))).stream()
                    .map(JpaPlotCatalog::facts)
                    .forEach(found::add);
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlotFacts> findActiveByNumbers(Collection<String> numbers) {
        List<String> lower = numbers.stream().map(number -> number.strip().toLowerCase(Locale.ROOT)).distinct()
                .toList();
        List<PlotFacts> found = new ArrayList<>();
        for (int from = 0; from < lower.size(); from += CHUNK) {
            plots.findActiveWithLowerNumbers(lower.subList(from, Math.min(lower.size(), from + CHUNK))).stream()
                    .map(JpaPlotCatalog::facts)
                    .forEach(found::add);
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> inNaturalOrder(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : plots.findIdsInNaturalOrder(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> inStreetOrder(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : plots.findIdsInStreetOrder(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public long countChargeable() {
        return plots.countByArchivedAtIsNullAndKindNot(PlotKind.COMMON);
    }

    private static PlotFacts facts(Plot plot) {
        return new PlotFacts(plot.getId(), plot.getNumber(), plot.getStreet(), plot.getKind() == PlotKind.COMMON,
                plot.getArchivedAt() != null, plot.getAreaM2());
    }
}
