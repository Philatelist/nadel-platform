package app.nadel.reports.internal;

import app.nadel.ledger.ChargedRow;
import app.nadel.ledger.ChargedRowKind;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.meters.MeterReports;
import app.nadel.meters.WaterConnections.ConnectedPeriod;
import app.nadel.meters.WaterSourceKind;
import app.nadel.meters.WaterUseRow;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCellReason;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Water ({@code water}, functional spec 2.7, feature 013 §3.5): the water use and the water charges of the plots that
 * had a water connection in a range of service days. Filters: the range of days (default the previous calendar month
 * in Kyiv, at most one year), the kind of water source (every well, the cooperative's water supply, or all) and the
 * street. Adds no rule: the connected days and cubic metres come from {@code meters.MeterReports.waterUse} (the range
 * consumption of 010 restricted to the connected days) and the charges from {@code ledger.LedgerReadModel.waterCharged};
 * every m³ is shown with three decimals by {@code Quantity.toDecimal} (ADR 0014) and nothing here rounds.
 * <p>
 * Sheet {@code main}: one row per plot and water source it was connected to on at least one day of the range (a plot
 * that changed source has a row per source), plots in the natural order of their numbers; a plot that was not
 * connected on any day is not listed (money rules 11), whatever was charged to it. {@code number}, {@code street},
 * {@code source} ({@code COOPERATIVE_SUPPLY} or {@code METER}, a well), {@code sourceName} (the well's meter name),
 * {@code connectedDays}, {@code serial} (the plot's water meter; empty without one), {@code cubicMetres} (empty with
 * {@code NO_READING} when a value is not known, never 0, with {@code lastReading} the day after which a reading is
 * missing; empty without a reason when the plot has no water meter), then the water charges whose service period
 * overlaps the range, each the net of its regular charges, reversals and adjustments: {@code consumption} (by meter),
 * {@code fixedFee} (the tariff charge of the month run), {@code pumpShare} (a well pump's common consumer share),
 * {@code corrections} (of which reversals and adjustments, all kinds) and {@code total}.
 * <p>
 * A plot's charges are attributed to one of its rows: a pump share to the row of its well (the consumer meter its
 * calculation names), any other charge to the row whose connected days overlap the charge's days in the range most
 * (the first row on a tie or without overlap). The attribution is made over every source before the source filter
 * applies, so a plot listed for one source never shows the charges of its other one.
 * <p>
 * Sheet {@code summary}: one row per source (the cooperative's water supply first, then the wells by name):
 * {@code plots} (distinct plots), {@code plotDays} (the connected days of its rows), {@code cubicMetres} and
 * {@code amount}, sums of the rows of {@code main}. Totals {@code all} on both sheets: sums of the rows listed, the m³
 * empty with {@code DERIVED_FROM_UNKNOWN} when one of them is not known; the plot count has no total (a plot that
 * changed source counts under both).
 * <p>
 * A well pump's share is also a common consumer charge of the electricity report: the two reports can show the same
 * amount.
 */
@Component
class WaterReport implements ReportDefinition {

    static final String MAIN = "main";
    static final String SUMMARY = "summary";
    static final String SOURCES = "reports.filters.options.waterSource";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            new DateRangeFilter(ReportPeriodBasis.SERVICE_DAYS), new WaterSourceFilter(), new StreetFilter());

    private static final List<Column> MAIN_COLUMNS = List.of(
            Column.of("number", ReportColumnType.TEXT),
            Column.of("street", ReportColumnType.TEXT),
            Column.codes("source", SOURCES),
            Column.of("sourceName", ReportColumnType.TEXT),
            Column.of("connectedDays", ReportColumnType.INTEGER),
            Column.of("serial", ReportColumnType.TEXT),
            Column.of("cubicMetres", ReportColumnType.QUANTITY),
            Column.of("lastReading", ReportColumnType.DATE),
            Column.of("consumption", ReportColumnType.MONEY),
            Column.of("fixedFee", ReportColumnType.MONEY),
            Column.of("pumpShare", ReportColumnType.MONEY),
            Column.of("corrections", ReportColumnType.MONEY),
            Column.of("total", ReportColumnType.MONEY));

    private static final List<Column> SUMMARY_COLUMNS = List.of(
            Column.codes("source", SOURCES),
            Column.of("sourceName", ReportColumnType.TEXT),
            Column.of("plots", ReportColumnType.INTEGER),
            Column.of("plotDays", ReportColumnType.INTEGER),
            Column.of("cubicMetres", ReportColumnType.QUANTITY),
            Column.of("amount", ReportColumnType.MONEY));

    private static final Collator UKRAINIAN = Collator.getInstance(Locale.forLanguageTag("uk"));

    private final MeterReports meters;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;

    WaterReport(MeterReports meters, LedgerReadModel ledger, PlotCatalog plots) {
        this.meters = meters;
        this.ledger = ledger;
        this.plots = plots;
    }

    @Override
    public ReportCode code() {
        return ReportCode.WATER;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        LocalDate from = filters.from();
        LocalDate to = filters.to();
        // every source: the charges of a plot are attributed among all its rows before the source filter applies
        List<WaterUseRow> use = meters.waterUse(from, to, null);
        if (filters.street() != null) {
            Set<UUID> onStreet = Set.copyOf(plots.idsOnStreet(filters.street()));
            use = use.stream().filter(row -> onStreet.contains(row.plotId())).toList();
        }
        List<Line> lines = use.stream().map(Line::new).toList();
        attributeCharges(lines, from, to, context.rowLimit());

        WaterSourceKind source = filters.waterSource();
        List<Line> listed = new ArrayList<>(lines.stream().filter(line -> source == null
                || line.use.source() == source).toList());
        List<UUID> plotIds = listed.stream().map(line -> line.use.plotId()).distinct().toList();
        Map<UUID, Integer> order = new HashMap<>();
        List<UUID> natural = plotIds.isEmpty() ? List.of() : plots.inNaturalOrder(plotIds);
        for (int position = 0; position < natural.size(); position++) {
            order.put(natural.get(position), position);
        }
        // a stable sort: a plot's rows keep the facade's order, by their first connected day
        listed.sort(Comparator.comparing(line -> order.getOrDefault(line.use.plotId(), Integer.MAX_VALUE)));
        return new ReportResult(List.of(main(listed), summary(listed)), List.of());
    }

    // ---- the charges

    /**
     * Adds every water charge of the listed plots to one row of its plot.
     *
     * @throws ApiProblemException 422 {@code REPORT_TOO_LARGE} when the ledger has as many rows as the limit: the
     *                             amounts would miss the rows past it
     */
    private void attributeCharges(List<Line> lines, LocalDate from, LocalDate to, int limit) {
        Map<UUID, List<Line>> byPlot = new LinkedHashMap<>();
        lines.forEach(line -> byPlot.computeIfAbsent(line.use.plotId(), plot -> new ArrayList<>()).add(line));
        if (byPlot.isEmpty()) {
            return;
        }
        List<ChargedRow> charged = ledger.waterCharged(from, to, byPlot.keySet(), limit);
        if (charged.size() >= limit) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.REPORT_TOO_LARGE,
                    "The report is too large. Narrow the filters.");
        }
        for (ChargedRow row : charged) {
            List<Line> candidates = byPlot.get(row.plotId());
            if (candidates != null) {
                attribute(row, candidates, from, to).add(row);
            }
        }
    }

    /**
     * The row of the plot a charge belongs to: a pump share to its well's row; otherwise the row whose connected days
     * overlap the charge's days within the range most, the first on a tie.
     */
    private static Line attribute(ChargedRow charge, List<Line> candidates, LocalDate from, LocalDate to) {
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (charge.consumerMeterId() != null) {
            for (Line line : candidates) {
                if (charge.consumerMeterId().equals(line.use.sourceMeterId())) {
                    return line;
                }
            }
        }
        LocalDate start = charge.serviceFrom().isAfter(from) ? charge.serviceFrom() : from;
        LocalDate end = charge.serviceTo().isBefore(to) ? charge.serviceTo() : to;
        Line best = candidates.getFirst();
        long bestDays = 0;
        for (Line line : candidates) {
            long days = overlap(line.use.periods(), start, end);
            if (days > bestDays) {
                best = line;
                bestDays = days;
            }
        }
        return best;
    }

    private static long overlap(List<ConnectedPeriod> periods, LocalDate start, LocalDate end) {
        long days = 0;
        for (ConnectedPeriod period : periods) {
            LocalDate first = period.from().isAfter(start) ? period.from() : start;
            LocalDate last = period.to().isBefore(end) ? period.to() : end;
            if (!last.isBefore(first)) {
                days += ChronoUnit.DAYS.between(first, last) + 1;
            }
        }
        return days;
    }

    // ---- the sheets

    private static Sheet main(List<Line> lines) {
        List<Row> rows = new ArrayList<>();
        Sum sum = new Sum();
        for (Line line : lines) {
            WaterUseRow use = line.use;
            sum.add(line);
            rows.add(Row.detail(List.of(
                    Cell.of(use.plotNumber()),
                    Cell.of(use.street()),
                    Cell.of(use.source().name()),
                    Cell.of(use.sourceName()),
                    Cell.of(use.connectedDays()),
                    Cell.of(use.meterSerial()),
                    cubicMetres(use),
                    Cell.of(use.lastReadingOn()),
                    Cell.of(line.consumption),
                    Cell.of(line.fixedFee),
                    Cell.of(line.pumpShare),
                    Cell.of(line.corrections),
                    Cell.of(line.total()))));
        }
        List<Total> totals = rows.isEmpty() ? List.of() : List.of(new Total("all", List.of(Cell.EMPTY, Cell.EMPTY,
                Cell.EMPTY, Cell.EMPTY, Cell.of(sum.plotDays), Cell.EMPTY, sum.cubicMetres(), Cell.EMPTY,
                Cell.of(sum.consumption), Cell.of(sum.fixedFee), Cell.of(sum.pumpShare), Cell.of(sum.corrections),
                Cell.of(sum.amount))));
        return new Sheet(MAIN, MAIN_COLUMNS, rows, totals);
    }

    private static Sheet summary(List<Line> lines) {
        Map<SourceKey, Sum> bySource = new HashMap<>();
        for (Line line : lines) {
            bySource.computeIfAbsent(new SourceKey(line.use.source(), line.use.sourceMeterId(), line.use.sourceName()),
                    key -> new Sum()).add(line);
        }
        List<SourceKey> sources = new ArrayList<>(bySource.keySet());
        sources.sort(Comparator.comparing((SourceKey key) -> key.kind() != WaterSourceKind.COOPERATIVE_SUPPLY)
                .thenComparing(key -> Objects.requireNonNullElse(key.name(), ""), UKRAINIAN)
                .thenComparing(key -> Objects.toString(key.meterId(), "")));
        List<Row> rows = new ArrayList<>();
        Sum all = new Sum();
        for (SourceKey key : sources) {
            Sum sum = bySource.get(key);
            all.add(sum);
            rows.add(Row.detail(List.of(Cell.of(key.kind().name()), Cell.of(key.name()), Cell.of(sum.plots.size()),
                    Cell.of(sum.plotDays), sum.cubicMetres(), Cell.of(sum.amount))));
        }
        List<Total> totals = rows.isEmpty() ? List.of() : List.of(new Total("all", List.of(Cell.EMPTY, Cell.EMPTY,
                Cell.EMPTY, Cell.of(all.plotDays), all.cubicMetres(), Cell.of(all.amount))));
        return new Sheet(SUMMARY, SUMMARY_COLUMNS, rows, totals);
    }

    /** The m³ of a row: empty without a meter, empty with the reason when a value is not known, never 0. */
    private static Cell cubicMetres(WaterUseRow use) {
        if (use.meterId() == null) {
            return Cell.EMPTY;
        }
        if (use.cubicMetres() == null) {
            return Cell.unknown(ReportCellReason.NO_READING);
        }
        return Cell.of(use.cubicMetres().toDecimal());
    }

    /** One row of the main sheet: the water use and the net charges attributed to it. */
    private static final class Line {

        private final WaterUseRow use;
        private Money consumption = Money.ZERO;
        private Money fixedFee = Money.ZERO;
        private Money pumpShare = Money.ZERO;
        private Money corrections = Money.ZERO;

        Line(WaterUseRow use) {
            this.use = use;
        }

        void add(ChargedRow row) {
            switch (row.source()) {
                case CONSUMPTION -> consumption = consumption.plus(row.amount());
                case TARIFF -> fixedFee = fixedFee.plus(row.amount());
                case COMMON_CONSUMER -> pumpShare = pumpShare.plus(row.amount());
                case LOSSES -> throw new IllegalStateException("The water rows have no losses");
            }
            if (row.kind() != ChargedRowKind.REGULAR) {
                corrections = corrections.plus(row.amount());
            }
        }

        Money total() {
            return consumption.plus(fixedFee).plus(pumpShare);
        }
    }

    /** A water source: the cooperative's water supply, or one well. */
    private record SourceKey(WaterSourceKind kind, @Nullable UUID meterId, @Nullable String name) {
    }

    /** The sums of rows as listed: the displayed m³ (unknown when one is), the amounts with {@code Money.plus}. */
    private static final class Sum {

        private final Set<UUID> plots = new HashSet<>();
        private long plotDays;
        private BigDecimal cubicMetres = BigDecimal.ZERO.setScale(3);
        private boolean measured;
        private boolean unknown;
        private Money consumption = Money.ZERO;
        private Money fixedFee = Money.ZERO;
        private Money pumpShare = Money.ZERO;
        private Money corrections = Money.ZERO;
        private Money amount = Money.ZERO;

        void add(Line line) {
            WaterUseRow use = line.use;
            plots.add(use.plotId());
            plotDays += use.connectedDays();
            if (use.meterId() != null) {
                measured = true;
                if (use.cubicMetres() == null) {
                    unknown = true;
                } else {
                    cubicMetres = cubicMetres.add(use.cubicMetres().toDecimal());
                }
            }
            consumption = consumption.plus(line.consumption);
            fixedFee = fixedFee.plus(line.fixedFee);
            pumpShare = pumpShare.plus(line.pumpShare);
            corrections = corrections.plus(line.corrections);
            amount = amount.plus(line.total());
        }

        void add(Sum other) {
            plots.addAll(other.plots);
            plotDays += other.plotDays;
            measured |= other.measured;
            unknown |= other.unknown;
            cubicMetres = cubicMetres.add(other.cubicMetres);
            amount = amount.plus(other.amount);
        }

        Cell cubicMetres() {
            if (!measured) {
                return Cell.EMPTY;
            }
            return unknown ? Cell.unknown(ReportCellReason.DERIVED_FROM_UNKNOWN) : Cell.of(cubicMetres);
        }
    }
}
