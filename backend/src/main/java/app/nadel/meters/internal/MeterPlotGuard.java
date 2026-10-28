package app.nadel.meters.internal;

import app.nadel.plots.PlotChangeGuard;
import app.nadel.problem.ProblemCodes;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A plot that has a meter (active or removed, so its history stays readable) cannot be deleted or turned into common
 * land, and a plot with an active meter cannot be archived: {@code PLOT_HAS_METER} until the meter is removed.
 * <p>
 * Runs inside the plot's transaction after the plot row was locked {@code FOR UPDATE}. Registering a plot meter takes the
 * plot {@code FOR SHARE} first, so the two never interleave: if the registration committed first this check sees the
 * meter; if the plot change committed first the registration finds the plot gone, archived or common land. The guard
 * only reads and takes no locks.
 * <p>
 * Asked after {@code ledger}'s guard and before {@link WaterConnectionPlotGuard}, so the first refusal of a plot is
 * deterministic.
 */
@Component
@Order(MeterPlotGuard.ORDER)
class MeterPlotGuard implements PlotChangeGuard {

    /** The position among the plot guards (lower is asked first). */
    static final int ORDER = 200;

    private static final Refusal HAS_METER = new Refusal(ProblemCodes.PLOT_HAS_METER,
            "The plot has a meter; remove the meter first.");

    private final MeterRepository meters;

    MeterPlotGuard(MeterRepository meters) {
        this.meters = meters;
    }

    @Override
    public Map<UUID, Refusal> refusals(Change change, Collection<UUID> plotIds) {
        if (plotIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> refused = change == Change.ARCHIVE ? meters.plotIdsWithActiveMeter(plotIds)
                : meters.plotIdsWithMeter(plotIds);
        Map<UUID, Refusal> refusals = new HashMap<>();
        refused.forEach(plotId -> refusals.put(plotId, HAS_METER));
        return refusals;
    }
}
