package app.nadel.plots.internal;

import app.nadel.money.Money;
import app.nadel.plots.PlotDebtCache;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link PlotDebtCache} from {@code plot_status}. Besides the debt and its oldest date it writes the status the
 * plot shows today into the {@code status} column, a snapshot for the previous release (this release derives the status
 * at read time and never reads the column).
 */
@Component
class JpaPlotDebtCache implements PlotDebtCache {

    /** Values per {@code IN} list; the chunks follow the database's uuid order, so the lock order is one order. */
    private static final int CHUNK = 500;

    private final PlotStatusRepository statuses;
    private final PlotRepository plots;
    private final OwnershipRepository ownerships;
    private final PlotStatusBasis statusBasis;
    private final Clock clock;

    JpaPlotDebtCache(PlotStatusRepository statuses, PlotRepository plots, OwnershipRepository ownerships,
            PlotStatusBasis statusBasis, Clock clock) {
        this.statuses = statuses;
        this.plots = plots;
        this.ownerships = ownerships;
        this.statusBasis = statusBasis;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAll(Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return;
        }
        List<UUID> ids = plotIds.stream().distinct().sorted(UuidOrder.POSTGRES).toList();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
            statuses.insertMissing(chunk);
            statuses.lockAllForUpdate(chunk);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID plotId, Money debt, @Nullable LocalDate oldestDebtDate, Money prepayment) {
        if (prepayment.signum() < 0) {
            throw new IllegalArgumentException("A prepayment is never negative");
        }
        PlotStatusEntry entry = statuses.findById(plotId).orElse(null);
        Plot plot = plots.findById(plotId).orElse(null);
        if (entry == null || plot == null) {
            return;
        }
        PlotStatusCode snapshot = PlotStatusPolicy.statusOf(plot.getKind(), ownerships.existsCurrentByPlotId(plotId),
                debt, oldestDebtDate, statusBasis.current());
        entry.recordDebt(debt.value(), oldestDebtDate, prepayment.value(), snapshot, clock.instant());
    }
}
