package app.nadel.reports.internal;

import app.nadel.plots.PlotStatus;
import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * Only the plots of some status colours ({@code statuses}, several); left out means every status. The definition
 * applies it after the status was worked out, so it filters the rows, never the amounts of the rows it keeps. A
 * repeated value counts once.
 */
final class StatusesFilter implements ReportFilterDefinition {

    static final String KEY = "statuses";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.STATUS;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.STATUSES);
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public @Nullable ReportPeriodBasis basis() {
        return null;
    }

    @Override
    public List<ReportFilterValue> defaults(LocalDate today) {
        return List.of();
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        if (raw.statuses() != null && !raw.statuses().isEmpty()) {
            into.statuses(raw.statuses().stream().map(status -> PlotStatus.valueOf(status.getValue())).toList());
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        Set<PlotStatus> chosen = filters.statuses();
        String value = chosen.isEmpty() ? texts.text("reports.filters.all")
                : chosen.stream().map(status -> texts.text("reports.filters.options.statuses." + status.name()))
                        .collect(Collectors.joining(", "));
        return texts.text("reports.filters.params.statuses") + ": " + value;
    }
}
