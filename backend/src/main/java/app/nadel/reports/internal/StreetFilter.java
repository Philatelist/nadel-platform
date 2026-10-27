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
 * Only the plots of one street ({@code street}); left out or blank means every street. The definition compares it
 * with the plot's street as stored, ignoring case and surrounding spaces ({@code plots.PlotCatalog#idsOnStreet}).
 */
final class StreetFilter implements ReportFilterDefinition {

    static final String KEY = "street";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.STREET;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.STREET);
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
        if (raw.street() != null && !raw.street().isBlank()) {
            into.street(raw.street().strip());
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        String street = filters.street();
        return texts.text("reports.filters.street") + ": " + (street == null ? texts.text("reports.filters.all")
                : street);
    }
}
