package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;

/**
 * The period of a report as a range of days, {@code from} to {@code to} (both included, Europe/Kyiv), always required.
 * A parameter left out takes its default, the first or the last day of the previous calendar month. {@code to} before
 * {@code from} is {@code INVALID} on {@code to}, and so is a range of more than one year ({@code to} on or after the
 * same day a year after {@code from}): a year is the longest period a board reports on, and it bounds the work of the
 * meter tree over the range.
 */
final class DateRangeFilter implements ReportFilterDefinition {

    static final String KEY = "period";

    private final ReportPeriodBasis basis;

    DateRangeFilter(ReportPeriodBasis basis) {
        this.basis = Objects.requireNonNull(basis, "basis");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.DATE_RANGE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.FROM, ReportFilterParam.TO);
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public ReportPeriodBasis basis() {
        return basis;
    }

    @Override
    public List<ReportFilterValue> defaults(LocalDate today) {
        YearMonth previous = YearMonth.from(today).minusMonths(1);
        return List.of(new ReportFilterValue(ReportFilterParam.FROM, previous.atDay(1).toString()),
                new ReportFilterValue(ReportFilterParam.TO, previous.atEndOfMonth().toString()));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        YearMonth previous = YearMonth.from(context.today()).minusMonths(1);
        LocalDate from = raw.from() == null ? previous.atDay(1) : raw.from();
        LocalDate to = raw.to() == null ? previous.atEndOfMonth() : raw.to();
        if (to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            errors.add(new FieldProblem(ReportFilterParam.TO.getValue(), "INVALID"));
            return;
        }
        into.days(from, to);
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.period." + basis.getValue()) + ": " + ReportTexts.day(filters.from())
                + " – " + ReportTexts.day(filters.to());
    }
}
