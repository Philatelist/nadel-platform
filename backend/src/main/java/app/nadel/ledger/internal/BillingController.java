package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.time.YearMonth;
import java.util.List;
import nadel.contract.api.BillingApi;
import nadel.contract.model.BillingMonthDetails;
import nadel.contract.model.BillingMonthSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code billing}: the months need {@code CHARGES_VIEW}, closing one {@code PERIODS_CLOSE}. The
 * platform operator has no permissions and gets 403. The month in the path is {@code YYYY-MM} (checked by the
 * contract's pattern, 400 otherwise).
 */
@RestController
@RequestMapping("/api/v1")
class BillingController implements BillingApi {

    private final CurrentUser currentUser;
    private final BillingMonthQueries queries;
    private final MonthClosing closing;

    BillingController(CurrentUser currentUser, BillingMonthQueries queries, MonthClosing closing) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.closing = closing;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<List<BillingMonthSummary>> listBillingMonths() {
        return ResponseEntity.ok(queries.list());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<BillingMonthDetails> getBillingMonth(String month) {
        return ResponseEntity.ok(queries.details(YearMonth.parse(month)));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_PERIODS_CLOSE')")
    public ResponseEntity<BillingMonthDetails> closeBillingMonth(String month) {
        return ResponseEntity.ok(closing.close(YearMonth.parse(month), currentUser.profile()));
    }
}
