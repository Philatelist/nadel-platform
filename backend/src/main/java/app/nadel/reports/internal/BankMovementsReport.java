package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.plots.PlotCatalog;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.treasury.AccountBook;
import app.nadel.treasury.AccountBook.BankMonth;
import app.nadel.treasury.TreasuryReadModel;
import java.util.List;
import java.util.Set;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Bank movements («Рух коштів по банку», {@code bank-movements}, feature 018 §2.12): the bank journal of a range of
 * days as the cash book shows the cash ({@link AccountBookSheet}), and the sheet {@code months}: every calendar month
 * the period touches, oldest first, with its reconciliation status ({@code reconciliation}, catalog
 * {@code cashbook.bank.status}; a month never reconciled is {@code NOT_RECONCILED}). Needs {@code CASHBOOK_VIEW} only;
 * it holds the counterparties of bank expenses, so {@code personalData} is true. The filter and the opening balance
 * rule are those of the cash book, for the bank account.
 */
@Component
class BankMovementsReport implements ReportDefinition {

    static final String MONTHS = "months";

    private final TreasuryReadModel treasury;
    private final PlotCatalog plots;

    BankMovementsReport(TreasuryReadModel treasury, PlotCatalog plots) {
        this.treasury = treasury;
        this.plots = plots;
    }

    @Override
    public ReportCode code() {
        return ReportCode.BANK_MOVEMENTS;
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
        return CashBookReport.FILTERS;
    }

    /** Defence in depth: the runner has already refused a caller without {@code CASHBOOK_VIEW} (403). */
    @Override
    @PreAuthorize("hasAuthority('PERM_CASHBOOK_VIEW')")
    public ReportResult load(ReportFilters filters, ReportContext context) {
        AccountBook book = treasury.bankMovements(filters.from(), filters.to(), context.rowLimit());
        List<Column> columns = List.of(Column.of("month", ReportColumnType.MONTH),
                Column.codes("reconciliation", "cashbook.bank.status"));
        List<Row> months = book.months().stream()
                .map(month -> Row.detail(List.of(Cell.of(month.month()), Cell.of(status(month))))).toList();
        return new ReportResult(List.of(AccountBookSheet.sheet(book, plots),
                new Sheet(MONTHS, columns, months, List.of())), List.of(),
                AccountBookSheet.categories(book, treasury.categories()));
    }

    private static String status(BankMonth month) {
        return month.status().name();
    }
}
