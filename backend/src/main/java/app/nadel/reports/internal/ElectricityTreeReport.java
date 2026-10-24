package app.nadel.reports.internal;

import app.nadel.ledger.ChargedRow;
import app.nadel.ledger.ChargedSource;
import app.nadel.ledger.CommonConsumerRunLineRow;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.LossesRunNodeRow;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterTreeLosses;
import app.nadel.meters.MeterTreeQuantity;
import app.nadel.meters.MeterTreeReportRow;
import app.nadel.meters.MeterTreeReportSlice;
import app.nadel.meters.MeterTreeValue;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.ReportCellReason;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import nadel.contract.model.ReportPeriodBasis;
import nadel.contract.model.ReportRowKind;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Electricity by meter tree ({@code electricity-tree}, functional spec 2.6, feature 013 §3.4): from the substation down
 * to the plots, what the meters show over a range of service days and what the ledger charged for it. Filter: the
 * range of days (default the previous calendar month in Kyiv, at most one year). Adds no rule: the quantities come from
 * {@code meters.MeterReports.tree} (the calculation of {@code GET /meter-tree}) and the charges from
 * {@code ledger.LedgerReadModel.electricityCharged}, {@code lossesRunsInRange} and {@code commonConsumerRunsInRange}.
 * Every kWh is shown with three decimals by {@code Quantity.toDecimal} (the one display rounding of a quantity, sign
 * kept, ADR 0014); nothing here rounds.
 * <p>
 * Sheet {@code consumption}: one row per node of the tree under each parent it had in the range, in the order of the
 * tree, {@code level} its depth (the indent). {@code name} (the meter's name, a plot meter's plot number),
 * {@code nodeKind}, {@code serial}, {@code start} and {@code end} (the value on the first day of the node's first slice
 * and on the last day of its last slice) each with its kind ({@code REAL}, {@code CALCULATED}, {@code UNKNOWN}) and,
 * when unknown, the day of the last reading ({@code startLastReading}, {@code endLastReading}), {@code consumption},
 * {@code children} (the sum of the children's consumption), {@code losses} in kWh and {@code lossesPercent} (of the
 * node's consumption; for substations and quarter meters only), {@code plotsFed}. A value that is not known is empty
 * with {@code NO_READING}; every figure derived from it is empty with {@code DERIVED_FROM_UNKNOWN}, never 0. No totals:
 * the levels of a tree do not add up.
 * <p>
 * Sheet {@code charged}: the electricity ledger rows whose service period overlaps the range, in three sections
 * ({@code CONSUMPTION}, {@code LOSSES}, {@code COMMON_CONSUMER}), each closed by a {@code SUBTOTAL} row. A section
 * lists groups of one regular charge followed by its reversals and adjustments (each its own row, {@code rowKind},
 * with the original charge's service period), groups in the natural order of the plot number, then by service period.
 * Losses and common consumer charges of a run come after a {@code RUN} row per node or consumer of that run, as booked
 * ({@code runStatus}, {@code outcome}, {@code split}, {@code consumption}, {@code children}, {@code losses},
 * {@code lossesPercent}, {@code runAmount}, {@code plots}, {@code plotDays}); a reversed run is listed with its charges
 * and their reversals, which add up to zero. The figures of a {@code RUN} row are informative and stay out of
 * {@code kwh} and {@code amount}: those two columns hold only ledger rows, so their sums are what the plot accounts
 * show. Whatever mode a charge or a run was booked under, it shows what it booked.
 * <p>
 * Totals: one line per source ({@code CONSUMPTION}: kWh used by plots, {@code LOSSES}: kWh of losses charged,
 * {@code COMMON_CONSUMER}: kWh of common consumers charged, each with its amount) and {@code all} (the amount), the
 * sums of the rows as listed. The header says the cooperative's losses mode now ({@code LOSSES_FIXED_PERCENT} with the
 * percent, or {@code LOSSES_PROPORTIONAL}).
 */
@Component
class ElectricityTreeReport implements ReportDefinition {

    static final String CONSUMPTION = "consumption";
    static final String CHARGED = "charged";
    static final String RUN = "RUN";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            new DateRangeFilter(ReportPeriodBasis.SERVICE_DAYS));

    private static final List<Column> TREE_COLUMNS = List.of(
            Column.of("name", ReportColumnType.TEXT),
            Column.codes("nodeKind", "reports.filters.options.meterKinds"),
            Column.of("serial", ReportColumnType.TEXT),
            Column.of("start", ReportColumnType.QUANTITY),
            Column.codes("startKind", "reports.electricity-tree.valueKinds"),
            Column.of("startLastReading", ReportColumnType.DATE),
            Column.of("end", ReportColumnType.QUANTITY),
            Column.codes("endKind", "reports.electricity-tree.valueKinds"),
            Column.of("endLastReading", ReportColumnType.DATE),
            Column.of("consumption", ReportColumnType.QUANTITY),
            Column.of("children", ReportColumnType.QUANTITY),
            Column.of("losses", ReportColumnType.QUANTITY),
            Column.of("lossesPercent", ReportColumnType.PERCENT),
            Column.of("plotsFed", ReportColumnType.INTEGER));

    private static final List<Column> CHARGED_COLUMNS = List.of(
            Column.codes("source", "reports.electricity-tree.sources"),
            Column.codes("rowKind", "reports.electricity-tree.rowKinds"),
            Column.of("number", ReportColumnType.TEXT),
            Column.of("serviceFrom", ReportColumnType.DATE),
            Column.of("serviceTo", ReportColumnType.DATE),
            Column.of("month", ReportColumnType.MONTH),
            Column.of("kwh", ReportColumnType.QUANTITY),
            Column.of("rate", ReportColumnType.DECIMAL),
            Column.of("amount", ReportColumnType.MONEY),
            Column.codes("runStatus", "reports.electricity-tree.runStatuses"),
            Column.codes("outcome", "reports.electricity-tree.outcomes"),
            Column.codes("split", "reports.electricity-tree.splits"),
            Column.of("consumption", ReportColumnType.QUANTITY),
            Column.of("children", ReportColumnType.QUANTITY),
            Column.of("losses", ReportColumnType.QUANTITY),
            Column.of("lossesPercent", ReportColumnType.PERCENT),
            Column.of("runAmount", ReportColumnType.MONEY),
            Column.of("plots", ReportColumnType.INTEGER),
            Column.of("plotDays", ReportColumnType.INTEGER));

    private static final Map<String, Integer> AT = index(CHARGED_COLUMNS);

    private final MeterReports meters;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final CooperativeSettings settings;

    ElectricityTreeReport(MeterReports meters, LedgerReadModel ledger, PlotCatalog plots,
            CooperativeSettings settings) {
        this.meters = meters;
        this.ledger = ledger;
        this.plots = plots;
        this.settings = settings;
    }

    @Override
    public ReportCode code() {
        return ReportCode.ELECTRICITY_TREE;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        LocalDate from = filters.from();
        LocalDate to = filters.to();
        List<Row> tree = meters.tree(from, to).rows().stream().map(ElectricityTreeReport::treeRow).toList();
        Sheet consumption = new Sheet(CONSUMPTION, TREE_COLUMNS, tree, List.of());
        Sheet charged = new Charged(ledger.electricityCharged(from, to, context.rowLimit()),
                ledger.lossesRunsInRange(from, to), ledger.commonConsumerRunsInRange(from, to)).sheet();
        return new ReportResult(List.of(consumption, charged), List.of(lossesNote()));
    }

    /** The cooperative's losses mode as it is now; a charge or a run booked under another one shows what it booked. */
    private Note lossesNote() {
        if (settings.lossesMode() == LossesMode.PROPORTIONAL) {
            return new Note(ReportNoteCode.LOSSES_PROPORTIONAL, Map.of());
        }
        return new Note(ReportNoteCode.LOSSES_FIXED_PERCENT,
                Map.of("percent", settings.lossesPercent().toPlainString()));
    }

    // ---- the consumption sheet

    private static Row treeRow(MeterTreeReportRow node) {
        List<MeterTreeReportSlice> slices = node.slices();
        List<Cell> cells = new ArrayList<>();
        cells.add(Cell.of(node.kind() == MeterKind.PLOT ? node.plotNumber() : node.name()));
        cells.add(Cell.of(node.kind().name()));
        cells.add(Cell.of(node.serial()));
        addValue(cells, slices.isEmpty() ? null : slices.getFirst().start());
        addValue(cells, slices.isEmpty() ? null : slices.getLast().end());
        cells.add(quantity(node.consumption()));
        cells.add(node.childrenConsumption() == null ? Cell.EMPTY : quantity(node.childrenConsumption()));
        MeterTreeLosses losses = node.losses();
        cells.add(losses == null ? Cell.EMPTY : quantity(losses.kwh()));
        cells.add(losses == null ? Cell.EMPTY : percent(losses));
        cells.add(Cell.of(node.plotsFed()));
        return new Row(ReportRowKind.DETAIL, node.level(), cells);
    }

    /** The value, its kind and, when it is not known, the day of the last reading. */
    private static void addValue(List<Cell> cells, @Nullable MeterTreeValue value) {
        if (value == null) {
            cells.addAll(List.of(Cell.EMPTY, Cell.EMPTY, Cell.EMPTY));
            return;
        }
        cells.add(value.value() == null ? Cell.unknown(ReportCellReason.NO_READING)
                : Cell.of(value.value().toDecimal()));
        cells.add(Cell.of(value.kind().name()));
        cells.add(Cell.of(value.lastReadingOn()));
    }

    /** A figure of the tree: its kWh, or empty with the reason when it is worked out from a value not known. */
    private static Cell quantity(MeterTreeQuantity quantity) {
        Quantity kwh = quantity.kwh();
        return kwh == null ? Cell.unknown(ReportCellReason.DERIVED_FROM_UNKNOWN) : Cell.of(kwh.toDecimal());
    }

    /** The losses in percent: unknown with the losses, empty when the consumption is not above zero. */
    private static Cell percent(MeterTreeLosses losses) {
        if (!losses.kwh().isKnown()) {
            return Cell.unknown(ReportCellReason.DERIVED_FROM_UNKNOWN);
        }
        return Cell.of(losses.percent());
    }

    // ---- the charged sheet

    /** One charged sheet: the ledger rows grouped and ordered, the runs before their charges, the sums. */
    private final class Charged {

        private final List<ChargedRow> rows;
        private final List<LossesRunNodeRow> lossesRuns;
        private final List<CommonConsumerRunLineRow> commonRuns;
        private final Map<UUID, PlotFacts> plotFacts;
        private final Map<UUID, Integer> plotOrder = new HashMap<>();
        private final Map<ChargedSource, Sum> sums = new LinkedHashMap<>();

        Charged(List<ChargedRow> rows, List<LossesRunNodeRow> lossesRuns,
                List<CommonConsumerRunLineRow> commonRuns) {
            this.rows = rows;
            this.lossesRuns = lossesRuns;
            this.commonRuns = commonRuns;
            List<UUID> plotIds = rows.stream().map(ChargedRow::plotId).distinct().toList();
            // the distinct plots of the rows, bounded by the plots of the cooperative
            this.plotFacts = plotIds.isEmpty() ? Map.of() : plots.findAll(plotIds);
            List<UUID> ordered = plotIds.isEmpty() ? List.of() : plots.inNaturalOrder(plotIds);
            for (int position = 0; position < ordered.size(); position++) {
                plotOrder.put(ordered.get(position), position);
            }
        }

        Sheet sheet() {
            Map<ChargedSource, List<Group>> bySource = new LinkedHashMap<>();
            for (Group group : groups()) {
                bySource.computeIfAbsent(group.head().source(), source -> new ArrayList<>()).add(group);
            }
            Map<UUID, List<Row>> lossesRunRows = new LinkedHashMap<>();
            for (LossesRunNodeRow node : lossesRuns) {
                lossesRunRows.computeIfAbsent(node.runId(), run -> new ArrayList<>()).add(runRow(node));
            }
            Map<UUID, List<Row>> commonRunRows = new LinkedHashMap<>();
            for (CommonConsumerRunLineRow line : commonRuns) {
                commonRunRows.computeIfAbsent(line.runId(), run -> new ArrayList<>()).add(runRow(line));
            }
            List<Row> sheetRows = new ArrayList<>();
            for (ChargedSource source : ChargedSource.values()) {
                Map<UUID, List<Row>> runs = switch (source) {
                    case CONSUMPTION -> Map.of();
                    case LOSSES -> lossesRunRows;
                    case COMMON_CONSUMER -> commonRunRows;
                };
                section(sheetRows, source, bySource.getOrDefault(source, List.of()), runs);
            }
            List<Total> totals = new ArrayList<>();
            if (!sheetRows.isEmpty()) {
                Money all = Money.ZERO;
                for (ChargedSource source : ChargedSource.values()) {
                    Sum sum = sums.getOrDefault(source, new Sum());
                    Cell[] cells = empty();
                    cells[AT.get("kwh")] = Cell.of(sum.kwh);
                    cells[AT.get("amount")] = Cell.of(sum.amount);
                    totals.add(new Total(source.name(), List.of(cells)));
                    all = all.plus(sum.amount);
                }
                Cell[] cells = empty();
                cells[AT.get("amount")] = Cell.of(all);
                totals.add(new Total("all", List.of(cells)));
            }
            return new Sheet(CHARGED, CHARGED_COLUMNS, sheetRows, totals);
        }

        /**
         * One section: the charges without a run, then each run's rows followed by its charges, then charges of a run
         * not in the range's runs (none in practice), and a subtotal when the section has rows.
         */
        private void section(List<Row> into, ChargedSource source, List<Group> groups, Map<UUID, List<Row>> runs) {
            Sum sum = new Sum();
            List<Row> section = new ArrayList<>();
            Map<UUID, List<Group>> byRun = new LinkedHashMap<>();
            List<Group> withoutRun = new ArrayList<>();
            for (Group group : groups) {
                UUID run = group.head().runId();
                if (run == null) {
                    withoutRun.add(group);
                } else {
                    byRun.computeIfAbsent(run, id -> new ArrayList<>()).add(group);
                }
            }
            addGroups(section, withoutRun, sum);
            for (Map.Entry<UUID, List<Row>> run : runs.entrySet()) {
                section.addAll(run.getValue());
                addGroups(section, byRun.getOrDefault(run.getKey(), List.of()), sum);
                byRun.remove(run.getKey());
            }
            addGroups(section, byRun.values().stream().flatMap(List::stream).toList(), sum);
            if (section.isEmpty()) {
                return;
            }
            into.addAll(section);
            Cell[] cells = empty();
            cells[AT.get("source")] = Cell.of(source.name());
            cells[AT.get("kwh")] = Cell.of(sum.kwh);
            cells[AT.get("amount")] = Cell.of(sum.amount);
            into.add(Row.subtotal(List.of(cells)));
            sums.put(source, sum);
        }

        /** The groups in the natural order of the plot number, then by service period; each charge then its fixes. */
        private void addGroups(List<Row> into, List<Group> groups, Sum sum) {
            List<Group> ordered = new ArrayList<>(groups);
            ordered.sort(Comparator.comparing((Group group) -> plotOrder.getOrDefault(group.head().plotId(),
                    Integer.MAX_VALUE)).thenComparing(group -> group.head().serviceFrom())
                    .thenComparing(group -> group.head().serviceTo()));
            for (Group group : ordered) {
                for (ChargedRow row : group.rows()) {
                    BigDecimal kwh = row.quantity().toDecimal();
                    sum.kwh = sum.kwh.add(kwh);
                    sum.amount = sum.amount.plus(row.amount());
                    into.add(chargeRow(group.head().source(), row, kwh));
                }
            }
        }

        /**
         * The rows in groups: each regular charge with the reversals and adjustments that point at it or at one of its
         * corrections, in the ledger's order (each charge is followed by its corrections). A correction whose charge
         * is not listed starts a group of its own.
         */
        private List<Group> groups() {
            List<Group> groups = new ArrayList<>();
            Map<UUID, Group> byCharge = new HashMap<>();
            for (ChargedRow row : rows) {
                Group group = row.originalChargeId() == null ? null : byCharge.get(row.originalChargeId());
                if (group == null) {
                    group = new Group(row, new ArrayList<>());
                    groups.add(group);
                }
                group.rows().add(row);
                byCharge.put(row.chargeId(), group);
            }
            return groups;
        }

        private Row chargeRow(ChargedSource source, ChargedRow row, BigDecimal kwh) {
            PlotFacts plot = plotFacts.get(row.plotId());
            BigDecimal rate = row.rate();
            Cell[] cells = empty();
            cells[AT.get("source")] = Cell.of(source.name());
            cells[AT.get("rowKind")] = Cell.of(row.kind().name());
            cells[AT.get("number")] = Cell.of(plot == null ? null : plot.number());
            cells[AT.get("serviceFrom")] = Cell.of(row.serviceFrom());
            cells[AT.get("serviceTo")] = Cell.of(row.serviceTo());
            cells[AT.get("month")] = Cell.of(row.billingMonth());
            cells[AT.get("kwh")] = Cell.of(kwh);
            cells[AT.get("rate")] = rate != null ? Cell.of(rate)
                    : row.parts().isEmpty() ? Cell.EMPTY : Cell.unknown(ReportCellReason.SEVERAL_RATES);
            cells[AT.get("amount")] = Cell.of(row.amount());
            return Row.detail(List.of(cells));
        }

        /** A node of a losses run as booked: informative, outside the kWh and amount columns. */
        private static Row runRow(LossesRunNodeRow node) {
            Cell[] cells = runCells(ChargedSource.LOSSES, node.nodeName(), node.runFrom(), node.runTo(),
                    node.billingMonth(), node.runStatus().name(), node.outcome().name());
            cells[AT.get("consumption")] = known(node.consumption(), ReportCellReason.NO_READING);
            cells[AT.get("children")] = known(node.children(), ReportCellReason.DERIVED_FROM_UNKNOWN);
            cells[AT.get("losses")] = known(node.losses(), ReportCellReason.DERIVED_FROM_UNKNOWN);
            cells[AT.get("lossesPercent")] = node.losses() == null
                    ? Cell.unknown(ReportCellReason.DERIVED_FROM_UNKNOWN) : Cell.of(node.percent());
            cells[AT.get("runAmount")] = Cell.of(node.amount());
            cells[AT.get("plots")] = Cell.of(node.plotsCharged());
            return Row.detail(List.of(cells));
        }

        /** A consumer of a common consumers run as booked: informative, outside the kWh and amount columns. */
        private static Row runRow(CommonConsumerRunLineRow line) {
            Cell[] cells = runCells(ChargedSource.COMMON_CONSUMER, line.consumerName(), line.runFrom(),
                    line.runTo(), line.billingMonth(), line.runStatus().name(), line.outcome().name());
            cells[AT.get("split")] = Cell.of(line.split().name());
            cells[AT.get("consumption")] = known(line.consumption(), ReportCellReason.NO_READING);
            cells[AT.get("runAmount")] = Cell.of(line.amount());
            cells[AT.get("plots")] = Cell.of(line.plotsShared());
            cells[AT.get("plotDays")] = Cell.of(line.plotDays());
            return Row.detail(List.of(cells));
        }

        private static Cell[] runCells(ChargedSource source, @Nullable String name, LocalDate from, LocalDate to,
                YearMonth month, String status, String outcome) {
            Cell[] cells = empty();
            cells[AT.get("source")] = Cell.of(source.name());
            cells[AT.get("rowKind")] = Cell.of(RUN);
            cells[AT.get("number")] = Cell.of(name);
            cells[AT.get("serviceFrom")] = Cell.of(from);
            cells[AT.get("serviceTo")] = Cell.of(to);
            cells[AT.get("month")] = Cell.of(month);
            cells[AT.get("runStatus")] = Cell.of(status);
            cells[AT.get("outcome")] = Cell.of(outcome);
            return cells;
        }

        private static Cell known(@Nullable Quantity quantity, ReportCellReason reason) {
            return quantity == null ? Cell.unknown(reason) : Cell.of(quantity.toDecimal());
        }
    }

    /** A regular charge (or a correction whose charge is not listed) and the rows that correct it, in order. */
    private record Group(ChargedRow head, List<ChargedRow> rows) {
    }

    /** The kWh and amount of the rows listed so far, added as listed (no rounding of their own). */
    private static final class Sum {

        private BigDecimal kwh = BigDecimal.ZERO.setScale(3);
        private Money amount = Money.ZERO;
    }

    private static Cell[] empty() {
        Cell[] cells = new Cell[CHARGED_COLUMNS.size()];
        Arrays.fill(cells, Cell.EMPTY);
        return cells;
    }

    private static Map<String, Integer> index(List<Column> columns) {
        Map<String, Integer> at = new HashMap<>();
        for (int position = 0; position < columns.size(); position++) {
            at.put(columns.get(position).key(), position);
        }
        return Map.copyOf(at);
    }
}
