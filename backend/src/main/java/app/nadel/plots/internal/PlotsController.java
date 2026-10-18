package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.CurrentUser;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import nadel.contract.api.PlotsApi;
import nadel.contract.model.PlotFeatureCollection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PlotsController implements PlotsApi {

    private final CurrentUser currentUser;
    private final PlotQueries queries;

    PlotsController(CurrentUser currentUser, PlotQueries queries) {
        this.currentUser = currentUser;
        this.queries = queries;
    }

    /** Every member of a cooperative may read the map; the platform operator has no cooperative data. */
    @Override
    public ResponseEntity<PlotFeatureCollection> getPlotMap() {
        AccessProfile viewer = currentUser.profile();
        requireCooperativeMember(viewer);
        return ResponseEntity.ok(queries.map(viewer));
    }

    private static void requireCooperativeMember(AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
    }
}
