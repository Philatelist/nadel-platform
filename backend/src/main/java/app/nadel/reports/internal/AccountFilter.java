package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import app.nadel.treasury.MoneyAccountKind;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/** The money account of an entry ({@code account}: {@code CASH} or {@code BANK}, feature 018); left out means both. */
final class AccountFilter implements ReportFilterDefinition {

    static final String KEY = "account";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.ACCOUNT;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.ACCOUNT);
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
        if (raw.account() != null) {
            into.account(MoneyAccountKind.valueOf(raw.account().getValue()));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> names) {
        MoneyAccountKind account = filters.account();
        return texts.text("reports.filters.params.account") + ": " + (account == null
                ? texts.text("reports.filters.all")
                : texts.text("reports.filters.options.account." + account.name()));
    }
}
