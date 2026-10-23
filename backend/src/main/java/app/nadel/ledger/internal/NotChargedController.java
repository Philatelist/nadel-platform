package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.NotChargedApi;
import nadel.contract.model.NotChargedItem;
import nadel.contract.model.NotChargedRetryResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code not-charged}: the queue needs {@code CHARGES_VIEW}, a retry {@code CHARGES_RUN}. The
 * platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class NotChargedController implements NotChargedApi {

    private final CurrentUser currentUser;
    private final NotChargedQueue queue;

    NotChargedController(CurrentUser currentUser, NotChargedQueue queue) {
        this.currentUser = currentUser;
        this.queue = queue;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<List<NotChargedItem>> listNotCharged() {
        return ResponseEntity.ok(queue.list());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_RUN')")
    public ResponseEntity<NotChargedRetryResult> retryNotCharged(UUID id) {
        return ResponseEntity.ok(queue.retry(id, currentUser.profile()));
    }
}
