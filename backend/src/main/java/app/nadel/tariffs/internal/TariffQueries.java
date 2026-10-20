package app.nadel.tariffs.internal;

import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.Basis;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffSummary;
import nadel.contract.model.TariffVersion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads tariffs. The cooperative is the one of the transaction (RLS); nothing is taken from the request. */
@Service
class TariffQueries {

    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final ChargeTypeRepository chargeTypes;
    private final PlotCatalog plots;
    private final Clock clock;

    TariffQueries(TariffRepository tariffs, TariffVersionRepository versions, TariffScopePlotRepository scopePlots,
            ChargeTypeRepository chargeTypes, PlotCatalog plots, Clock clock) {
        this.tariffs = tariffs;
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.chargeTypes = chargeTypes;
        this.plots = plots;
        this.clock = clock;
    }

    /**
     * The tariffs of the non-archived charge types in list order, each with the version valid today (Kyiv) or else the
     * earliest future one, and the number of plots it applies to.
     */
    @Transactional(readOnly = true)
    List<TariffSummary> list() {
        LocalDate today = LocalDate.now(clock);
        List<TariffRecord> all = tariffs.findOfActiveChargeTypes();
        if (all.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = all.stream().map(TariffRecord::getId).toList();
        Map<UUID, List<TariffVersionRecord>> byTariff = versions.findByTariffIdInOrderByTariffIdAscValidFromAsc(ids)
                .stream().collect(Collectors.groupingBy(TariffVersionRecord::getTariffId));
        Map<UUID, Long> selectedCounts = selectedPlotCounts(all);
        Long allPlots = null;

        List<TariffSummary> result = new ArrayList<>(all.size());
        for (TariffRecord tariff : all) {
            List<TariffVersionRecord> oldestFirst = byTariff.getOrDefault(tariff.getId(), List.of());
            if (oldestFirst.isEmpty()) {
                continue; // a tariff is created with its first version, so this cannot happen
            }
            int shown = shownIndex(oldestFirst, today);
            TariffVersionRecord version = oldestFirst.get(shown);
            LocalDate validTo = shown + 1 < oldestFirst.size() ? oldestFirst.get(shown + 1).getValidFrom().minusDays(1)
                    : null;
            TariffVersion dto = TariffDtoMapper.version(version, validTo, today);
            var summary = new TariffSummary(tariff.getId(), tariff.getName(), tariff.getChargeTypeId(),
                    TariffDtoMapper.scopeKind(tariff), dto);
            if (version.getBasis() != Basis.PER_KWH && version.getBasis() != Basis.PER_M3) {
                if (tariff.getScopeKind() == ScopeKind.ALL_PLOTS) {
                    if (allPlots == null) {
                        allPlots = plots.countChargeable();
                    }
                    summary.setPlotCount(allPlots);
                } else {
                    summary.setPlotCount(selectedCounts.getOrDefault(tariff.getId(), 0L));
                }
            }
            result.add(summary);
        }
        return result;
    }

    /** The tariff with its charge type (also an archived one) and every version, newest first. */
    @Transactional(readOnly = true)
    Tariff get(UUID id) {
        TariffRecord tariff = tariffs.findById(id).orElseThrow(ApiProblemException::notFound);
        ChargeTypeRecord chargeType = chargeTypes.findById(tariff.getChargeTypeId())
                .orElseThrow(ApiProblemException::notFound);
        return TariffDtoMapper.toDto(tariff, chargeType, versions.findByTariffIdOrderByValidFromAsc(id),
                LocalDate.now(clock));
    }

    /** Index of the version valid on {@code today}, else of the earliest later one. {@code oldestFirst} is not empty. */
    private static int shownIndex(List<TariffVersionRecord> oldestFirst, LocalDate today) {
        int current = -1;
        for (int i = 0; i < oldestFirst.size(); i++) {
            if (!oldestFirst.get(i).getValidFrom().isAfter(today)) {
                current = i;
            }
        }
        return current >= 0 ? current : 0;
    }

    /**
     * For every selected-plots tariff, the number of its selected plots that are active and not common land. The
     * plots are read through {@link PlotCatalog} in one call.
     */
    private Map<UUID, Long> selectedPlotCounts(List<TariffRecord> all) {
        List<UUID> selectedTariffs = all.stream().filter(t -> t.getScopeKind() == ScopeKind.SELECTED_PLOTS)
                .map(TariffRecord::getId).toList();
        if (selectedTariffs.isEmpty()) {
            return Map.of();
        }
        List<TariffScopePlotRecord> rows = scopePlots.findSelectedOf(selectedTariffs);
        Set<UUID> plotIds = new HashSet<>();
        rows.forEach(row -> plotIds.add(row.getPlotId()));
        Map<UUID, PlotFacts> facts = plots.findAll(plotIds);
        Map<UUID, Long> counts = new HashMap<>();
        for (TariffScopePlotRecord row : rows) {
            @Nullable PlotFacts plot = facts.get(row.getPlotId());
            if (plot != null && !plot.archived() && !plot.commonLand()) {
                counts.merge(row.getTariffId(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
