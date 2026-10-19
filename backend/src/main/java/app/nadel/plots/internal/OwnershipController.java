package app.nadel.plots.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import java.util.UUID;
import nadel.contract.api.OwnershipsApi;
import nadel.contract.model.OwnershipChange;
import nadel.contract.model.PlotOwnerships;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OwnershipController implements OwnershipsApi {

    private final CurrentUser currentUser;
    private final OwnershipQueries queries;
    private final OwnershipCommands commands;

    OwnershipController(CurrentUser currentUser, OwnershipQueries queries, OwnershipCommands commands) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.commands = commands;
    }

    /**
     * Owners and history of a plot, for callers with {@code PLOTS_VIEW} (the platform operator has no such
     * permission). A plot that does not exist or belongs to another cooperative (RLS) is 404.
     */
    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW')")
    public ResponseEntity<PlotOwnerships> listPlotOwnerships(UUID id) {
        return ResponseEntity.ok(queries.ofPlot(id).orElseThrow(ApiProblemException::notFound));
    }

    /**
     * Changes the owners of a plot from a date. The platform operator and everyone without both permissions get 403; a
     * plot that does not exist or belongs to another cooperative (RLS) is 404.
     */
    @Override
    @PreAuthorize("hasAuthority('PERM_PLOTS_VIEW') and hasAuthority('PERM_OWNERSHIP_EDIT')")
    public ResponseEntity<PlotOwnerships> createOwnershipChange(UUID id, OwnershipChange ownershipChange) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commands.change(id, ownershipChange, currentUser.profile()));
    }
}
