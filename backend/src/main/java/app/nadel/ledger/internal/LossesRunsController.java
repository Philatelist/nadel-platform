package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.LossesRunsApi;
import nadel.contract.model.LossesRunDetails;
import nadel.contract.model.LossesRunRequest;
import nadel.contract.model.LossesRunResult;
import nadel.contract.model.LossesRunSummary;
import nadel.contract.model.RunReversal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code losses-runs}: the history and the details need {@code CHARGES_VIEW}, a run (its preview
 * too) {@code CHARGES_RUN}, the reversal of a run {@code CHARGES_REVERSE}. The platform operator has no permissions and
 * gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class LossesRunsController implements LossesRunsApi {

    private final CurrentUser currentUser;
    private final LossesRuns lossesRuns;

    LossesRunsController(CurrentUser currentUser, LossesRuns lossesRuns) {
        this.currentUser = currentUser;
        this.lossesRuns = lossesRuns;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<List<LossesRunSummary>> listLossesRuns() {
        return ResponseEntity.ok(lossesRuns.list(currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<LossesRunDetails> getLossesRun(UUID id) {
        return ResponseEntity.ok(lossesRuns.details(id, currentUser.profile()));
    }

    /** The preview and the booking both need {@code CHARGES_RUN}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_RUN')")
    public ResponseEntity<LossesRunResult> runLosses(Boolean preview, LossesRunRequest lossesRunRequest) {
        return ResponseEntity.ok(lossesRuns.run(lossesRunRequest, Boolean.TRUE.equals(preview),
                currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_REVERSE')")
    public ResponseEntity<LossesRunDetails> reverseLossesRun(UUID id, RunReversal runReversal) {
        return ResponseEntity.ok(lossesRuns.reverse(id, runReversal, currentUser.profile()));
    }
}
