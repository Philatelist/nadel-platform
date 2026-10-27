package app.nadel.reports.internal;

import app.nadel.ledger.CorrectionKind;
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
 * The kinds of correction a report lists ({@code correctionKinds}: {@code REVERSAL}, {@code ADJUSTMENT}, several); left
 * out means both. A repeated value counts once.
 */
final class CorrectionKindsFilter implements ReportFilterDefinition {

    static final String KEY = "correctionKinds";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.CORRECTION_KIND;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.CORRECTION_KINDS);
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
        if (raw.correctionKinds() != null && !raw.correctionKinds().isEmpty()) {
            into.correctionKinds(raw.correctionKinds().stream().map(kind -> CorrectionKind.valueOf(kind.getValue()))
                    .toList());
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        Set<CorrectionKind> chosen = filters.correctionKinds();
        String value = chosen.isEmpty() ? texts.text("reports.filters.all")
                : chosen.stream().map(kind -> texts.text("reports.filters.options.correctionKinds." + kind.name()))
                        .collect(Collectors.joining(", "));
        return texts.text("reports.filters.params.correctionKinds") + ": " + value;
    }
}
