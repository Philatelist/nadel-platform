package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.treasury.CategoryRef;
import app.nadel.treasury.ExpenseLine;
import app.nadel.treasury.TreasuryReadModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportPeriodBasis;
import nadel.contract.model.ReportRowKind;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Expenses («Витрати», {@code expenses}, feature 018 §2.12): the original expenses of a range of days (a reversed one
 * listed once and marked), oldest first. Needs {@code CASHBOOK_VIEW} only; it holds counterparties, so
 * {@code personalData} is true. Filters: the period (days, basis {@code MOVEMENT_DATES}, default the previous month),
 * the account ({@code account}, default both) and the expense categories ({@code expenseCategoryIds}, default all; an
 * id that is not an expense category of the cooperative is 400 {@code REPORT_FILTER_INVALID} with {@code NOT_FOUND}
 * on {@code expenseCategoryIds[i]}, checked here because the categories are the treasury's). No opening balance is
 * needed: the list shows entries, not balances.
 * <p>
 * Sheet {@code main}, one row per expense: {@code date}, {@code account} ({@code CODE},
 * {@code reports.filters.options.account}), {@code category}, {@code counterparty}, {@code document} (number),
 * {@code documentDate}, {@code amount}, {@code reversed}, {@code attachments} (the number of receipt files); each row
 * carries its {@code expenseId}, so the screen links the files. The totals line {@code all} sums the amounts of the
 * expenses that are not reversed (a reversed expense is listed but spent nothing).
 */
@Component
class ExpensesReport implements ReportDefinition {

    static final String MAIN = "main";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            new DateRangeFilter(ReportPeriodBasis.MOVEMENT_DATES), new AccountFilter(), new ExpenseCategoriesFilter());

    private static final List<Column> COLUMNS = List.of(
            Column.of("date", ReportColumnType.DATE),
            Column.codes("account", "reports.filters.options.account"),
            Column.of("category", ReportColumnType.EXPENSE_CATEGORY),
            Column.of("counterparty", ReportColumnType.TEXT),
            Column.of("document", ReportColumnType.TEXT),
            Column.of("documentDate", ReportColumnType.DATE),
            Column.of("amount", ReportColumnType.MONEY),
            Column.of("reversed", ReportColumnType.BOOLEAN),
            Column.of("attachments", ReportColumnType.INTEGER));

    private final TreasuryReadModel treasury;

    ExpensesReport(TreasuryReadModel treasury) {
        this.treasury = treasury;
    }

    @Override
    public ReportCode code() {
        return ReportCode.EXPENSES;
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
        List<CategoryRef> categories = treasury.categories();
        checkCategories(filters.expenseCategoryIds(), categories);
        List<ExpenseLine> expenses = treasury.expenses(filters.from(), filters.to(), filters.account(),
                filters.expenseCategoryIds(), context.rowLimit());
        List<Row> rows = new ArrayList<>();
        Money total = Money.ZERO;
        for (ExpenseLine expense : expenses) {
            rows.add(new Row(ReportRowKind.DETAIL, 0, List.of(Cell.of(expense.date()),
                    Cell.of(expense.account().name()), Cell.of(expense.categoryId()), Cell.of(expense.counterparty()),
                    Cell.of(expense.documentNumber()), Cell.of(expense.documentDate()), Cell.of(expense.amount()),
                    Cell.of(expense.reversed()), Cell.of(expense.attachments())), expense.expenseId()));
            if (!expense.reversed()) {
                total = total.plus(expense.amount());
            }
        }
        List<Cell> totals = new ArrayList<>(COLUMNS.stream().map(column -> Cell.EMPTY).toList());
        totals.set(6, Cell.of(total));
        Set<UUID> used = expenses.stream().map(ExpenseLine::categoryId).collect(Collectors.toSet());
        used.addAll(filters.expenseCategoryIds());
        return new ReportResult(List.of(new Sheet(MAIN, COLUMNS, rows, List.of(new Total("all", totals)))),
                List.of(), categories.stream().filter(category -> used.contains(category.id())).toList());
    }

    /** 400 {@code REPORT_FILTER_INVALID} for every chosen id that is not an expense category of the cooperative. */
    private static void checkCategories(List<UUID> chosen, List<CategoryRef> categories) {
        Set<UUID> known = categories.stream().filter(category -> category.kind() == CategoryRef.Kind.EXPENSE)
                .map(CategoryRef::id).collect(Collectors.toSet());
        List<FieldProblem> errors = new ArrayList<>();
        for (int index = 0; index < chosen.size(); index++) {
            if (!known.contains(chosen.get(index))) {
                errors.add(new FieldProblem(ReportFilterParam.EXPENSE_CATEGORY_IDS.getValue() + "[" + index + "]",
                        "NOT_FOUND"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.REPORT_FILTER_INVALID,
                    "The filters do not fit the report.", errors);
        }
    }
}
