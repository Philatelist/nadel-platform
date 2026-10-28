package app.nadel.reports.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthGrouping;
import app.nadel.ledger.MonthTypeTotals;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Charges by month ({@code charges-by-month}, functional spec 2.3, feature 013 §3.1, feature 015 §6): what was charged
 * per billing month and charge type, split into regular charges, opening balances, reversals and adjustments, with the
 * effective total (money rules 5.4), and what is paid on those charges as of the report's day (today, Kyiv): column
 * {@code paid}, the net allocations to the charges of the row whose value date is on or before today. Filters: billing
 * months (default the previous month), charge types (default all), street.
 * <p>
 * Sheet {@code byBillingMonth}: one row per billing month and charge type that has charges, with the month's status,
 * the charge types in their configured order; a subtotal row after each month and the totals of the range. Its
 * effective total per month is the billing months screen's total cent for cent ({@link MonthGrouping#BILLING_MONTH}
 * uses the screen's predicate). Sheet {@code byServicePeriod}: the same charges grouped by the month of their first
 * service day, without status, so an adjustment booked in March for January shows under January; the header note
 * {@code SERVICE_PERIOD_SHEET} explains the difference. Plots are counted per month and type (a subtotal and the totals
 * leave the plot count empty: a plot can have several types). Every amount is a {@code Money} sum of the rows listed.
 */
@Component
class ChargesByMonthReport implements ReportDefinition {

    static final String BY_BILLING_MONTH = "byBillingMonth";
    static final String BY_SERVICE_PERIOD = "byServicePeriod";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new ChargeTypesFilter(),
            new StreetFilter());

    /** The amount columns after the plot count, in order. */
    private static final List<Function<MonthTypeTotals, Money>> AMOUNTS = List.of(MonthTypeTotals::regular,
            MonthTypeTotals::opening, MonthTypeTotals::reversals, MonthTypeTotals::adjustments,
            MonthTypeTotals::effective, MonthTypeTotals::paid);

    private final LedgerReadModel ledger;
    private final PlotCatalog plots;

    ChargesByMonthReport(LedgerReadModel ledger, PlotCatalog plots) {
        this.ledger = ledger;
        this.plots = plots;
    }

    @Override
    public ReportCode code() {
        return ReportCode.CHARGES_BY_MONTH;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        Set<UUID> types = new LinkedHashSet<>(filters.chargeTypeIds());
        Set<UUID> plotIds = filters.street() == null ? null : Set.copyOf(plots.idsOnStreet(filters.street()));
        Map<UUID, Integer> order = new HashMap<>();
        List<ChargeTypeFacts> configured = context.chargeTypes();
        for (int index = 0; index < configured.size(); index++) {
            order.put(configured.get(index).id(), index);
        }
        List<MonthTypeTotals> byBillingMonth = ledger.monthTotals(filters.fromMonth(), filters.toMonth(), types,
                plotIds, MonthGrouping.BILLING_MONTH, context.today());
        List<MonthTypeTotals> byServiceMonth = ledger.monthTotals(filters.fromMonth(), filters.toMonth(), types,
                plotIds, MonthGrouping.SERVICE_MONTH, context.today());
        return new ReportResult(List.of(sheet(BY_BILLING_MONTH, true, byBillingMonth, order),
                sheet(BY_SERVICE_PERIOD, false, byServiceMonth, order)),
                List.of(new Note(ReportNoteCode.SERVICE_PERIOD_SHEET, Map.of())));
    }

    private static Sheet sheet(String key, boolean withStatus, List<MonthTypeTotals> totals,
            Map<UUID, Integer> order) {
        List<Column> columns = new ArrayList<>();
        columns.add(Column.of("month", ReportColumnType.MONTH));
        if (withStatus) {
            columns.add(Column.codes("status", "billing.status"));
        }
        columns.add(Column.of("chargeType", ReportColumnType.CHARGE_TYPE));
        columns.add(Column.of("plots", ReportColumnType.INTEGER));
        for (String amount : List.of("regular", "opening", "reversals", "adjustments", "effective", "paid")) {
            columns.add(Column.of(amount, ReportColumnType.MONEY));
        }
        Map<YearMonth, List<MonthTypeTotals>> byMonth = new LinkedHashMap<>();
        totals.stream()
                .sorted(Comparator.comparing(MonthTypeTotals::month)
                        .thenComparing(row -> order.getOrDefault(row.chargeTypeId(), Integer.MAX_VALUE)))
                .forEach(row -> byMonth.computeIfAbsent(row.month(), month -> new ArrayList<>()).add(row));
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<YearMonth, List<MonthTypeTotals>> month : byMonth.entrySet()) {
            List<MonthTypeTotals> ofMonth = month.getValue();
            for (MonthTypeTotals row : ofMonth) {
                List<Cell> cells = new ArrayList<>();
                cells.add(Cell.of(row.month()));
                if (withStatus) {
                    cells.add(status(row));
                }
                cells.add(Cell.of(row.chargeTypeId()));
                cells.add(Cell.of(row.plotCount()));
                AMOUNTS.forEach(amount -> cells.add(Cell.of(amount.apply(row))));
                rows.add(Row.detail(cells));
            }
            MonthTypeTotals first = ofMonth.getFirst();
            rows.add(Row.subtotal(sums(Cell.of(month.getKey()),
                    withStatus ? status(first) : null, ofMonth)));
        }
        return new Sheet(key, columns, rows, List.of(new Total("all", sums(Cell.EMPTY,
                withStatus ? Cell.EMPTY : null, totals))));
    }

    /** The status of the billing month (never null when grouped by billing month). */
    private static Cell status(MonthTypeTotals row) {
        return Cell.of(Objects.requireNonNull(row.status(), "status").name());
    }

    /** A subtotal or totals line: the month and status as given, no type and no plot count, the amounts summed. */
    private static List<Cell> sums(Cell month, @Nullable Cell status, List<MonthTypeTotals> rows) {
        List<Cell> cells = new ArrayList<>();
        cells.add(month);
        if (status != null) {
            cells.add(status);
        }
        cells.add(Cell.EMPTY);
        cells.add(Cell.EMPTY);
        AMOUNTS.forEach(amount -> cells.add(Cell.of(rows.stream().map(amount).reduce(Money.ZERO, Money::plus))));
        return cells;
    }
}
