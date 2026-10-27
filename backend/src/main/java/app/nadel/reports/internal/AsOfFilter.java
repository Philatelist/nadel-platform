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
 * The day a report is made for ({@code asOf}), always required, today in Europe/Kyiv by default. A day after today is
 * {@code INVALID}: a balance on a day to come would count charges not yet due and an age not yet reached.
 */
final class AsOfFilter implements ReportFilterDefinition {

    static final String KEY = "asOf";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.DATE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.AS_OF);
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
        return List.of(new ReportFilterValue(ReportFilterParam.AS_OF, today.toString()));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        LocalDate asOf = raw.asOf() == null ? context.today() : raw.asOf();
        if (asOf.isAfter(context.today())) {
            errors.add(new FieldProblem(ReportFilterParam.AS_OF.getValue(), "INVALID"));
            return;
        }
        into.asOf(asOf);
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.params.asOf") + ": " + ReportTexts.day(filters.asOf());
    }
}
