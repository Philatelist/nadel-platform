package app.nadel.meters;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What another module may know about the meters of the cooperative: which plots a meter-based tariff reaches, the
 * kind and name of one meter, and its chain of replacements. The cooperative is the one of the caller's transaction (row-level security); every
 * method runs inside it.
 */
public interface MeterCatalog {

    /**
     * What another module may know about one meter.
     *
     * @param name   the name of a named meter (substation, quarter, common consumer; user content); null for a plot meter
     * @param active whether it is not removed
     */
    record MeterFacts(UUID id, MeterKind kind, MeterResource resource, @Nullable String name, boolean active) {
    }

    /** The meter, removed or not; empty when it does not exist in the cooperative. */
    Optional<MeterFacts> find(UUID meterId);

    /**
     * The ids of the meter's chain of replacements: the meters it replaced and the meters that replaced it, with the
     * meter itself, oldest first (the order of {@code MeterNetwork.NetworkChain#meterIds}); the meter alone when it was
     * never replaced and replaced nothing; empty when it does not exist in the cooperative.
     */
    List<UUID> chainOf(UUID meterId);

    /**
     * Every plot of the cooperative that has an active (not removed) plot meter of the resource now. Archived plots and
     * common land never have one (the plot guard refuses both while a meter is active).
     */
    Set<UUID> plotIdsWithActiveMeter(MeterResource resource);

    /**
     * Whether the plot had a plot meter of the resource working on at least one day from {@code from} to {@code to}
     * (both included): installed on or before {@code to} and not removed before {@code from}. A meter removed on a day
     * still worked that day.
     */
    boolean hasMeterBetween(UUID plotId, MeterResource resource, LocalDate from, LocalDate to);

    /**
     * The plot meters of the plots that measure one of the resources, active and removed, in id order (a
     * recalculation charges the intervals of every meter a plot has had, feature 012). No locks: the caller locks each
     * meter through {@code MeterNetwork#lockSharedPoints}.
     */
    List<UUID> plotMeterIds(Collection<UUID> plotIds, Collection<MeterResource> resources);
}
