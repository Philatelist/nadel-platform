package app.nadel.reports.internal;

import app.nadel.ledger.CorrectionRow;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportPeriodBasis;
import org.springframework.stereotype.Component;

/**
 * Corrections ({@code corrections}, functional spec 2.10, feature 013 §3.8): every reversal and adjustment booked in a
 * range of billing months, so the board can show the general meeting every change of the past. Filters: billing months
 * (default the previous month), charge types (default all), kinds of correction (default both). Reads
 * {@code ledger.LedgerReadModel.corrections} with the row cap plus one as its limit, the plot numbers from
 * {@code plots.PlotCatalog.findAll} (500 ids per call, archived plots included) and the names of the members who booked
 * them from {@code members.MemberDirectory.find} (one call for the distinct authors).
 * <p>
 * Sheet {@code main}, one row per correction in the ledger's order (billing month, then the time it was booked, then
 * id): {@code bookedOn} (the Kyiv day), {@code month} (the billing month), {@code number} (the plot),
 * {@code chargeType}, {@code kind} ({@code REVERSAL}/{@code ADJUSTMENT}, texts
 * {@code reports.filters.options.correctionKinds}), {@code originalFrom}, {@code originalTo} and
 * {@code originalAmount} (the service period and the booked amount of the corrected charge), {@code amount} (signed as
 * booked: a reversal is negative), {@code source} ({@code MANUAL_REVERSAL}/{@code RECALCULATION}/{@code RUN_REVERSAL},
 * texts {@code reports.corrections.sources}), {@code cause} (the cause of the recalculation, only for a correction a
 * recalculation booked, texts {@code recalculation.causeKind}), {@code reason} (the reason booked with the correction,
 * user content shown as entered: the recalculation's reason, or the reason given for a reversal by hand or of a run)
 * and {@code bookedBy} (the member's "Last First"; empty when the row has no author).
 * <p>
 * The totals {@code all} sum {@code amount} only (summing the original amounts would count a charge twice when it was
 * corrected twice). A range without corrections gives a sheet without rows and without a totals line: the screen says
 * there is nothing to show, the file has the header block and the column headers only.
 */
@Component
class CorrectionsReport implements ReportDefinition {

    static final String MAIN = "main";

    /** Plots per {@link PlotCatalog#findAll} call. */
    private static final int CHUNK = 500;

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new ChargeTypesFilter(),
            new CorrectionKindsFilter());

    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final MemberDirectory members;

    CorrectionsReport(LedgerReadModel ledger, PlotCatalog plots, MemberDirectory members) {
        this.ledger = ledger;
        this.plots = plots;
        this.members = members;
    }

    @Override
    public ReportCode code() {
        return ReportCode.CORRECTIONS;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        List<CorrectionRow> corrections = ledger.corrections(filters.fromMonth(), filters.toMonth(),
                new LinkedHashSet<>(filters.chargeTypeIds()), filters.correctionKinds(), context.rowLimit());
        Map<UUID, PlotFacts> plotFacts = plotsOf(corrections);
        Map<UUID, MemberRef> authors = members.find(corrections.stream().map(CorrectionRow::bookedBy)
                .filter(Objects::nonNull).distinct().toList());

        List<Column> columns = List.of(
                Column.of("bookedOn", ReportColumnType.DATE),
                Column.of("month", ReportColumnType.MONTH),
                Column.of("number", ReportColumnType.TEXT),
                Column.of("chargeType", ReportColumnType.CHARGE_TYPE),
                Column.codes("kind", "reports.filters.options.correctionKinds"),
                Column.of("originalFrom", ReportColumnType.DATE),
                Column.of("originalTo", ReportColumnType.DATE),
                Column.of("originalAmount", ReportColumnType.MONEY),
                Column.of("amount", ReportColumnType.MONEY),
                Column.codes("source", "reports.corrections.sources"),
                Column.codes("cause", "recalculation.causeKind"),
                Column.of("reason", ReportColumnType.TEXT),
                Column.of("bookedBy", ReportColumnType.TEXT));

        List<Row> rows = new ArrayList<>();
        Money total = Money.ZERO;
        for (CorrectionRow correction : corrections) {
            PlotFacts plot = plotFacts.get(correction.plotId());
            MemberRef author = correction.bookedBy() == null ? null : authors.get(correction.bookedBy());
            rows.add(Row.detail(List.of(
                    Cell.of(correction.bookedOn()),
                    Cell.of(correction.billingMonth()),
                    Cell.of(plot == null ? null : plot.number()),
                    Cell.of(correction.chargeTypeId()),
                    Cell.of(correction.kind().name()),
                    Cell.of(correction.originalServiceFrom()),
                    Cell.of(correction.originalServiceTo()),
                    Cell.of(correction.originalAmount()),
                    Cell.of(correction.amount()),
                    Cell.of(correction.source().name()),
                    Cell.of(correction.cause() == null ? null : correction.cause().name()),
                    Cell.of(correction.reason().isBlank() ? null : correction.reason()),
                    Cell.of(author == null ? null : author.shortName()))));
            total = total.plus(correction.amount());
        }
        List<Total> totals = new ArrayList<>();
        if (!rows.isEmpty()) {
            List<Cell> cells = new ArrayList<>();
            for (Column column : columns) {
                cells.add("amount".equals(column.key()) ? Cell.of(total) : Cell.EMPTY);
            }
            totals.add(new Total("all", cells));
        }
        return new ReportResult(List.of(new Sheet(MAIN, columns, rows, totals)), List.of());
    }

    /** The plots of the corrections, archived ones included, {@value #CHUNK} ids per call. */
    private Map<UUID, PlotFacts> plotsOf(List<CorrectionRow> corrections) {
        List<UUID> ids = corrections.stream().map(CorrectionRow::plotId).distinct().toList();
        Map<UUID, PlotFacts> facts = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            facts.putAll(plots.findAll(ids.subList(from, Math.min(ids.size(), from + CHUNK))));
        }
        return facts;
    }
}
