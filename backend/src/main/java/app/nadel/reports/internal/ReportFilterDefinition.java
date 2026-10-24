package app.nadel.reports.internal;

import app.nadel.problem.FieldProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportFilter;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterType;
import nadel.contract.model.ReportFilterValue;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;

/**
 * One filter of a report: what {@code GET /reports} says about it, how it reads its query parameters into
 * {@link ReportFilters} (with its default when a parameter is left out) and how the file's header block names it.
 * Implementations are immutable and shared by the definitions.
 */
interface ReportFilterDefinition {

    /** Names the filter in the client's catalogs ({@code period}, {@code chargeTypes}, {@code street}, ...). */
    String key();

    ReportFilterType type();

    /** The query parameters the filter fills, in order. */
    List<ReportFilterParam> params();

    /** True when the report cannot run without the filter. */
    boolean required();

    /** What a period means; null for a filter that is not a period. */
    @Nullable ReportPeriodBasis basis();

    /** The values the filter starts with on {@code today} (Europe/Kyiv); empty when it starts empty (all). */
    List<ReportFilterValue> defaults(LocalDate today);

    /**
     * Reads the filter's parameters, or its defaults for the ones left out, into {@code into}; a value that does not
     * fit the report adds a field error naming the parameter ({@code REQUIRED}, {@code INVALID}, {@code NOT_FOUND}).
     */
    void apply(RawFilters raw, ReportContext context, ReportFilters into, List<FieldProblem> errors);

    /** The filter as the header block of the file shows it: {@code <label>: <value>}. */
    String describe(ReportFilters filters, ReportTexts texts, Function<UUID, String> chargeTypeNames);

    /** The filter as {@code GET /reports} lists it. */
    default ReportFilter info(LocalDate today) {
        return new ReportFilter(key(), type(), params(), required(), defaults(today)).basis(basis());
    }
}
