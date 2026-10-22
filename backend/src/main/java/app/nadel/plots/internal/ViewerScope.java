package app.nadel.plots.internal;

import app.nadel.settings.PlotStatusVisibility;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What the viewer of one request may see of plots, decided once and used for the list, the card and the map.
 *
 * @param full         the viewer holds {@code PLOTS_VIEW}: every plot in full, including notes and archived plots
 * @param ownedPlotIds the plots the viewer's member owns now (a former owner owns nothing); empty for a full viewer,
 *                     who does not need it
 * @param setting      who sees the status of plots that are not theirs; {@code ALL_PLOTS} for a full viewer, whose
 *                     view the setting never narrows (so it is not read for them)
 * @param statusBasis  the red threshold and today, from which every status of the request is derived
 */
record ViewerScope(boolean full, Set<UUID> ownedPlotIds, PlotStatusVisibility setting,
        PlotStatusPolicy.Basis statusBasis) {

    ViewerScope {
        ownedPlotIds = Set.copyOf(ownedPlotIds);
        Objects.requireNonNull(statusBasis, "statusBasis");
    }

    /** A viewer with {@code PLOTS_VIEW}. */
    static ViewerScope full(PlotStatusPolicy.Basis statusBasis) {
        return new ViewerScope(true, Set.of(), PlotStatusVisibility.ALL_PLOTS, statusBasis);
    }

    /** A viewer who sees nothing beyond the basics: no owned plots, own plots only. */
    static ViewerScope none(PlotStatusPolicy.Basis statusBasis) {
        return new ViewerScope(false, Set.of(), PlotStatusVisibility.OWN_ONLY, statusBasis);
    }

    /** Area, cadastral number, building flag and, on the map, area and debt: the board and the plot's owners. */
    boolean canSeeDetails(UUID plotId) {
        return full || ownedPlotIds.contains(plotId);
    }

    /** Notes and the created/updated timestamps stay with the board, owners of the plot included. */
    boolean canSeeNotes() {
        return full;
    }

    /** The colour of the plot: the board, its owners, and everyone when the cooperative shows all colours. */
    boolean canSeeStatus(UUID plotId) {
        return full || ownedPlotIds.contains(plotId) || setting == PlotStatusVisibility.ALL_PLOTS;
    }

    /** True when the status filter of this viewer matches only the plots they own. */
    boolean statusFilterLimitedToOwned() {
        return !full && setting == PlotStatusVisibility.OWN_ONLY;
    }
}
