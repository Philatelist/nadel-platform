package app.nadel.ledger.internal;

import app.nadel.plots.PlotChangeGuard;
import app.nadel.problem.ProblemCodes;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A plot that has charges (any kind, reversed or not) is never deleted: the ledger keeps them for good, so the answer
 * is 409 {@code PLOT_HAS_CHARGES} (the foreign key {@code charge_plot_fk} is the backstop). Archiving a plot and turning
 * it into common land are not refused here.
 * <p>
 * Runs inside the plot's transaction after the plot row was locked {@code FOR UPDATE}. An opening balance takes the
 * plot {@code FOR SHARE} first, so the two never interleave. The guard only reads and takes no locks.
 * <p>
 * Asked first among the plot guards ({@code @Order(100)}; {@code meters} follows), so a plot that has both charges
 * and a meter is refused with {@code PLOT_HAS_CHARGES}.
 */
@Component
@Order(100)
class LedgerPlotGuard implements PlotChangeGuard {

    private static final Refusal HAS_CHARGES = new Refusal(ProblemCodes.PLOT_HAS_CHARGES,
            "The plot has charges; the ledger keeps them.");

    private final ChargeRepository charges;

    LedgerPlotGuard(ChargeRepository charges) {
        this.charges = charges;
    }

    @Override
    public Map<UUID, Refusal> refusals(Change change, Collection<UUID> plotIds) {
        Map<UUID, Refusal> refusals = new HashMap<>();
        if (change == Change.DELETE && !plotIds.isEmpty()) {
            charges.plotIdsWithCharges(plotIds).forEach(plotId -> refusals.put(plotId, HAS_CHARGES));
        }
        return refusals;
    }
}
