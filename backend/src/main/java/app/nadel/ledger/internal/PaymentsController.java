package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.time.LocalDate;
import java.util.UUID;
import nadel.contract.api.PaymentsApi;
import nadel.contract.model.PaymentDetails;
import nadel.contract.model.PaymentEntryRequest;
import nadel.contract.model.PaymentEntryResult;
import nadel.contract.model.PaymentPage;
import nadel.contract.model.PaymentSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code payments}: the list and a payment need {@code PAYMENTS_VIEW}, a preview or a save of a
 * payment {@code PAYMENTS_ENTER}. The platform operator has no permissions and gets 403.
 */
@RestController
@RequestMapping("/api/v1")
class PaymentsController implements PaymentsApi {

    private final CurrentUser currentUser;
    private final PaymentQueries queries;
    private final PaymentService service;

    PaymentsController(CurrentUser currentUser, PaymentQueries queries, PaymentService service) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.service = service;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PAYMENTS_VIEW')")
    public ResponseEntity<PaymentPage> listPayments(@Nullable LocalDate from, @Nullable LocalDate to,
            @Nullable PaymentSource source, @Nullable UUID plotId, @Nullable String month, Integer page,
            Integer size) {
        return ResponseEntity.ok(queries.list(from, to, source, plotId, month, page, size));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PAYMENTS_VIEW')")
    public ResponseEntity<PaymentDetails> getPayment(UUID id) {
        return ResponseEntity.ok(queries.details(id, currentUser.profile()));
    }

    /** The preview (200) and the save (201) both need {@code PAYMENTS_ENTER}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_PAYMENTS_ENTER')")
    public ResponseEntity<PaymentEntryResult> enterPayment(Boolean preview, PaymentEntryRequest request) {
        boolean isPreview = Boolean.TRUE.equals(preview);
        PaymentEntryResult result = service.enter(request, isPreview, currentUser.profile());
        return ResponseEntity.status(isPreview ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
