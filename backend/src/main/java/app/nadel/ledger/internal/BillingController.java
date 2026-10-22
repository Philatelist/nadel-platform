package app.nadel.ledger.internal;

import app.nadel.identity.CurrentUser;
import java.time.YearMonth;
import java.util.List;
import nadel.contract.api.BillingApi;
import nadel.contract.model.BillingMonthDetails;
import nadel.contract.model.BillingMonthSummary;
import nadel.contract.model.MonthRunRequest;
import nadel.contract.model.MonthRunResult;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code billing}: the months need {@code CHARGES_VIEW}, closing one {@code PERIODS_CLOSE}, the
 * month run (its preview too) {@code CHARGES_RUN}. The
 * platform operator has no permissions and gets 403. The month in the path is {@code YYYY-MM} (checked by the
 * contract's pattern, 400 otherwise).
 */
@RestController
@RequestMapping("/api/v1")
class BillingController implements BillingApi {

    private final CurrentUser currentUser;
    private final BillingMonthQueries queries;
    private final MonthClosing closing;
    private final MonthRun monthRun;

    BillingController(CurrentUser currentUser, BillingMonthQueries queries, MonthClosing closing, MonthRun monthRun) {
        this.currentUser = currentUser;
        this.queries = queries;
        this.closing = closing;
        this.monthRun = monthRun;
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

    /** The preview and the booking both need {@code CHARGES_RUN}. */
    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_RUN')")
    public ResponseEntity<MonthRunResult> runBillingMonth(String month, Boolean preview,
            @Nullable MonthRunRequest monthRunRequest) {
        String digest = monthRunRequest == null ? null : monthRunRequest.getDigest();
        return ResponseEntity.ok(monthRun.run(YearMonth.parse(month), Boolean.TRUE.equals(preview), digest,
                currentUser.profile()));
    }
}
