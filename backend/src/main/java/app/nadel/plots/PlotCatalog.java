package app.nadel.plots;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only facts about plots for other modules (tariffs), so they never touch the plot table. The caller has bound the
 * cooperative (a transaction of the tenant-aware transaction manager): plots of other cooperatives are invisible.
 */
public interface PlotCatalog {

    /**
     * What a module may know about a plot.
     *
     * @param id         the plot
     * @param number     the plot number as the cooperative writes it
     * @param commonLand whether it is common land (roads, a well area), which has no tariffs
     * @param archived   whether it is archived
     * @param areaM2     the area in square metres, two decimals
     */
    record PlotFacts(UUID id, String number, boolean commonLand, boolean archived, BigDecimal areaM2) {
    }

    /** The plot, archived or not; empty when it does not exist in the cooperative. */
    Optional<PlotFacts> find(UUID id);

    /** The given plots, archived ones included. Ids that do not exist in the cooperative are absent from the map. */
    Map<UUID, PlotFacts> findAll(Collection<UUID> ids);

    /**
     * The given plots' ids in natural order of the plot number (12, 12а, 12б, 100), the order of the plots list. Ids
     * that do not exist in the cooperative are left out.
     */
    List<UUID> inNaturalOrder(Collection<UUID> ids);

    /** The number of plots a tariff for all plots applies to: active (not archived) and not common land. */
    long countChargeable();
}
