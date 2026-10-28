package app.nadel.reports.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.ReportsApi;
import nadel.contract.model.MeterKind;
import nadel.contract.model.MeterResource;
import nadel.contract.model.PaymentSource;
import nadel.contract.model.PlotStatus;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportCorrectionKind;
import nadel.contract.model.ReportInfo;
import nadel.contract.model.ReportPreview;
import nadel.contract.model.WaterSourceKind;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints of the built-in reports. Every one needs {@code REPORTS_VIEW}; a report that needs more (the debtors also
 * {@code MEMBERS_VIEW}, the payments also {@code PAYMENTS_VIEW}) is not listed and is 403 ({@link ReportCatalog}). The
 * platform operator holds no cooperative permission and is refused with 403. A code of the contract without a
 * definition yet is 404. {@code /reports/{code}.xlsx} is matched before {@code /reports/{code}}: its pattern is the
 * more specific one.
 */
@RestController
@RequestMapping("/api/v1")
class ReportsController implements ReportsApi {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportRunner runner;
    private final HttpServletRequest request;

    ReportsController(ReportRunner runner, HttpServletRequest request) {
        this.runner = runner;
        this.request = request;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
    public ResponseEntity<List<ReportInfo>> listReports() {
        return ResponseEntity.ok(runner.list());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
    public ResponseEntity<ReportPreview> getReport(ReportCode code, @Nullable String fromMonth,
            @Nullable String toMonth, @Nullable LocalDate from, @Nullable LocalDate to, @Nullable LocalDate asOf,
            @Nullable Integer year, @Nullable List<UUID> chargeTypeIds, @Nullable String street,
            @Nullable List<PlotStatus> statuses, @Nullable String minDebt, @Nullable Integer olderThanDays,
            @Nullable MeterResource resource, @Nullable List<MeterKind> meterKinds,
            @Nullable WaterSourceKind waterSource, @Nullable List<ReportCorrectionKind> correctionKinds,
            @Nullable PaymentSource source) {
        RawFilters raw = new RawFilters(fromMonth, toMonth, from, to, asOf, year, chargeTypeIds, street, statuses,
                minDebt, olderThanDays, resource, meterKinds, waterSource, correctionKinds, source);
        return ResponseEntity.ok(runner.preview(code, raw));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
    public ResponseEntity<Resource> downloadReport(ReportCode code, @Nullable String fromMonth,
            @Nullable String toMonth, @Nullable LocalDate from, @Nullable LocalDate to, @Nullable LocalDate asOf,
            @Nullable Integer year, @Nullable List<UUID> chargeTypeIds, @Nullable String street,
            @Nullable List<PlotStatus> statuses, @Nullable String minDebt, @Nullable Integer olderThanDays,
            @Nullable MeterResource resource, @Nullable List<MeterKind> meterKinds,
            @Nullable WaterSourceKind waterSource, @Nullable List<ReportCorrectionKind> correctionKinds,
            @Nullable PaymentSource source, @Nullable String lang) {
        RawFilters raw = new RawFilters(fromMonth, toMonth, from, to, asOf, year, chargeTypeIds, street, statuses,
                minDebt, olderThanDays, resource, meterKinds, waterSource, correctionKinds, source);
        ReportLanguage language = ReportLanguage.resolve(lang, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        ReportFile file = runner.file(code, raw, language);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(file.content().length)
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(new ByteArrayResource(file.content()));
    }
}
