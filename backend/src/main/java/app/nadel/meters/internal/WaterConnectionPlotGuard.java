package app.nadel.meters.internal;

import app.nadel.plots.PlotChangeGuard;
import app.nadel.problem.ProblemCodes;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A plot that has a lasting water connection (no end date) can be neither archived nor turned into common land:
 * {@code PLOT_HAS_WATER_CONNECTION} until the connection is ended. A connection that has an end date, even a later one,
 * does not refuse. A delete is not refused here: the connections go with the plot ({@code ON DELETE CASCADE}).
 * <p>
 * Runs inside the plot's transaction after the plot row was locked {@code FOR UPDATE}. Connecting a plot, ending a
 * connection and the Excel import's water column take the plot {@code FOR SHARE} first, so they never interleave with a
 * plot change: if the connection committed first this check sees it; if the plot change committed first the connection
 * finds the plot archived or common land. The guard only reads and takes no locks.
 * <p>
 * Asked after {@link MeterPlotGuard}, so a plot that has both an active meter and a lasting connection is refused with
 * {@code PLOT_HAS_METER}.
 */
@Component
@Order(MeterPlotGuard.ORDER + 100)
class WaterConnectionPlotGuard implements PlotChangeGuard {

    private static final Refusal HAS_CONNECTION = new Refusal(ProblemCodes.PLOT_HAS_WATER_CONNECTION,
            "The plot has a lasting water connection; end the connection first.");

    private final WaterConnectionRepository connections;

    WaterConnectionPlotGuard(WaterConnectionRepository connections) {
        this.connections = connections;
    }

    @Override
    public Map<UUID, Refusal> refusals(Change change, Collection<UUID> plotIds) {
        if (change == Change.DELETE || plotIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Refusal> refusals = new HashMap<>();
        connections.plotIdsWithLastingConnection(plotIds).forEach(plotId -> refusals.put(plotId, HAS_CONNECTION));
        return refusals;
    }
}
