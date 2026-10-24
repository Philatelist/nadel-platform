package app.nadel.reports.internal;

import app.nadel.money.Money;
import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * A debt above an amount ({@code minDebt}, two decimals), always set: left out it is the default the report gives, the
 * cooperative's current red amount for the debtors (read when the list or the report is made, inside its
 * transaction). The contract has already checked the format ({@code ^\d{1,12}\.\d{2}$}), so the value is never
 * negative.
 */
final class MinDebtFilter implements ReportFilterDefinition {

    static final String KEY = "minDebt";

    private final Supplier<Money> fallback;

    MinDebtFilter(Supplier<Money> fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.MONEY;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.MIN_DEBT);
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
        return List.of(new ReportFilterValue(ReportFilterParam.MIN_DEBT, fallback.get().toString()));
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        into.minDebt(raw.minDebt() == null ? fallback.get() : Money.parse(raw.minDebt()));
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        return texts.text("reports.filters.params.minDebt") + ": " + filters.minDebt();
    }
}
