package app.nadel.plots.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import org.springframework.http.HttpStatus;

/** Guard shared by the endpoints that are open to every member of a cooperative but not to the platform operator. */
final class CooperativeCallers {

    private CooperativeCallers() {
    }

    /** The platform operator has no cooperative data (ADR 0008): 403. */
    static void require(AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
    }
}
