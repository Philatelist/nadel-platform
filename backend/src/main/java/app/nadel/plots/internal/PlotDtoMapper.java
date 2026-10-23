package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.money.Money;
import java.time.ZoneOffset;
import java.util.List;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotOwnerSummary;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.PlotSummary;

/**
 * Turns plots into contract objects, shaped by the viewer's {@link ViewerScope}. The only place that decides who sees
 * which fields.
 */
final class PlotDtoMapper {

    private PlotDtoMapper() {
    }

    /**
     * Whether the viewer sees the details of every plot, which also decides what the list may search by (cadastral
     * number) and whether archived plots and the owners column are listed. Stays {@code PLOTS_VIEW}: owning a plot
     * never widens a search.
     */
    static boolean canSeeAllDetails(AccessProfile viewer) {
        return viewer.can(Permission.PLOTS_VIEW);
    }

    /** The status derived from the row's debt, its owners and the request's threshold and today. */
    static PlotStatus statusOf(PlotRow row, PlotStatusPolicy.Basis basis) {
        Money debt = row.debt() == null ? Money.ZERO : Money.of(row.debt());
        return PlotStatus.fromValue(PlotStatusPolicy.statusOf(row.plot().getKind(), row.hasOwner(), debt,
                row.oldestDebtDate(), basis).name());
    }

    /**
     * @param owners the current owners of the plot, or null/empty when it has none; they are shown only to a viewer
     *               who may see the details of every plot
     */
    static PlotSummary toSummary(ViewerScope scope, PlotRow row, List<PlotOwnerSummary> owners) {
        Plot plot = row.plot();
        var dto = new PlotSummary();
        dto.setId(plot.getId());
        dto.setNumber(plot.getNumber());
        dto.setStreet(plot.getStreet());
        dto.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        if (scope.canSeeStatus(plot.getId())) {
            dto.setStatus(statusOf(row, scope.statusBasis()));
        }
        dto.setArchived(plot.getArchivedAt() != null);
        if (scope.canSeeDetails(plot.getId())) {
            dto.setAreaM2(plot.getAreaM2());
            dto.setCadastralNumber(plot.getCadastralNumber());
            dto.setHasBuilding(plot.hasBuilding());
        }
        // the generated model defaults to an empty list, which would be written as "owners": []
        boolean showOwners = scope.full() && owners != null && !owners.isEmpty();
        dto.setOwners(showOwners ? owners : null);
        return dto;
    }

    /** The card for the caller of a write endpoint, which all require {@code PLOTS_VIEW}. */
    static PlotDetails toDetails(AccessProfile actor, PlotRow row, PlotStatusPolicy.Basis basis) {
        return toDetails(canSeeAllDetails(actor) ? ViewerScope.full(basis) : ViewerScope.none(basis), row);
    }

    static PlotDetails toDetails(ViewerScope scope, PlotRow row) {
        Plot plot = row.plot();
        var dto = new PlotDetails();
        dto.setId(plot.getId());
        dto.setNumber(plot.getNumber());
        dto.setStreet(plot.getStreet());
        dto.setKind(nadel.contract.model.PlotKind.fromValue(plot.getKind().name()));
        if (scope.canSeeStatus(plot.getId())) {
            dto.setStatus(statusOf(row, scope.statusBasis()));
        }
        dto.setArchived(plot.getArchivedAt() != null);
        if (plot.getArchivedAt() != null) {
            dto.setArchivedAt(plot.getArchivedAt().atOffset(ZoneOffset.UTC));
        }
        if (scope.canSeeDetails(plot.getId())) {
            dto.setAreaM2(plot.getAreaM2());
            dto.setCadastralNumber(plot.getCadastralNumber());
            dto.setHasBuilding(plot.hasBuilding());
        }
        if (scope.canSeeNotes()) {
            dto.setNotes(plot.getNotes());
            dto.setCreatedAt(plot.getCreatedAt().atOffset(ZoneOffset.UTC));
            dto.setUpdatedAt(plot.getUpdatedAt().atOffset(ZoneOffset.UTC));
        }
        return dto;
    }
}
