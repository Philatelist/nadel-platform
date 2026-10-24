package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * A debt older than a number of days ({@code olderThanDays}, 0 to 36 500 as the contract checks), always set: left out
 * it is the default the report gives, the cooperative's current red days for the debtors (read when the list or the
 * report is made, inside its transaction).
 */
final class OlderThanDaysFilter implements ReportFilterDefinition {

    static final String KEY = "olderThanDays";

    private final IntSupplier fallback;

    OlderThanDaysFilter(IntSupplier fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.INT;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.OLDER_THAN_DAYS);
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
        return List.of(new ReportFilterValue(ReportFilterParam.OLDER_THAN_DAYS,
                Integer.toString(fallback.getAsInt())));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        into.olderThanDays(raw.olderThanDays() == null ? fallback.getAsInt() : raw.olderThanDays());
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.params.olderThanDays") + ": " + filters.olderThanDays();
    }
}
