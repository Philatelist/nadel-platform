package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.util.LinkedHashSet;
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
 * The charge types a report counts ({@code chargeTypeIds}, several); left out means every type. An id that is not a
 * charge type of the cooperative (archived types count) is {@code NOT_FOUND} on {@code chargeTypeIds[i]}; a repeated
 * id counts once.
 */
final class ChargeTypesFilter implements ReportFilterDefinition {

    static final String KEY = "chargeTypes";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.CHARGE_TYPES;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.CHARGE_TYPE_IDS);
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
        List<UUID> given = raw.chargeTypeIds();
        if (given == null || given.isEmpty()) {
            return;
        }
        Set<UUID> known = context.chargeTypes().stream().map(ChargeTypeFacts::id).collect(Collectors.toSet());
        boolean valid = true;
        for (int index = 0; index < given.size(); index++) {
            if (!known.contains(given.get(index))) {
                errors.add(new FieldProblem(ReportFilterParam.CHARGE_TYPE_IDS.getValue() + "[" + index + "]",
                        "NOT_FOUND"));
                valid = false;
            }
        }
        if (valid) {
            into.chargeTypeIds(List.copyOf(new LinkedHashSet<>(given)));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        List<UUID> chosen = filters.chargeTypeIds();
        String value = chosen.isEmpty() ? texts.text("reports.filters.all")
                : chosen.stream().map(chargeTypeNames).collect(Collectors.joining(", "));
        return texts.text("reports.filters.chargeTypes") + ": " + value;
    }
}
