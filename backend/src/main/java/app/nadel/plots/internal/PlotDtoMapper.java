package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.time.ZoneOffset;
import java.util.List;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotOwnerSummary;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.PlotSummary;

/** Turns plots into contract objects, shaped for the viewer. The only place that decides who sees which fields. */
final class PlotDtoMapper {

    private PlotDtoMapper() {
    }

    /**
     * The one place that decides who sees area, cadastral number, building, notes and debt of a plot. Today that is
     * everyone with {@code PLOTS_VIEW}; owning the plot joins the rule later in spec 007.
     */
    static boolean canSeeDetails(AccessProfile viewer, Plot plot) {
        return canSeeAllDetails(viewer);
    }

    /**
     * Whether the viewer sees the details of every plot, which also decides what the list may search by (cadastral
     * number) and whether archived plots are listed. Stays {@code PLOTS_VIEW} after 007: own plots never widen a search.
     */
    static boolean canSeeAllDetails(AccessProfile viewer) {
        return viewer.can(Permission.PLOTS_VIEW);
    }

    static PlotStatus statusOf(PlotRow row) {
        return PlotStatus.fromValue(PlotStatusPolicy.statusOf(row.plot().getKind(), row.status()).name());
    }

    /**
     * @param owners the current owners of the plot, or null/empty when it has none; they are shown only to a viewer
     *               who may see the details of every plot
     */
    static PlotSummary toSummary(AccessProfile viewer, PlotRow row, List<PlotOwnerSummary> owners) {
        Plot plot = row.plot();
        var dto = new PlotSummary();
        dto.setId(plot.getId());
        dto.setNumber(plot.getNumber());
        dto.setStreet(plot.getStreet());
        dto.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        dto.setStatus(statusOf(row));
        dto.setArchived(plot.getArchivedAt() != null);
        if (canSeeDetails(viewer, plot)) {
            dto.setAreaM2(plot.getAreaM2());
            dto.setCadastralNumber(plot.getCadastralNumber());
            dto.setHasBuilding(plot.hasBuilding());
        }
        // the generated model defaults to an empty list, which would be written as "owners": []
        boolean showOwners = canSeeAllDetails(viewer) && owners != null && !owners.isEmpty();
        dto.setOwners(showOwners ? owners : null);
        return dto;
    }

    static PlotDetails toDetails(AccessProfile viewer, PlotRow row) {
        Plot plot = row.plot();
        var dto = new PlotDetails();
        dto.setId(plot.getId());
        dto.setNumber(plot.getNumber());
        dto.setStreet(plot.getStreet());
        dto.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        dto.setStatus(statusOf(row));
        dto.setArchived(plot.getArchivedAt() != null);
        if (plot.getArchivedAt() != null) {
            dto.setArchivedAt(plot.getArchivedAt().atOffset(ZoneOffset.UTC));
        }
        if (canSeeDetails(viewer, plot)) {
            dto.setAreaM2(plot.getAreaM2());
            dto.setCadastralNumber(plot.getCadastralNumber());
            dto.setHasBuilding(plot.hasBuilding());
            dto.setNotes(plot.getNotes());
            dto.setCreatedAt(plot.getCreatedAt().atOffset(ZoneOffset.UTC));
            dto.setUpdatedAt(plot.getUpdatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
