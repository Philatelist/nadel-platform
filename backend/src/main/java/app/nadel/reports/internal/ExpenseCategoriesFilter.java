package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.LinkedHashSet;
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
 * The expense categories a report counts ({@code expenseCategoryIds}, several, feature 018); left out means every
 * category; a repeated id counts once. The ids are checked by the report that reads them ({@link ExpensesReport}:
 * {@code NOT_FOUND} on {@code expenseCategoryIds[i]} for an id that is not an expense category of the cooperative),
 * because the categories are the treasury's and the filter reads no data.
 */
final class ExpenseCategoriesFilter implements ReportFilterDefinition {

    static final String KEY = "expenseCategories";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.EXPENSE_CATEGORIES;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.EXPENSE_CATEGORY_IDS);
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
        List<UUID> given = raw.expenseCategoryIds();
        if (given != null && !given.isEmpty()) {
            into.expenseCategoryIds(List.copyOf(new LinkedHashSet<>(given)));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> names) {
        List<UUID> chosen = filters.expenseCategoryIds();
        String value = chosen.isEmpty() ? texts.text("reports.filters.all")
                : chosen.stream().map(names).collect(Collectors.joining(", "));
        return texts.text("reports.filters.expenseCategories") + ": " + value;
    }
}
