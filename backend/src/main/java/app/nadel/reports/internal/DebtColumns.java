package app.nadel.reports.internal;

import app.nadel.ledger.PlotDebts;
import app.nadel.money.Money;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportColumnType;

/**
 * The columns of debt per charge type that plot balances and debtors share, and their totals line.
 * <p>
 * One {@code MONEY} column per charge type, in the configured order, keyed {@code debt.<chargeTypeId>} with the
 * column's {@code chargeTypeId} set (as {@code ReportColumn} allows). Its header is the charge type's name, not a bundle
 * key: the file writes the name the runner resolves ({@code chargeTypes.defaultNames.<CODE>} for a seeded type that was
 * not renamed, the stored name otherwise), the web its {@code chargeTypeName}. With a charge type filter the columns are
 * the chosen types; without one every type that is not archived, plus an archived type that some plot still has a
 * counted charge of, so no debt is left without its column. A plot without a counted charge of a type shows
 * {@code 0.00} in its column.
 */
final class DebtColumns {

    static final String KEY_PREFIX = "debt.";

    private DebtColumns() {
    }

    /** The charge types that get a column, in their configured order. */
    static List<ChargeTypeFacts> types(ReportFilters filters, ReportContext context, Collection<PlotDebts> debts) {
        Set<UUID> chosen = new HashSet<>(filters.chargeTypeIds());
        if (!chosen.isEmpty()) {
            return context.chargeTypes().stream().filter(type -> chosen.contains(type.id())).toList();
        }
        Set<UUID> withDebt = new HashSet<>();
        debts.forEach(debt -> withDebt.addAll(debt.byChargeType().keySet()));
        return context.chargeTypes().stream().filter(type -> !type.archived() || withDebt.contains(type.id()))
                .toList();
    }

    /** True when the charge type filter leaves out some charge type of the cooperative. */
    static boolean someTypesLeftOut(ReportFilters filters, ReportContext context) {
        Set<UUID> chosen = new HashSet<>(filters.chargeTypeIds());
        return !chosen.isEmpty() && context.chargeTypes().stream().anyMatch(type -> !chosen.contains(type.id()));
    }

    static List<Column> columns(List<ChargeTypeFacts> types) {
        return types.stream().map(type -> new Column(KEY_PREFIX + type.id(), ReportColumnType.MONEY, type.id(), null))
                .toList();
    }

    /** The plot's debt of each type, zero for a type without counted charges. */
    static List<Cell> cells(List<ChargeTypeFacts> types, PlotDebts debts) {
        return types.stream().map(type -> Cell.of(debts.byChargeType().getOrDefault(type.id(), Money.ZERO)))
                .toList();
    }

    /**
     * The totals line of a sheet: every {@code MONEY} column is the sum of the listed rows ({@link Money#plus}, no
     * rounding of its own), every other column is empty.
     */
    static List<Cell> totals(List<Column> columns, List<Row> rows) {
        List<Cell> cells = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).type() != ReportColumnType.MONEY) {
                cells.add(Cell.EMPTY);
                continue;
            }
            Money sum = Money.ZERO;
            for (Row row : rows) {
                if (row.cells().get(index).value() instanceof Money amount) {
                    sum = sum.plus(amount);
                }
            }
            cells.add(Cell.of(sum));
        }
        return cells;
    }
}
