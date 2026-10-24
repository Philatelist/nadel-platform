package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.util.UUID;
import nadel.contract.api.RecalculationsApi;
import nadel.contract.model.Recalculation;
import nadel.contract.model.RecalculationPage;
import nadel.contract.model.RecalculationRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code recalculations}: the list and a record need {@code CHARGES_VIEW}, a preview or a booking
 * {@code RECALCULATION_RUN}. The platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class RecalculationsController implements RecalculationsApi {

    private final CurrentUser currentUser;
    private final RecalculationQueries queries;
    private final RecalculationService service;

    RecalculationsController(CurrentUser currentUser, RecalculationQueries queries, RecalculationService service) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.service = service;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<RecalculationPage> listRecalculations(Integer page, Integer size) {
        return ResponseEntity.ok(queries.list(page, size));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<Recalculation> getRecalculation(UUID id) {
        return ResponseEntity.ok(queries.details(id));
    }

    /** The preview and the booking both need {@code RECALCULATION_RUN}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_RECALCULATION_RUN')")
    public ResponseEntity<Recalculation> runRecalculation(Boolean preview, RecalculationRequest recalculationRequest) {
        return ResponseEntity.ok(service.run(recalculationRequest, Boolean.TRUE.equals(preview),
                currentUser.profile()));
    }
}
