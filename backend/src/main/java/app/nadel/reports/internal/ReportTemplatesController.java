package app.nadel.reports.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.problem.ProblemWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.ReportTemplatesApi;
import nadel.contract.model.Problem;
import nadel.contract.model.ReportTemplate;
import nadel.contract.model.ReportTemplateSlot;
import nadel.contract.model.ReportTemplateUploadResult;
import nadel.contract.model.ReportTemplateVersion;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Endpoints of the report templates (feature 013, functional spec 2.11 and 2.12). The list needs {@code REPORTS_VIEW}
 * or {@code REPORT_TEMPLATES_MANAGE}; uploading, versions, archiving and downloading a version need
 * {@code REPORT_TEMPLATES_MANAGE}. The platform operator holds no cooperative permission and is refused with 403. The
 * permission is checked before anything else, also before the file is read. The work is {@link ReportTemplateService}.
 * <p>
 * A missing form field ({@code file}, {@code name}) is 400 {@code VALIDATION_FAILED} with the field and
 * {@code REQUIRED}, as the contract of these operations says (elsewhere a missing part is {@code MALFORMED_REQUEST}).
 */
@RestController
@RequestMapping("/api/v1")
class ReportTemplatesController implements ReportTemplatesApi {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CurrentUser currentUser;
    private final ReportTemplateService service;

    ReportTemplatesController(CurrentUser currentUser, ReportTemplateService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @Override
    @PreAuthorize("hasAnyAuthority('PERM_REPORTS_VIEW', 'PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<List<ReportTemplate>> listReportTemplates() {
        return ResponseEntity.ok(service.list(currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<ReportTemplateUploadResult> uploadReportTemplate(MultipartFile file, String name,
            @Nullable String description, @Nullable ReportTemplateSlot slot) {
        ReportTemplateUploadResult result = service.upload(currentUser.profile(), file, name, description, slot);
        return ResponseEntity.created(URI.create("/api/v1/report-templates/" + result.getTemplate().getId()
                + "/versions")).body(result);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<List<ReportTemplateVersion>> listReportTemplateVersions(UUID id) {
        return ResponseEntity.ok(service.versions(currentUser.profile(), id));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<ReportTemplateUploadResult> uploadReportTemplateVersion(UUID id, MultipartFile file) {
        ReportTemplateUploadResult result = service.uploadVersion(currentUser.profile(), id, file);
        return ResponseEntity.created(URI.create("/api/v1/report-templates/" + id + "/versions")).body(result);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<ReportTemplate> archiveReportTemplate(UUID id) {
        return ResponseEntity.ok(service.archive(currentUser.profile(), id));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_REPORT_TEMPLATES_MANAGE')")
    public ResponseEntity<Resource> downloadReportTemplateVersion(UUID id, UUID versionId) {
        TemplateFile file = service.download(currentUser.profile(), id, versionId);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.fileName()))
                .body(new ByteArrayResource(file.content()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Problem> missingPart(MissingServletRequestPartException e) {
        return required(e.getRequestPartName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Problem> missingParameter(MissingServletRequestParameterException e) {
        return required(e.getParameterName());
    }

    private static ResponseEntity<Problem> required(String field) {
        // only the two form fields the contract declares; the name of anything else is not echoed
        String known = "file".equals(field) || "name".equals(field) ? field : "request";
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemWriter.body(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                        "The request is not valid.", List.of(new FieldProblem(known, ProblemCodes.REQUIRED))));
    }

    /**
     * {@code attachment} with an ASCII {@code filename} (every other character, quotes and backslashes as {@code _})
     * and the exact name as {@code filename*} in UTF-8 (RFC 6266, RFC 8187).
     */
    static String contentDisposition(String fileName) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        fileName.codePoints().forEach(codePoint -> ascii.append(
                codePoint >= 0x20 && codePoint < 0x7f && codePoint != '"' && codePoint != '\\'
                        ? (char) codePoint : '_'));
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int value = b & 0xff;
            if (isAttrChar(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(value >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** {@code attr-char} of RFC 8187: letters, digits and {@code !#$&+-.^_`|~}. */
    private static boolean isAttrChar(int value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9')
                || "!#$&+-.^_`|~".indexOf(value) >= 0;
    }
}
