package app.nadel.plots;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The writer of the debt cache behind the plot status (feature 011, functional spec 2.10): the ledger rebuilds the debt
 * of plots from its committed charges, payments and allocations and records it here; the plots module derives the colour from the cached debt,
 * the red threshold of the settings and today whenever a status is read. Both methods run in the caller's transaction
 * (the cooperative bound by the tenant-aware transaction manager; RLS hides other cooperatives' rows).
 */
public interface PlotDebtCache {

    /**
     * Locks the cache rows of the plots ({@code FOR UPDATE}, in id order, so two refreshes of overlapping sets never
     * wait for each other in a circle), creating a missing row first. Plots that do not exist in the cooperative are
     * ignored. Call it before reading the ledger, so a later refresh of the same plot waits and then reads at least what
     * this one read.
     */
    void lockAll(Collection<UUID> plotIds);

    /**
     * Records the plot's debt after payments, the day its oldest unpaid charge counts from (null when nothing is
     * unpaid) and its prepayment (money paid for the plot that is on no charge, never below zero; feature 015). A plot
     * without a cache row (it does not exist in the cooperative) is ignored.
     */
    void record(UUID plotId, Money debt, @Nullable LocalDate oldestDebtDate, Money prepayment);
}
