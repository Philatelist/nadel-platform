package app.nadel.tariffs.internal;

import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tariffs.internal.TariffScopePlotRecord.Kind;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffExclusion;
import nadel.contract.model.TariffScopePlot;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link Tariff} of the API (versions, scope and plot count) and the plot counts of the list. Plots are read
 * only through {@link PlotCatalog}. The caller has the transaction of the cooperative.
 * <p>
 * A plot counts for a tariff when it is active and not common land. {@code ALL_PLOTS} counts every such plot minus the
 * excluded such plots; {@code SELECTED_PLOTS} counts the selected such plots. Archived plots stay in the stored scope
 * (and are listed, marked archived) but are never counted.
 */
@Component
class TariffAssembler {

    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final PlotCatalog plots;
    private final Clock clock;

    TariffAssembler(TariffVersionRepository versions, TariffScopePlotRepository scopePlots, PlotCatalog plots,
            Clock clock) {
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.plots = plots;
        this.clock = clock;
    }

    /** The tariff with every version newest first, its scope in natural order of the plot numbers, and the count. */
    Tariff assemble(TariffRecord tariff, ChargeTypeRecord chargeType) {
        LocalDate today = LocalDate.now(clock);
        List<TariffVersionRecord> oldestFirst = versions.findByTariffIdOrderByValidFromAsc(tariff.getId());
        List<TariffScopePlotRecord> rows = scopePlots.findByTariffId(tariff.getId());
        Map<UUID, PlotFacts> facts = plots.findAll(plotIds(rows));

        List<TariffScopePlot> selected = new ArrayList<>();
        List<TariffExclusion> excluded = new ArrayList<>();
        Map<UUID, TariffScopePlotRecord> byPlot = rows.stream()
                .collect(Collectors.toMap(TariffScopePlotRecord::getPlotId, row -> row));
        for (UUID plotId : plots.inNaturalOrder(byPlot.keySet())) {
            TariffScopePlotRecord row = byPlot.get(plotId);
            PlotFacts plot = facts.get(plotId);
            if (row.getKind() == Kind.SELECTED) {
                selected.add(new TariffScopePlot(plotId, plot.number(), plot.archived()));
            } else {
                excluded.add(new TariffExclusion(plotId, plot.number(), plot.archived(), row.getReason()));
            }
        }

        Long count = null;
        if (!oldestFirst.isEmpty() && !TariffDtoMapper.meterBased(oldestFirst.get(
                TariffDtoMapper.shownIndex(oldestFirst, today)))) {
            count = countOf(tariff, rows, facts, plots::countChargeable);
        }
        return TariffDtoMapper.toDto(tariff, chargeType, oldestFirst, today, selected, excluded, count);
    }

    /**
     * The number of plots each tariff applies to, by tariff id. Whether to show it (not for a meter-based version) is
     * the caller's decision.
     */
    Map<UUID, Long> plotCounts(Collection<TariffRecord> tariffs) {
        if (tariffs.isEmpty()) {
            return Map.of();
        }
        List<TariffScopePlotRecord> rows = scopePlots.findOf(tariffs.stream().map(TariffRecord::getId).toList());
        Map<UUID, PlotFacts> facts = plots.findAll(plotIds(rows));
        Map<UUID, List<TariffScopePlotRecord>> byTariff = rows.stream()
                .collect(Collectors.groupingBy(TariffScopePlotRecord::getTariffId));
        long[] chargeable = {-1};
        LongSupplier allPlots = () -> chargeable[0] >= 0 ? chargeable[0] : (chargeable[0] = plots.countChargeable());

        Map<UUID, Long> counts = new HashMap<>();
        for (TariffRecord tariff : tariffs) {
            counts.put(tariff.getId(),
                    countOf(tariff, byTariff.getOrDefault(tariff.getId(), List.of()), facts, allPlots));
        }
        return counts;
    }

    private static long countOf(TariffRecord tariff, List<TariffScopePlotRecord> rows, Map<UUID, PlotFacts> facts,
            LongSupplier allPlots) {
        boolean all = tariff.getScopeKind() == ScopeKind.ALL_PLOTS;
        Kind counted = all ? Kind.EXCLUDED : Kind.SELECTED;
        long inRows = rows.stream().filter(row -> row.getKind() == counted).filter(row -> {
            @Nullable PlotFacts plot = facts.get(row.getPlotId());
            return plot != null && !plot.archived() && !plot.commonLand();
        }).count();
        return all ? allPlots.getAsLong() - inRows : inRows;
    }

    private static Set<UUID> plotIds(List<TariffScopePlotRecord> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.getPlotId()));
        return ids;
    }
}
