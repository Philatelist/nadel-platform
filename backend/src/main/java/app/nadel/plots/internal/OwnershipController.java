package app.nadel.plots.internal;

import app.nadel.problem.ApiProblemException;
import java.util.UUID;
import nadel.contract.api.OwnershipsApi;
import nadel.contract.model.PlotOwnerships;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OwnershipController implements OwnershipsApi {

    private final OwnershipQueries queries;

    OwnershipController(OwnershipQueries queries) {
        this.queries = queries;
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
}
