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
import org.jspecify.annotations.Nullable;

/**
 * The period of a report as a range of months, {@code fromMonth} to {@code toMonth} (both included), always required.
 * A parameter left out takes the default, the previous calendar month in Europe/Kyiv, when the filter has one;
 * without a default it is {@code REQUIRED}. {@code toMonth} before {@code fromMonth} is {@code INVALID} on
 * {@code toMonth}.
 */
final class MonthRangeFilter implements ReportFilterDefinition {

    static final String KEY = "period";

    private final ReportPeriodBasis basis;
    private final boolean previousMonthByDefault;

    private MonthRangeFilter(ReportPeriodBasis basis, boolean previousMonthByDefault) {
        this.basis = Objects.requireNonNull(basis, "basis");
        this.previousMonthByDefault = previousMonthByDefault;
    }

    /** A month range that starts as the previous calendar month. */
    static MonthRangeFilter previousMonth(ReportPeriodBasis basis) {
        return new MonthRangeFilter(basis, true);
    }

    /** A month range without a default: both months must be given. */
    static MonthRangeFilter withoutDefault(ReportPeriodBasis basis) {
        return new MonthRangeFilter(basis, false);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.MONTH_RANGE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.FROM_MONTH, ReportFilterParam.TO_MONTH);
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
        YearMonth month = defaultMonth(today);
        if (month == null) {
            return List.of();
        }
        return List.of(new ReportFilterValue(ReportFilterParam.FROM_MONTH, month.toString()),
                new ReportFilterValue(ReportFilterParam.TO_MONTH, month.toString()));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        YearMonth fallback = defaultMonth(context.today());
        YearMonth from = raw.fromMonth() == null ? fallback : YearMonth.parse(raw.fromMonth());
        YearMonth to = raw.toMonth() == null ? fallback : YearMonth.parse(raw.toMonth());
        if (from == null) {
            errors.add(new FieldProblem(ReportFilterParam.FROM_MONTH.getValue(), "REQUIRED"));
        }
        if (to == null) {
            errors.add(new FieldProblem(ReportFilterParam.TO_MONTH.getValue(), "REQUIRED"));
        }
        if (from == null || to == null) {
            return;
        }
        if (to.isBefore(from)) {
            errors.add(new FieldProblem(ReportFilterParam.TO_MONTH.getValue(), "INVALID"));
            return;
        }
        into.months(from, to);
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.period." + basis.getValue()) + ": "
                + ReportTexts.month(filters.fromMonth()) + " – " + ReportTexts.month(filters.toMonth());
    }

    private @Nullable YearMonth defaultMonth(LocalDate today) {
        return previousMonthByDefault ? YearMonth.from(today).minusMonths(1) : null;
    }
}
