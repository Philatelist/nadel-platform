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

/**
 * The days payments were received, {@code from} to {@code to} (both included, Europe/Kyiv), as an alternative to the
 * billing months of the payments report: optional and empty by default. Given, they choose the payments instead of the
 * months; left out, the months choose. Only one of the two given is {@code REQUIRED} on the other; {@code to} before
 * {@code from}, or a range of more than one year, is {@code INVALID} on {@code to}, as for {@link DateRangeFilter}.
 */
final class PaymentDaysFilter implements ReportFilterDefinition {

    static final String KEY = "received";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public ReportFilterType type() {
        return ReportFilterType.DATE_RANGE;
    }

    @Override
    public List<ReportFilterParam> params() {
        return List.of(ReportFilterParam.FROM, ReportFilterParam.TO);
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ReportPeriodBasis basis() {
        return ReportPeriodBasis.PAYMENT_DATES;
    }

    @Override
    public List<ReportFilterValue> defaults(LocalDate today) {
        return List.of();
    }

    @Override
    public void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors) {
        LocalDate from = raw.from();
        LocalDate to = raw.to();
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            ReportFilterParam missing = from == null ? ReportFilterParam.FROM : ReportFilterParam.TO;
            errors.add(new FieldProblem(missing.getValue(), "REQUIRED"));
            return;
        }
        if (to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            errors.add(new FieldProblem(ReportFilterParam.TO.getValue(), "INVALID"));
            return;
        }
        into.days(from, to);
    }

    @Override
    public String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        String label = texts.text("reports.filters.period." + ReportPeriodBasis.PAYMENT_DATES.getValue()) + ": ";
        return filters.hasDays() ? label + ReportTexts.day(filters.from()) + " – " + ReportTexts.day(filters.to())
                : label + texts.text("reports.filters.all");
    }
}
