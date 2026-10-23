package app.nadel.ledger.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import nadel.contract.model.Recalculation;
import nadel.contract.model.RecalculationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Previews and books recalculations ({@code POST /recalculations}, feature 012 §2.2).
 * <p>
 * <b>Temporary (feature 012, slice 1):</b> the contract declares the endpoint, but the planner (slice 2) and the
 * booking (slice 3) are not in code yet, so every request is answered 409 {@code NOTHING_TO_RECALCULATE} and nothing
 * is written. Slice 3 replaces this body with the booking described in the ledger README.
 */
@Service
class RecalculationService {

    /** @throws ApiProblemException 409 {@code NOTHING_TO_RECALCULATE} until the booking arrives (slice 3) */
    Recalculation run(RecalculationRequest request, boolean preview, AccessProfile actor) {
        throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.NOTHING_TO_RECALCULATE,
                "Nothing to recalculate.");
    }
}
