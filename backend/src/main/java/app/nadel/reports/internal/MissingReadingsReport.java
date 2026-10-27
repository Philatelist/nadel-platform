package app.nadel.reports.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MissingReadingRow;
import app.nadel.meters.Quantity;
import app.nadel.plots.PlotCatalog;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Missing readings ({@code missing-readings}, functional spec 2.8, feature 013 §3.6): the active meters without an
 * approved reading in a range of reading dates, so the board can ask the owners for them. Filters: the range of days
 * (default the previous calendar month in Kyiv, at most one year), the resource (default both), the meter kinds
 * (default plot meters only; quarter meters, substations and common consumers optional) and the street (only the plot
 * meters of its plots). Adds no rule: the rows are {@code meters.MeterReports.missingReadings}, in the order of the
 * meter register (plot meters in street order, then the other kinds by kind and name).
 * <p>
 * Sheet {@code main}, one row per meter that worked through the whole range with no approved reading dated in it:
 * {@code resource}, {@code kind}, {@code name} (a plot meter's plot number, another meter's name), {@code street},
 * {@code serial}, {@code lastReadingOn} and {@code lastReadingValue} (its last point before the range, three decimals),
 * {@code initialValue} (true when that point is the installation's initial value, not a reading), {@code daysSince}
 * (to the last day of the range) and {@code pending} (a member's reading dated in the range waits for approval). Not
 * listed (the facade's rules): a water meter of a plot not connected to water on any day of the range; a meter
 * installed after the range or removed before it; and a meter installed or removed within the range, whose initial or
 * final value is a point of the range. No totals.
 * <p>
 * The header note {@code OPEN_NOT_CHARGED} (param {@code count}) counts the open "not charged" items of kind
 * {@code INTERVAL} of the listed meters ({@code ledger.LedgerReadModel.openIntervalItems}) when there are any; the web
 * links it to the queue. The report does not replace the queue.
 */
@Component
class MissingReadingsReport implements ReportDefinition {

    static final String MAIN = "main";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            new DateRangeFilter(ReportPeriodBasis.READING_DATES), new ResourceFilter(), new MeterKindsFilter(),
            new StreetFilter());

    private static final List<Column> COLUMNS = List.of(
            Column.codes("resource", "reports.filters.options.resource"),
            Column.codes("kind", "reports.filters.options.meterKinds"),
            Column.of("name", ReportColumnType.TEXT),
            Column.of("street", ReportColumnType.TEXT),
            Column.of("serial", ReportColumnType.TEXT),
            Column.of("lastReadingOn", ReportColumnType.DATE),
            Column.of("lastReadingValue", ReportColumnType.QUANTITY),
            Column.of("initialValue", ReportColumnType.BOOLEAN),
            Column.of("daysSince", ReportColumnType.INTEGER),
            Column.of("pending", ReportColumnType.BOOLEAN));

    private final MeterReports meters;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;

    MissingReadingsReport(MeterReports meters, LedgerReadModel ledger, PlotCatalog plots) {
        this.meters = meters;
        this.ledger = ledger;
        this.plots = plots;
    }

    @Override
    public ReportCode code() {
        return ReportCode.MISSING_READINGS;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        MeterResource resource = filters.resource();
        Set<MeterResource> resources = resource == null ? Set.of() : Set.of(resource);
        Set<MeterKind> kinds = Set.copyOf(filters.meterKinds());
        @Nullable Collection<UUID> plotIds = filters.street() == null ? null : plots.idsOnStreet(filters.street());
        List<MissingReadingRow> missing = meters.missingReadings(filters.from(), filters.to(), resources, kinds,
                plotIds, context.rowLimit());
        List<Row> rows = missing.stream().map(MissingReadingsReport::row).toList();
        List<Note> notes = List.of();
        if (!missing.isEmpty()) {
            // the listed meters only, at most the cap plus one (the runner refuses more)
            Map<UUID, Integer> items = ledger.openIntervalItems(missing.stream().map(MissingReadingRow::meterId)
                    .toList());
            int count = items.values().stream().mapToInt(Integer::intValue).sum();
            if (count > 0) {
                notes = List.of(new Note(ReportNoteCode.OPEN_NOT_CHARGED, Map.of("count", Integer.toString(count))));
            }
        }
        return new ReportResult(List.of(new Sheet(MAIN, COLUMNS, rows, List.of())), notes);
    }

    private static Row row(MissingReadingRow meter) {
        LocalDate lastOn = meter.lastReadingOn();
        return Row.detail(List.of(
                Cell.of(meter.resource().name()),
                Cell.of(meter.kind().name()),
                Cell.of(meter.kind() == MeterKind.PLOT ? meter.plotNumber() : meter.name()),
                Cell.of(meter.street()),
                Cell.of(meter.serial()),
                Cell.of(lastOn),
                // the one display form of a quantity (three decimals), as the electricity report shows meter values
                Cell.of(Quantity.of(meter.lastReadingValue()).toDecimal()),
                Cell.of(meter.lastReadingInitial()),
                Cell.of(meter.daysSinceLastReading()),
                Cell.of(meter.pendingApprovalInRange())));
    }
}
