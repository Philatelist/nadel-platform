package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.plots.PlotCatalog;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.PlotsApi;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotFeatureCollection;
import nadel.contract.model.PlotInput;
import nadel.contract.model.PlotPage;
import nadel.contract.model.PlotStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PlotsController implements PlotsApi {

    private final CurrentUser currentUser;
    private final PlotQueries queries;
    private final PlotCommands commands;
    private final PlotCatalog catalog;

    PlotsController(CurrentUser currentUser, PlotQueries queries, PlotCommands commands, PlotCatalog catalog) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
        this.catalog = catalog;
    }

    /**
     * The streets of the active plots (feature 017 §2.6: an announcement's streets): for whoever sees plots or sends
     * announcements. Street names only, no plot or owner data.
     */
    @Override
    @PreAuthorize("hasAnyAuthority('PERM_PLOTS_VIEW', 'PERM_ANNOUNCEMENTS_SEND')")
    public ResponseEntity<List<String>> listPlotStreets() {
        return ResponseEntity.ok(catalog.activeStreets());
    }

    /** Every member of a cooperative may read the map; the platform operator has no cooperative data. */
    @Override
    public ResponseEntity<PlotFeatureCollection> getPlotMap() {
        AccessProfile viewer = currentUser.profile();
        CooperativeCallers.require(viewer);
        return ResponseEntity.ok(queries.map(viewer));
    }

    /** Every member of a cooperative may list plots; what they see of each depends on {@code PLOTS_VIEW}. */
    @Override
    public ResponseEntity<PlotPage> listPlots(String q, nadel.contract.model.PlotKind kind, PlotStatus status,
            Boolean includeArchived, Integer page, Integer size) {
        AccessProfile viewer = currentUser.profile();
        CooperativeCallers.require(viewer);
        return ResponseEntity.ok(queries.list(viewer, q, kind, status, includeArchived, page, size));
    }

    /**
     * A plot that does not exist, belongs to another cooperative (RLS) or is archived while the caller may not see
     * its details is 404 in every case, so nothing is revealed.
     */
    @Override
    public ResponseEntity<PlotDetails> getPlot(UUID id) {
        AccessProfile viewer = currentUser.profile();
        CooperativeCallers.require(viewer);
        return ResponseEntity.ok(queries.card(id, viewer).orElseThrow(ApiProblemException::notFound));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")
    public ResponseEntity<PlotDetails> createPlot(PlotInput plotInput) {
        PlotDetails created = commands.create(plotInput, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/plots/" + created.getId())).body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")
    public ResponseEntity<PlotDetails> updatePlot(UUID id, PlotInput plotInput) {
        return ResponseEntity.ok(commands.update(id, plotInput, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")
    public ResponseEntity<PlotDetails> archivePlot(UUID id) {
        return ResponseEntity.ok(commands.archive(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")
    public ResponseEntity<PlotDetails> restorePlot(UUID id) {
        return ResponseEntity.ok(commands.restore(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_PLOTS_EDIT')")
    public ResponseEntity<Void> deletePlot(UUID id) {
        commands.delete(id, currentUser.profile());
        return ResponseEntity.noContent().build();
    }
}
