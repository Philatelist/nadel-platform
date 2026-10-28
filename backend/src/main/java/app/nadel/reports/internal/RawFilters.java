package app.nadel.reports.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.model.MeterKind;
import nadel.contract.model.MeterResource;
import nadel.contract.model.PaymentSource;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.ReportCorrectionKind;
import nadel.contract.model.WaterSourceKind;
import org.jspecify.annotations.Nullable;

/**
 * The filter query parameters of {@code getReport} and {@code downloadReport} as the contract has read them (formats,
 * enum values and ranges are already checked: a malformed one was 400 {@code VALIDATION_FAILED}). A report reads only
 * the parameters of its filters ({@link ReportFilterDefinition}) and ignores the others.
 */
record RawFilters(
        @Nullable String fromMonth,
        @Nullable String toMonth,
        @Nullable LocalDate from,
        @Nullable LocalDate to,
        @Nullable LocalDate asOf,
        @Nullable Integer year,
        @Nullable List<UUID> chargeTypeIds,
        @Nullable String street,
        @Nullable List<PlotStatus> statuses,
        @Nullable String minDebt,
        @Nullable Integer olderThanDays,
        @Nullable MeterResource resource,
        @Nullable List<MeterKind> meterKinds,
        @Nullable WaterSourceKind waterSource,
        @Nullable List<ReportCorrectionKind> correctionKinds,
        @Nullable PaymentSource source) {

    /** No parameter given: every filter takes its default. */
    static final RawFilters NONE = new RawFilters(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);

    /** The parameters of the reports before the payments report: no {@code source}. */
    RawFilters(@Nullable String fromMonth, @Nullable String toMonth, @Nullable LocalDate from, @Nullable LocalDate to,
            @Nullable LocalDate asOf, @Nullable Integer year, @Nullable List<UUID> chargeTypeIds,
            @Nullable String street, @Nullable List<PlotStatus> statuses, @Nullable String minDebt,
            @Nullable Integer olderThanDays, @Nullable MeterResource resource, @Nullable List<MeterKind> meterKinds,
            @Nullable WaterSourceKind waterSource, @Nullable List<ReportCorrectionKind> correctionKinds) {
        this(fromMonth, toMonth, from, to, asOf, year, chargeTypeIds, street, statuses, minDebt, olderThanDays,
                resource, meterKinds, waterSource, correctionKinds, null);
    }
}
