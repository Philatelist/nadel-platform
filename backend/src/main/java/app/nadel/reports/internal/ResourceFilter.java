package app.nadel.reports.internal;

import app.nadel.meters.MeterResource;
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

/** The meters of one resource ({@code resource}: {@code ELECTRICITY} or {@code WATER}); left out means both. */
final class ResourceFilter implements ReportFilterDefinition {

    static final String KEY = "resource";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.RESOURCE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.RESOURCE);
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
        if (raw.resource() != null) {
            into.resource(MeterResource.valueOf(raw.resource().getValue()));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        MeterResource resource = filters.resource();
        return texts.text("reports.filters.params.resource") + ": " + (resource == null
                ? texts.text("reports.filters.all")
                : texts.text("reports.filters.options.resource." + resource.name()));
    }
}
