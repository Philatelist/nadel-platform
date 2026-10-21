package app.nadel.plots;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only facts about plots for other modules (tariffs, meters), so they never touch the plot table. The caller has
 * bound the cooperative (a transaction of the tenant-aware transaction manager): plots of other cooperatives are
 * invisible.
 */
public interface PlotCatalog {

    /**
     * What a module may know about a plot.
     *
     * @param id         the plot
     * @param number     the plot number as the cooperative writes it
     * @param street     the street as the cooperative writes it; null when the plot has none
     * @param commonLand whether it is common land (roads, a well area), which has no tariffs and no plot meters
     * @param archived   whether it is archived
     * @param areaM2     the area in square metres, two decimals
     */
    record PlotFacts(UUID id, String number, @Nullable String street, boolean commonLand, boolean archived,
            BigDecimal areaM2) {
    }

    /** The plot, archived or not; empty when it does not exist in the cooperative. */
    Optional<PlotFacts> find(UUID id);

    /** The given plots, archived ones included. Ids that do not exist in the cooperative are absent from the map. */
    Map<UUID, PlotFacts> findAll(Collection<UUID> ids);

    /**
     * The plot, archived or not, with a shared row lock ({@code FOR SHARE}) until the end of the caller's transaction;
     * empty when it does not exist in the cooperative. A module that writes data depending on the plot (a plot meter)
     * takes this lock first: it waits for a delete, archive or edit of the plot in progress (they hold
     * {@code FOR UPDATE} and ask every {@link PlotChangeGuard}), and those wait for it, so a guard always sees the
     * committed data. Must run inside the caller's transaction.
     */
    Optional<PlotFacts> findAndLockShared(UUID id);

    /**
     * {@link #findAndLockShared} for many plots at once (the Excel import's meters): the rows are locked
     * {@code FOR SHARE} in id order, so two transactions that lock overlapping sets never wait for each other in a
     * circle. Ids that do not exist in the cooperative are left out. Must run inside the caller's transaction.
     */
    List<PlotFacts> findAllAndLockShared(Collection<UUID> ids);

    /**
     * The active (not archived) plots whose number is among {@code numbers}, ignoring case, for an import that names
     * plots by number. Numbers that match no active plot are left out; the caller keys the result by number.
     */
    List<PlotFacts> findActiveByNumbers(Collection<String> numbers);

    /**
     * The given plots' ids in natural order of the plot number (12, 12а, 12б, 100), the order of the plots list. Ids
     * that do not exist in the cooperative are left out.
     */
    List<UUID> inNaturalOrder(Collection<UUID> ids);

    /**
     * The given plots' ids in street order (the Ukrainian alphabet, ignoring case; plots without a street last), then
     * natural order of the number within a street: the order of a meter round. Ids that do not exist in the cooperative
     * are left out.
     */
    List<UUID> inStreetOrder(Collection<UUID> ids);

    /** The number of plots a tariff for all plots applies to: active (not archived) and not common land. */
    long countChargeable();
}
