package app.nadel.tariffs.internal;

import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterResource;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tariffs.internal.TariffScopePlotRecord.Kind;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffExclusion;
import nadel.contract.model.TariffScopePlot;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link Tariff} of the API (versions, scope and plot count) and the plot counts of the list. Plots are read
 * only through {@link PlotCatalog}, meters only through {@link MeterCatalog}. The caller has the transaction of the
 * cooperative.
 * <p>
 * A plot counts for a tariff when it is active and not common land. {@code ALL_PLOTS} counts every such plot minus the
 * excluded such plots; {@code SELECTED_PLOTS} counts the selected such plots. Archived plots stay in the stored scope
 * (and are listed, marked archived) but are never counted. When the shown version is charged by meter ({@code PER_KWH}
 * or {@code PER_M3}), a plot also needs an active meter of that resource to count: such a tariff reaches only the
 * plots with a meter.
 */
@Component
class TariffAssembler {

    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final PlotCatalog plots;
    private final MeterCatalog meters;
    private final Clock clock;

    TariffAssembler(TariffVersionRepository versions, TariffScopePlotRepository scopePlots, PlotCatalog plots,
            MeterCatalog meters, Clock clock) {
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.plots = plots;
        this.meters = meters;
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
        if (!oldestFirst.isEmpty()) {
            @Nullable MeterResource resource = MeterBasis.resourceOf(
                    oldestFirst.get(TariffDtoMapper.shownIndex(oldestFirst, today)).getBasis());
            count = new Counter().count(tariff, resource, rows, facts);
        }
        return TariffDtoMapper.toDto(tariff, chargeType, oldestFirst, today, selected, excluded, count);
    }

    /**
     * The number of plots each tariff applies to, by tariff id.
     *
     * @param tariffs  the tariffs, each with the resource of the meter its shown version is charged by, or null when
     *                 the shown version is not charged by meter
     */
    Map<UUID, Long> plotCounts(Map<TariffRecord, @Nullable MeterResource> tariffs) {
        if (tariffs.isEmpty()) {
            return Map.of();
        }
        List<TariffScopePlotRecord> rows = scopePlots.findOf(tariffs.keySet().stream().map(TariffRecord::getId)
                .toList());
        Map<UUID, PlotFacts> facts = plots.findAll(plotIds(rows));
        Map<UUID, List<TariffScopePlotRecord>> byTariff = rows.stream()
                .collect(Collectors.groupingBy(TariffScopePlotRecord::getTariffId));

        Counter counter = new Counter();
        Map<UUID, Long> counts = new HashMap<>();
        tariffs.forEach((tariff, resource) -> counts.put(tariff.getId(),
                counter.count(tariff, resource, byTariff.getOrDefault(tariff.getId(), List.of()), facts)));
        return counts;
    }

    /**
     * Counts the plots of tariffs, loading what all of them share once: the number of chargeable plots and, per
     * resource, the chargeable plots with an active meter.
     */
    private final class Counter {

        private @Nullable Long chargeable;
        private final Map<MeterResource, Set<UUID>> metered = new EnumMap<>(MeterResource.class);

        long count(TariffRecord tariff, @Nullable MeterResource resource, List<TariffScopePlotRecord> rows,
                Map<UUID, PlotFacts> facts) {
            boolean all = tariff.getScopeKind() == ScopeKind.ALL_PLOTS;
            Kind counted = all ? Kind.EXCLUDED : Kind.SELECTED;
            Predicate<UUID> withMeter = resource == null ? plotId -> true : meteredPlots(resource)::contains;
            long inRows = rows.stream().filter(row -> row.getKind() == counted).filter(row -> {
                @Nullable PlotFacts plot = facts.get(row.getPlotId());
                return plot != null && !plot.archived() && !plot.commonLand() && withMeter.test(plot.id());
            }).count();
            if (!all) {
                return inRows;
            }
            long base = resource == null ? chargeable() : meteredPlots(resource).size();
            return base - inRows;
        }

        private long chargeable() {
            if (chargeable == null) {
                chargeable = plots.countChargeable();
            }
            return chargeable;
        }

        /** The active, non-common plots with an active meter of the resource. */
        private Set<UUID> meteredPlots(MeterResource resource) {
            return metered.computeIfAbsent(resource, r -> {
                Set<UUID> ids = meters.plotIdsWithActiveMeter(r);
                return plots.findAll(ids).values().stream().filter(plot -> !plot.archived() && !plot.commonLand())
                        .map(PlotFacts::id).collect(Collectors.toUnmodifiableSet());
            });
        }
    }

    private static Set<UUID> plotIds(List<TariffScopePlotRecord> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.getPlotId()));
        return ids;
    }
}
