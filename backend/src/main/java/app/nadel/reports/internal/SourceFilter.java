package app.nadel.reports.internal;

import app.nadel.ledger.PaymentSource;
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

/** The source of a payment ({@code source}: {@code CASH} or {@code BANK}); left out means both. */
final class SourceFilter implements ReportFilterDefinition {

    static final String KEY = "source";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.SOURCE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.SOURCE);
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
        if (raw.source() != null) {
            into.source(PaymentSource.valueOf(raw.source().getValue()));
        }
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        PaymentSource source = filters.source();
        return texts.text("reports.filters.params.source") + ": " + (source == null
                ? texts.text("reports.filters.all")
                : texts.text("reports.filters.options.source." + source.name()));
    }
}
