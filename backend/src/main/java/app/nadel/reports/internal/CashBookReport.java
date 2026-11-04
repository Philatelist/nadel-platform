package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.plots.PlotCatalog;
import app.nadel.treasury.AccountBook;
import app.nadel.treasury.TreasuryReadModel;
import java.util.List;
import java.util.Set;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportPeriodBasis;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * The cash book («Касова книга», {@code cash-book}, feature 018 §2.12): the cash journal of a range of days, per day
 * the opening balance, each line and the closing balance ({@link AccountBookSheet}); every day's closing balance is the
 * cash balance «Каса і банк» shows for that day. Needs {@code CASHBOOK_VIEW} only (not {@code REPORTS_VIEW}: an audit
 * committee role may hold just that). It holds the counterparties of expenses, so {@code personalData} is true.
 * Filter: the period (days, basis {@code MOVEMENT_DATES}, default the previous month). The cash opening balance is
 * required (409 {@code OPENING_BALANCE_MISSING}) and the period starts on or after its date (400
 * {@code BEFORE_OPENING_BALANCE} on {@code from}).
 */
@Component
class CashBookReport implements ReportDefinition {

    static final List<ReportFilterDefinition> FILTERS = List.of(
            new DateRangeFilter(ReportPeriodBasis.MOVEMENT_DATES));

    private final TreasuryReadModel treasury;
    private final PlotCatalog plots;

    CashBookReport(TreasuryReadModel treasury, PlotCatalog plots) {
        this.treasury = treasury;
        this.plots = plots;
    }

    @Override
    public ReportCode code() {
        return ReportCode.CASH_BOOK;
    }

    @Override
    public Set<Permission> requiredPermissions() {
        return Set.of(Permission.CASHBOOK_VIEW);
    }

    @Override
    public boolean personalData() {
        return true;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    /** Defence in depth: the runner has already refused a caller without {@code CASHBOOK_VIEW} (403). */
    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ReportResult load(ReportFilters filters, ReportContext context) {
        AccountBook book = treasury.cashBook(filters.from(), filters.to(), context.rowLimit());
        return new ReportResult(List.of(AccountBookSheet.sheet(book, plots)), List.of(),
                AccountBookSheet.categories(book, treasury.categories()));
    }
}
