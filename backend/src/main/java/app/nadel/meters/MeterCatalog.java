package app.nadel.meters;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * What another module may know about the plot meters of the cooperative: which plots a meter-based tariff reaches. The
 * cooperative is the one of the caller's transaction (row-level security); every method runs inside it.
 */
public interface MeterCatalog {

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
}
