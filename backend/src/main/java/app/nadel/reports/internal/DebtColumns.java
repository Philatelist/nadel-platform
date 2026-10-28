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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportColumnType;

/**
 * The columns per charge type that plot balances and debtors share, and their totals line.
 * <p>
 * One {@code MONEY} column per charge type and {@link Family} (plot balances: charged, paid and debt of each type side
 * by side; debtors: the debt only), the types in their configured order, keyed {@code <family>.<chargeTypeId>} with the
 * column's {@code chargeTypeId} set (as {@code ReportColumn} allows). Its header is not a key of the report: the file
 * writes {@code reports.chargeTypeColumns.<family>} with the charge type's name the runner resolves
 * ({@code chargeTypes.defaultNames.<CODE>} for a seeded type that was not renamed, the stored name otherwise), the web
 * the same text with its {@code chargeTypeName}. With a charge type filter the columns are the chosen types; without
 * one every type that is not archived, plus an archived type that some plot still has a counted charge of, so no amount
 * is left without its column. A plot without a counted charge of a type shows {@code 0.00} in its columns.
 */
final class DebtColumns {

    /** The prefix of the header texts of the columns per charge type: {@code reports.chargeTypeColumns.<family>}. */
    static final String HEADER_PREFIX = "reports.chargeTypeColumns.";

    /** What a column per charge type shows. */
    enum Family {

        /** The charged amount: charges, reversals and adjustments with their signs, opening balances included. */
        CHARGED("charged", PlotDebts::chargedByType),

        /** What payments pay on those charges as of the day. */
        PAID("paid", PlotDebts::paidByType),

        /** The debt: charged minus paid. */
        DEBT("debt", PlotDebts::byChargeType);

        private final String key;
        private final Function<PlotDebts, Map<UUID, Money>> amounts;

        Family(String key, Function<PlotDebts, Map<UUID, Money>> amounts) {
            this.key = key;
            this.amounts = amounts;
        }

        /** The first part of the column key and the last part of its header text key. */
        String key() {
            return key;
        }

        /** The family of a column key {@code <family>.<chargeTypeId>}. */
        static Family ofColumn(String columnKey) {
            String prefix = columnKey.substring(0, columnKey.indexOf('.'));
            for (Family family : values()) {
                if (family.key.equals(prefix)) {
                    return family;
                }
            }
            throw new IllegalArgumentException("Not a column per charge type: " + columnKey);
        }
    }

    private DebtColumns() {
    }

    /** The charge types that get columns, in their configured order. */
    static List<ChargeTypeFacts> types(ReportFilters filters, ReportContext context, Collection<PlotDebts> debts) {
        Set<UUID> chosen = new HashSet<>(filters.chargeTypeIds());
        if (!chosen.isEmpty()) {
            return context.chargeTypes().stream().filter(type -> chosen.contains(type.id())).toList();
        }
        Set<UUID> withCharges = new HashSet<>();
        debts.forEach(debt -> withCharges.addAll(debt.chargedByType().keySet()));
        return context.chargeTypes().stream().filter(type -> !type.archived() || withCharges.contains(type.id()))
                .toList();
    }

    /** True when the charge type filter leaves out some charge type of the cooperative. */
    static boolean someTypesLeftOut(ReportFilters filters, ReportContext context) {
        Set<UUID> chosen = new HashSet<>(filters.chargeTypeIds());
        return !chosen.isEmpty() && context.chargeTypes().stream().anyMatch(type -> !chosen.contains(type.id()));
    }

    /** Per type, one column per family in the order given. */
    static List<Column> columns(List<ChargeTypeFacts> types, List<Family> families) {
        List<Column> columns = new ArrayList<>();
        for (ChargeTypeFacts type : types) {
            for (Family family : families) {
                columns.add(new Column(family.key() + "." + type.id(), ReportColumnType.MONEY, type.id(), null));
            }
        }
        return columns;
    }

    /** The plot's amounts in the order of {@link #columns}, zero for a type without counted charges. */
    static List<Cell> cells(List<ChargeTypeFacts> types, List<Family> families, PlotDebts debts) {
        List<Cell> cells = new ArrayList<>();
        for (ChargeTypeFacts type : types) {
            for (Family family : families) {
                cells.add(Cell.of(family.amounts.apply(debts).getOrDefault(type.id(), Money.ZERO)));
            }
        }
        return cells;
    }

    /**
     * The totals line of a sheet: every {@code MONEY} column is the sum of the rows given ({@link Money#plus}, no
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
