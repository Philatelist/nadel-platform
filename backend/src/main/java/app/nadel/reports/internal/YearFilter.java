package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * A calendar year ({@code year}), always required, the current year in Europe/Kyiv by default. The contract keeps it
 * between 2000 and 2100 (anything else is 400 {@code VALIDATION_FAILED}); a year after the current one is
 * {@code INVALID}: nothing can have been charged for it yet, so every plot would differ from what is expected.
 */
final class YearFilter implements ReportFilterDefinition {

    static final String KEY = "year";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.YEAR;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.YEAR);
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public @Nullable ReportPeriodBasis basis() {
        return null;
    }

    @Override
    public List<ReportFilterValue> defaults(LocalDate today) {
        return List.of(new ReportFilterValue(ReportFilterParam.YEAR, Integer.toString(today.getYear())));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        int year = raw.year() == null ? context.today().getYear() : raw.year();
        if (year > context.today().getYear()) {
            errors.add(new FieldProblem(ReportFilterParam.YEAR.getValue(), "INVALID"));
            return;
        }
        into.year(year);
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.params.year") + ": " + filters.year();
    }
}
