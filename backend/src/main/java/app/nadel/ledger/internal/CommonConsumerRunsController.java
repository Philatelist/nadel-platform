package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.CommonConsumerRunsApi;
import nadel.contract.model.CommonConsumerBilling;
import nadel.contract.model.CommonConsumerBillingUpdate;
import nadel.contract.model.CommonConsumerRunDetails;
import nadel.contract.model.CommonConsumerRunRequest;
import nadel.contract.model.CommonConsumerRunResult;
import nadel.contract.model.CommonConsumerRunSummary;
import nadel.contract.model.RunReversal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code common-consumer-runs}: the history and the details need {@code CHARGES_VIEW}, a run (its
 * preview too) {@code CHARGES_RUN}, the reversal of a run {@code CHARGES_REVERSE}, the settings of a consumer (read and
 * change) {@code SETTINGS_MANAGE}. The platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class CommonConsumerRunsController implements CommonConsumerRunsApi {

    private final CurrentUser currentUser;
    private final CommonConsumerRuns runs;
    private final CommonConsumerBillings billings;

    CommonConsumerRunsController(CurrentUser currentUser, CommonConsumerRuns runs, CommonConsumerBillings billings) {
        this.currentUser = currentUser;
        this.runs = runs;
        this.billings = billings;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<List<CommonConsumerRunSummary>> listCommonConsumerRuns() {
        return ResponseEntity.ok(runs.list(currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<CommonConsumerRunDetails> getCommonConsumerRun(UUID id) {
        return ResponseEntity.ok(runs.details(id, currentUser.profile()));
    }

    /** The preview and the booking both need {@code CHARGES_RUN}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_RUN')")
    public ResponseEntity<CommonConsumerRunResult> runCommonConsumers(Boolean preview,
            CommonConsumerRunRequest commonConsumerRunRequest) {
        return ResponseEntity.ok(runs.run(commonConsumerRunRequest, Boolean.TRUE.equals(preview),
                currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_REVERSE')")
    public ResponseEntity<CommonConsumerRunDetails> reverseCommonConsumerRun(UUID id, RunReversal runReversal) {
        return ResponseEntity.ok(runs.reverse(id, runReversal, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<CommonConsumerBilling> getCommonConsumerBilling(UUID meterId) {
        return ResponseEntity.ok(billings.get(meterId));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<CommonConsumerBilling> updateCommonConsumerBilling(UUID meterId,
            CommonConsumerBillingUpdate commonConsumerBillingUpdate) {
        return ResponseEntity.ok(billings.update(meterId, commonConsumerBillingUpdate, currentUser.profile()));
    }
}
