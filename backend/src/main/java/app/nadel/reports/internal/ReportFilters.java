package app.nadel.reports.internal;

import app.nadel.ledger.CorrectionKind;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.WaterSourceKind;
import app.nadel.money.Money;
import app.nadel.plots.PlotStatus;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportFilterParam;
import nadel.contract.model.ReportFilterValue;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The filters of one run of a report, read from the query parameters by the report's
 * {@link ReportFilterDefinition}s with the defaults filled in. Holds the typed values the definition loads with and
 * the values as the query string carries them ({@link #values()}): the preview echoes those and the audit entry
 * records them (ids, codes and dates only, never contents).
 */
final class ReportFilters {

    /** The days a report covers, both included: the period of its file name. */
    record Period(LocalDate from, LocalDate to) {
    }

    private @Nullable YearMonth fromMonth;
    private @Nullable YearMonth toMonth;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private List<UUID> chargeTypeIds = List.of();
    private @Nullable String street;
    private @Nullable LocalDate asOf;
    private Set<PlotStatus> statuses = Set.of();
    private @Nullable Money minDebt;
    private @Nullable Integer olderThanDays;
    private @Nullable Integer year;
    private Set<CorrectionKind> correctionKinds = Set.of();
    private @Nullable MeterResource resource;
    private Set<MeterKind> meterKinds = Set.of();
    private @Nullable WaterSourceKind waterSource;
    private final List<ReportFilterValue> values = new ArrayList<>();

    ReportFilters() {
    }

    /**
     * Reads the filters of a report.
     *
     * @throws ApiProblemException 400 {@code REPORT_FILTER_INVALID} with every field error when a filter does not
     *                             fit the report
     */
    static ReportFilters parse(List<ReportFilterDefinition> definitions, RawFilters raw, ReportContext context) {
        ReportFilters filters = new ReportFilters();
        List<FieldProblem> errors = new ArrayList<>();
        for (ReportFilterDefinition definition : definitions) {
            definition.apply(raw, context, filters, errors);
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.REPORT_FILTER_INVALID,
                    "The filters do not fit the report.", errors);
        }
        return filters;
    }

    // ---- set by the filter definitions

    void months(YearMonth from, YearMonth to) {
        this.fromMonth = Objects.requireNonNull(from, "from");
        this.toMonth = Objects.requireNonNull(to, "to");
        values.add(new ReportFilterValue(ReportFilterParam.FROM_MONTH, from.toString()));
        values.add(new ReportFilterValue(ReportFilterParam.TO_MONTH, to.toString()));
    }

    void days(LocalDate from, LocalDate to) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        values.add(new ReportFilterValue(ReportFilterParam.FROM, from.toString()));
        values.add(new ReportFilterValue(ReportFilterParam.TO, to.toString()));
    }

    void chargeTypeIds(List<UUID> ids) {
        this.chargeTypeIds = List.copyOf(ids);
        ids.forEach(id -> values.add(new ReportFilterValue(ReportFilterParam.CHARGE_TYPE_IDS, id.toString())));
    }

    void street(String street) {
        this.street = Objects.requireNonNull(street, "street");
        values.add(new ReportFilterValue(ReportFilterParam.STREET, street));
    }

    void asOf(LocalDate asOf) {
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        values.add(new ReportFilterValue(ReportFilterParam.AS_OF, asOf.toString()));
    }

    void statuses(List<PlotStatus> statuses) {
        Set<PlotStatus> chosen = new LinkedHashSet<>(statuses);
        this.statuses = Collections.unmodifiableSet(chosen);
        chosen.forEach(status -> values.add(new ReportFilterValue(ReportFilterParam.STATUSES, status.name())));
    }

    void minDebt(Money minDebt) {
        this.minDebt = Objects.requireNonNull(minDebt, "minDebt");
        values.add(new ReportFilterValue(ReportFilterParam.MIN_DEBT, minDebt.toString()));
    }

    void olderThanDays(int days) {
        this.olderThanDays = days;
        values.add(new ReportFilterValue(ReportFilterParam.OLDER_THAN_DAYS, Integer.toString(days)));
    }

    void year(int year) {
        this.year = year;
        values.add(new ReportFilterValue(ReportFilterParam.YEAR, Integer.toString(year)));
    }

    void correctionKinds(List<CorrectionKind> kinds) {
        Set<CorrectionKind> chosen = new LinkedHashSet<>(kinds);
        this.correctionKinds = Collections.unmodifiableSet(chosen);
        chosen.forEach(kind -> values.add(new ReportFilterValue(ReportFilterParam.CORRECTION_KINDS, kind.name())));
    }

    void resource(MeterResource resource) {
        this.resource = Objects.requireNonNull(resource, "resource");
        values.add(new ReportFilterValue(ReportFilterParam.RESOURCE, resource.name()));
    }

    void meterKinds(List<MeterKind> kinds) {
        Set<MeterKind> chosen = new LinkedHashSet<>(kinds);
        this.meterKinds = Collections.unmodifiableSet(chosen);
        chosen.forEach(kind -> values.add(new ReportFilterValue(ReportFilterParam.METER_KINDS, kind.name())));
    }

    void waterSource(WaterSourceKind source) {
        this.waterSource = Objects.requireNonNull(source, "source");
        values.add(new ReportFilterValue(ReportFilterParam.WATER_SOURCE, source.name()));
    }

    // ---- read by the report definitions

    /** The first billing month; only for a report with a month range. */
    YearMonth fromMonth() {
        return required(fromMonth, "month range");
    }

    /** The last billing month; only for a report with a month range. */
    YearMonth toMonth() {
        return required(toMonth, "month range");
    }

    /** The first day of the range; only for a report with a range of days. */
    LocalDate from() {
        return required(from, "range of days");
    }

    /** The last day of the range; only for a report with a range of days. */
    LocalDate to() {
        return required(to, "range of days");
    }

    /** The chosen charge types in the order given; empty means every type. */
    List<UUID> chargeTypeIds() {
        return chargeTypeIds;
    }

    /** The chosen street without surrounding spaces; null when every street counts. */
    @Nullable String street() {
        return street;
    }

    /** The day the report is made for; only for a report with an as-of filter. */
    LocalDate asOf() {
        return required(asOf, "as-of");
    }

    /** The chosen status colours in the order given; empty means every status. */
    Set<PlotStatus> statuses() {
        return statuses;
    }

    /** A debt above this amount matches; only for a report with a minimum debt filter. */
    Money minDebt() {
        return required(minDebt, "minimum debt");
    }

    /** A debt older than this many days matches; only for a report with an age filter. */
    int olderThanDays() {
        return required(olderThanDays, "age");
    }

    /** The calendar year; only for a report with a year filter. */
    int year() {
        return required(year, "year");
    }

    /** The chosen kinds of correction in the order given; empty means both. */
    Set<CorrectionKind> correctionKinds() {
        return correctionKinds;
    }

    /** The chosen resource; null when both count. */
    @Nullable MeterResource resource() {
        return resource;
    }

    /** The chosen meter kinds in the order given; empty means the filter's default was not applied (no filter). */
    Set<MeterKind> meterKinds() {
        return meterKinds;
    }

    /** The chosen kind of water source; null when every source counts. */
    @Nullable WaterSourceKind waterSource() {
        return waterSource;
    }

    /**
     * The days the report covers: a range of days as given; a month range runs from the first day of its first to the
     * last of its last month.
     */
    Period period() {
        if (from != null && to != null) {
            return new Period(from, to);
        }
        return new Period(fromMonth().atDay(1), toMonth().atEndOfMonth());
    }

    /** The values used, as the query string carries them, in the order of the filters. */
    List<ReportFilterValue> values() {
        return values.stream().map(value -> new ReportFilterValue(value.getParam(), value.getValue())).toList();
    }

    private static <T> T required(@Nullable T value, String filter) {
        if (value == null) {
            throw new IllegalStateException("The report has no " + filter + " filter");
        }
        return value;
    }
}
