package app.nadel.reports.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.plots.PlotStatus;
import app.nadel.plots.PlotStatuses;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Plot balances ({@code plot-balances}, functional spec 2.4, feature 013 §3.2): the debt of every active plot on a day,
 * without names. Filters: as of (default today, Kyiv), charge types (default all), street, statuses.
 * <p>
 * Sheet {@code main}, one row per active plot that is not common land ({@code PlotCatalog.findActive}, natural order of
 * the number): {@code number}, {@code street}, {@code owners} (the number of current owners, never their names), one
 * debt column per charge type ({@link DebtColumns}), {@code total}, {@code oldestUnpaid}, {@code daysOverdue} and
 * {@code status}; the totals {@code all} sum the money columns of the rows listed. The debts are
 * {@code LedgerReadModel.debtsAsOf} on the day for the chosen types.
 * <p>
 * The status always counts every charge type. As of today it is the status on the map ({@code PlotStatuses.current},
 * the {@code plot_status} cache); for a past day it is worked out from the ledger on that day with today's thresholds
 * ({@code PlotStatuses.classify}) and the header note {@code PAST_DATE_STATUS} says so. With a charge type filter that
 * leaves out some type the note {@code STATUS_ALL_CHARGE_TYPES} says that the status still counts every type. The
 * status filter applies after the status is worked out. The note {@code OLDEST_UNPAID_IS_OLDEST_CHARGE} explains the
 * oldest unpaid date until payments exist.
 */
@Component
class PlotBalancesReport implements ReportDefinition {

    static final String MAIN = "main";

    private static final List<ReportFilterDefinition> FILTERS = List.of(new AsOfFilter(), new ChargeTypesFilter(),
            new StreetFilter(), new StatusesFilter());

    private final PlotCatalog plots;
    private final PlotOwners owners;
    private final PlotStatuses statuses;
    private final LedgerReadModel ledger;

    PlotBalancesReport(PlotCatalog plots, PlotOwners owners, PlotStatuses statuses, LedgerReadModel ledger) {
        this.plots = plots;
        this.owners = owners;
        this.statuses = statuses;
        this.ledger = ledger;
    }

    @Override
    public ReportCode code() {
        return ReportCode.PLOT_BALANCES;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    /**
     * One active plot on the day: the plot, its number of current owners, its debts of the chosen types and its status
     * over every type (null only if the status cache has no row for it).
     */
    record Balance(PlotFacts plot, int owners, PlotDebts debts, @Nullable PlotStatus status) {
    }

    /**
     * The balances of every active plot that is not common land on {@code asOf}, natural order of the number, before
     * the status filter: the rows of this report and of the template row set {@code plots} ({@link TemplateValues}).
     *
     * @param chargeTypeIds the types the debts count; empty means every type (the status always counts every type)
     * @param typesLeftOut  true when {@code chargeTypeIds} leaves out some type of the cooperative
     */
    List<Balance> balances(LocalDate asOf, List<UUID> chargeTypeIds, boolean typesLeftOut, @Nullable String street,
            ReportContext context) {
        boolean today = asOf.equals(context.today());
        List<PlotFacts> active = plots.findActive(street);
        List<UUID> ids = active.stream().map(PlotFacts::id).toList();
        Map<UUID, PlotDebts> debts = ledger.debtsAsOf(asOf, new LinkedHashSet<>(chargeTypeIds), ids);
        Map<UUID, PlotStatus> status = today ? statuses.current(ids)
                : statuses.classify(statusDebts(typesLeftOut ? ledger.debtsAsOf(asOf, Set.of(), ids) : debts),
                        asOf);
        Map<UUID, Integer> ownerCounts = owners.ownerCounts(ids);
        return active.stream().map(plot -> new Balance(plot, ownerCounts.getOrDefault(plot.id(), 0),
                debts.getOrDefault(plot.id(), PlotDebts.none(plot.id())), status.get(plot.id()))).toList();
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        LocalDate asOf = filters.asOf();
        boolean today = asOf.equals(context.today());
        boolean typesLeftOut = DebtColumns.someTypesLeftOut(filters, context);
        List<Balance> balances = balances(asOf, filters.chargeTypeIds(), typesLeftOut, filters.street(), context);
        List<ChargeTypeFacts> types = DebtColumns.types(filters, context,
                balances.stream().map(Balance::debts).toList());

        List<Column> columns = new ArrayList<>();
        columns.add(Column.of("number", ReportColumnType.TEXT));
        columns.add(Column.of("street", ReportColumnType.TEXT));
        columns.add(Column.of("owners", ReportColumnType.INTEGER));
        columns.addAll(DebtColumns.columns(types));
        columns.add(Column.of("total", ReportColumnType.MONEY));
        columns.add(Column.of("oldestUnpaid", ReportColumnType.DATE));
        columns.add(Column.of("daysOverdue", ReportColumnType.INTEGER));
        columns.add(Column.codes("status", "reports.filters.options.statuses"));

        List<Row> rows = new ArrayList<>();
        for (Balance balance : balances) {
            PlotStatus plotStatus = balance.status();
            if (!filters.statuses().isEmpty() && !filters.statuses().contains(plotStatus)) {
                continue;
            }
            PlotFacts plot = balance.plot();
            PlotDebts debt = balance.debts();
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(plot.number()));
            cells.add(Cell.of(plot.street()));
            cells.add(Cell.of(balance.owners()));
            cells.addAll(DebtColumns.cells(types, debt));
            cells.add(Cell.of(debt.total()));
            cells.add(Cell.of(debt.oldestUnpaid()));
            cells.add(Cell.of(debt.daysOverdue()));
            cells.add(Cell.of(plotStatus == null ? null : plotStatus.name()));
            rows.add(Row.detail(cells));
        }

        List<Note> notes = new ArrayList<>();
        if (!today) {
            notes.add(new Note(ReportNoteCode.PAST_DATE_STATUS, Map.of()));
        }
        if (typesLeftOut) {
            notes.add(new Note(ReportNoteCode.STATUS_ALL_CHARGE_TYPES, Map.of()));
        }
        notes.add(new Note(ReportNoteCode.OLDEST_UNPAID_IS_OLDEST_CHARGE, Map.of()));
        return new ReportResult(List.of(new Sheet(MAIN, columns, rows,
                List.of(new Total("all", DebtColumns.totals(columns, rows))))), notes);
    }

    @Override
    public ReportFilters.Period filenameRange(ReportFilters filters, ReportContext context) {
        return new ReportFilters.Period(filters.asOf(), filters.asOf());
    }

    private static Map<UUID, PlotStatuses.Debt> statusDebts(Map<UUID, PlotDebts> debts) {
        Map<UUID, PlotStatuses.Debt> result = new LinkedHashMap<>();
        debts.forEach((id, debt) -> result.put(id, new PlotStatuses.Debt(debt.total(), debt.oldestUnpaid())));
        return result;
    }
}
