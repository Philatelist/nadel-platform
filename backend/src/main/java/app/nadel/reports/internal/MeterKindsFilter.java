package app.nadel.reports.internal;

import app.nadel.meters.MeterKind;
import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of meter a report lists ({@code meterKinds}: {@code PLOT}, {@code SUBSTATION}, {@code QUARTER},
 * {@code COMMON_CONSUMER}, several); left out means plot meters only, the filter's default. A repeated value counts
 * once.
 */
final class MeterKindsFilter implements ReportFilterDefinition {

    static final String KEY = "meterKinds";

    /** Plot meters: the meters whose owners the board asks for readings. */
    static final List<MeterKind> DEFAULT = List.of(MeterKind.PLOT);

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.METER_KIND;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.METER_KINDS);
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
        return DEFAULT.stream().map(kind -> new ReportFilterValue(ReportFilterParam.METER_KINDS, kind.name()))
                .toList();
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        if (raw.meterKinds() == null || raw.meterKinds().isEmpty()) {
            into.meterKinds(DEFAULT);
        } else {
            into.meterKinds(raw.meterKinds().stream().map(kind -> MeterKind.valueOf(kind.getValue())).toList());
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.params.meterKinds") + ": " + filters.meterKinds().stream()
                .map(kind -> texts.text("reports.filters.options.meterKinds." + kind.name()))
                .collect(Collectors.joining(", "));
    }
}
