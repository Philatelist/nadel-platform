package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportPeriodBasis;

/**
 * A report definition for the framework's unit tests: one sheet of {@code rows} rows, a text and an amount of
 * {@code 0.01} times the row number, and the total of the amounts; month range and street filters. Its sheet and
 * column keys are those of {@code charges-by-month}, so the bundles name them.
 */
final class FakeReport implements ReportDefinition {

    private final ReportCode code;
    private final Set<Permission> permissions;
    private final int rows;

    FakeReport(ReportCode code, Set<Permission> permissions, int rows) {
        this.code = code;
        this.permissions = permissions;
        this.rows = rows;
    }

    @Override
    public ReportCode code() {
        return code;
    }

    @Override
    public Set<Permission> requiredPermissions() {
        return permissions;
    }

    @Override
    public boolean personalData() {
        return permissions.contains(Permission.MEMBERS_VIEW);
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return List.of(MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new StreetFilter());
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        List<Row> list = new ArrayList<>();
        Money total = Money.ZERO;
        for (int index = 1; index <= rows; index++) {
            Money amount = Money.of(BigDecimal.valueOf(index, 2));
            total = total.plus(amount);
            list.add(Row.detail(List.of(Cell.of(Integer.toString(index)), Cell.of(amount))));
        }
        return new ReportResult(List.of(new Sheet("byBillingMonth", List.of(
                Column.of("chargeType", ReportColumnType.TEXT), Column.of("effective", ReportColumnType.MONEY)), list, List.of(new Total("all",
                List.of(Cell.EMPTY, Cell.of(total)))))), List.of());
    }
}
