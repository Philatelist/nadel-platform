package app.nadel.reports.internal;

import app.nadel.meters.WaterSourceKind;
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
 * The kind of water source ({@code waterSource}: {@code METER}, every well, or {@code COOPERATIVE_SUPPLY}, the
 * cooperative's water supply); left out means every source.
 */
final class WaterSourceFilter implements ReportFilterDefinition {

    static final String KEY = "waterSource";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.WATER_SOURCE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.WATER_SOURCE);
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
        if (raw.waterSource() != null) {
            into.waterSource(WaterSourceKind.valueOf(raw.waterSource().getValue()));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        WaterSourceKind source = filters.waterSource();
        return texts.text("reports.filters.params.waterSource") + ": " + (source == null
                ? texts.text("reports.filters.all")
                : texts.text("reports.filters.options.waterSource." + source.name()));
    }
}
