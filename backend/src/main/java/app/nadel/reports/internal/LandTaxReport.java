package app.nadel.reports.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.tariffs.ExpectedAmounts;
import app.nadel.tariffs.VersionShare;
import app.nadel.tariffs.YearExpectation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import org.springframework.stereotype.Component;

/**
 * Land tax ({@code land-tax}, functional spec 2.9, feature 013 §3.7): per plot, what the land tax tariffs are expected
 * to charge for a year and what the ledger charged for it, so the treasurer can prepare the tax report. Filter: the
 * year (default the current year in Kyiv). The money rule stays in tariffs: the expectation is
 * {@code tariffs.ExpectedAmounts.yearByVersion} (month by month as the month run charges, one share per tariff version)
 * and the charged amount {@code ledger.LedgerReadModel.chargedInYear} (the effective amount of the land tax charges
 * whose service period starts in the year: charges, reversals and adjustments, without opening balances).
 * <p>
 * Sheet {@code main}: the active plots that are not common land ({@code PlotCatalog.findActive}, natural order of the
 * number) that the land tax charges on some day of the year; a plot out of every tariff's scope, excluded, or without a
 * version in the year is not listed. One row per tariff version that charges the plot in the year, oldest first:
 * {@code number}, {@code street}, {@code areaM2} and {@code areaSotka} (exact, {@code DECIMAL}), {@code rate} (per
 * sotka per year as entered, {@code DECIMAL} with four decimals), {@code from} and {@code to} (the first and last day
 * of the year the version charges), {@code days}, {@code expected} (the version's share of the yearly amount),
 * {@code charged} and {@code mismatch}. The plot's first row carries the area, the charged amount for the year and the
 * mismatch mark ({@code true} when the plot's expected amount, the sum of its shares, differs from the charged amount);
 * a continuation row (a second rate in the year) repeats the number and street for reading and leaves the area,
 * {@code charged} and {@code mismatch} empty, so every amount is in the sheet once.
 * <p>
 * The totals {@code all} sum {@code expected} and {@code charged} over the rows listed ({@code Money.plus}, nothing
 * rounded again). When some plot is marked, the header note {@code RECALCULATION_SUGGESTED} (param {@code count}, the
 * number of plots marked) suggests a recalculation (feature 012); the report never books one. File name
 * {@code land-tax_<year>-01-01_<year>-12-31.xlsx}.
 */
@Component
class LandTaxReport implements ReportDefinition {

    static final String MAIN = "main";
    static final String LAND_TAX = ChargeTypeCode.LAND_TAX.getValue();

    private static final List<ReportFilterDefinition> FILTERS = List.of(new YearFilter());

    private final PlotCatalog plots;
    private final ExpectedAmounts expectedAmounts;
    private final LedgerReadModel ledger;

    LandTaxReport(PlotCatalog plots, ExpectedAmounts expectedAmounts, LedgerReadModel ledger) {
        this.plots = plots;
        this.expectedAmounts = expectedAmounts;
        this.ledger = ledger;
    }

    @Override
    public ReportCode code() {
        return ReportCode.LAND_TAX;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    /** A plot of the sheet: what the land tax is expected to charge it for the year and what was charged. */
    record PlotLandTax(PlotFacts plot, YearExpectation expectation, Money charged) {
    }

    /**
     * The plots of the sheet for the year, natural order of the number: the active plots without common land that the
     * land tax charges on some day of the year. The rows of this report and the land tax of the template row set
     * {@code plots} and totals ({@link TemplateValues}).
     */
    List<PlotLandTax> plots(int year) {
        List<PlotFacts> active = plots.findActive(null);
        Map<UUID, YearExpectation> expected = expectedAmounts.yearByVersion(LAND_TAX, year,
                active.stream().map(PlotFacts::id).toList());
        Map<UUID, Money> charged = ledger.chargedInYear(LAND_TAX, year, List.copyOf(expected.keySet()));
        List<PlotLandTax> result = new ArrayList<>();
        for (PlotFacts plot : active) {
            YearExpectation expectation = expected.get(plot.id());
            if (expectation != null) {
                result.add(new PlotLandTax(plot, expectation, charged.getOrDefault(plot.id(), Money.ZERO)));
            }
        }
        return result;
    }

    @Override
    public ReportResult load(ReportFilters filters, ReportContext context) {
        int year = filters.year();

        List<Column> columns = List.of(
                Column.of("number", ReportColumnType.TEXT),
                Column.of("street", ReportColumnType.TEXT),
                Column.of("areaM2", ReportColumnType.DECIMAL),
                Column.of("areaSotka", ReportColumnType.DECIMAL),
                Column.of("rate", ReportColumnType.DECIMAL),
                Column.of("from", ReportColumnType.DATE),
                Column.of("to", ReportColumnType.DATE),
                Column.of("days", ReportColumnType.INTEGER),
                Column.of("expected", ReportColumnType.MONEY),
                Column.of("charged", ReportColumnType.MONEY),
                Column.of("mismatch", ReportColumnType.BOOLEAN));

        List<Row> rows = new ArrayList<>();
        int marked = 0;
        for (PlotLandTax landTax : plots(year)) {
            PlotFacts plot = landTax.plot();
            YearExpectation expectation = landTax.expectation();
            Money chargedForYear = landTax.charged();
            boolean mismatch = !expectation.total().equals(chargedForYear);
            if (mismatch) {
                marked++;
            }
            boolean first = true;
            for (VersionShare share : expectation.shares()) {
                rows.add(Row.detail(List.of(
                        Cell.of(plot.number()),
                        Cell.of(plot.street()),
                        first ? Cell.of(expectation.areaM2()) : Cell.EMPTY,
                        first ? Cell.of(expectation.areaSotka()) : Cell.EMPTY,
                        Cell.of(share.rate().value()),
                        Cell.of(share.from()),
                        Cell.of(share.to()),
                        Cell.of(share.days()),
                        Cell.of(share.amount()),
                        first ? Cell.of(chargedForYear) : Cell.EMPTY,
                        first ? Cell.of(mismatch) : Cell.EMPTY)));
                first = false;
            }
        }

        List<Note> notes = marked == 0 ? List.of()
                : List.of(new Note(ReportNoteCode.RECALCULATION_SUGGESTED, Map.of("count", Integer.toString(marked))));
        return new ReportResult(List.of(new Sheet(MAIN, columns, rows,
                List.of(new Total("all", DebtColumns.totals(columns, rows))))), notes);
    }

    @Override
    public ReportFilters.Period filenameRange(ReportFilters filters, ReportContext context) {
        return new ReportFilters.Period(LocalDate.of(filters.year(), 1, 1), LocalDate.of(filters.year(), 12, 31));
    }
}
