package app.nadel.plots;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The status of plots for the staff reports of feature 013, derived with the rule of the map (money rules 9): GREY for
 * common land and for a plot without a current owner, RED when the debt is above the cooperative's red amount or its
 * oldest unpaid charge is more than the red days old, YELLOW for any other debt, GREEN without a debt. The thresholds
 * are the current ones (settings {@code redAmount}, {@code redDays}), read once per call. Both methods run in the
 * caller's transaction ({@code Propagation.MANDATORY}) bound to the cooperative; plots of other cooperatives and unknown
 * ids are absent from the result. Staff-only: the caller checks its permissions; the member visibility setting is not
 * applied here.
 */
public interface PlotStatuses {

    /**
     * The debt of a plot on a day, as the ledger reports it ({@code ledger.LedgerReadModel#debtsAsOf}).
     *
     * @param total        the debt of the plot
     * @param oldestUnpaid the date of its oldest unpaid charge; null when nothing is unpaid
     */
    record Debt(Money total, @Nullable LocalDate oldestUnpaid) {

        public Debt {
            Objects.requireNonNull(total, "total");
        }
    }

    /**
     * The status each plot shows on the map today: the debt and oldest unpaid date cached in {@code plot_status}
     * (rebuilt from the ledger after every booking), the current owners, the current thresholds and today (Europe/Kyiv).
     * A plot without a cache row has no debt.
     */
    Map<UUID, PlotStatus> current(Collection<UUID> plotIds);

    /**
     * The status of each plot for the given debt on the day {@code asOf}: the rule of {@link #current} with
     * {@code asOf} in place of today (debt age = {@code asOf} minus the oldest unpaid date) and the given debt in place
     * of the cache. The thresholds are today's (a past report says so in its header), and so are the plot's kind and
     * owners: GREY means common land or no current owner now. With the cached debt and today it gives exactly
     * {@link #current}.
     *
     * @param debts the debt of each plot, keyed by plot id
     * @param asOf  the day the debt age is counted to
     */
    Map<UUID, PlotStatus> classify(Map<UUID, Debt> debts, LocalDate asOf);
}
