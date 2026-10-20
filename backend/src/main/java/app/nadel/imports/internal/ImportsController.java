package app.nadel.imports.internal;

import app.nadel.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import nadel.contract.api.ImportsApi;
import nadel.contract.model.ImportPage;
import nadel.contract.model.ImportPreview;
import nadel.contract.model.ImportResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoints of the Excel import. All of them need {@code IMPORT_RUN}; the platform operator holds no cooperative
 * permissions and is refused with 403.
 */
@RestController
@RequestMapping("/api/v1")
class ImportsController implements ImportsApi {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TemplateWriter templateWriter = new TemplateWriter();
    private final CurrentUser currentUser;
    private final ImportService service;
    private final ImportCommitter committer;
    private final ImportHistory history;
    private final HttpServletRequest request;

    ImportsController(CurrentUser currentUser, ImportService service, ImportCommitter committer,
            ImportHistory history, HttpServletRequest request) {
        this.request = request;
        this.currentUser = currentUser;
        this.service = service;
        this.committer = committer;
        this.history = history;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")
    public ResponseEntity<Resource> downloadImportTemplate(String lang) {
        TemplateLanguage language = TemplateLanguage.resolve(lang, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        byte[] template = templateWriter.write(language);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(template.length)
                .varyBy(HttpHeaders.ACCEPT_LANGUAGE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(language.fileName()).build().toString())
                .body(new ByteArrayResource(template));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")
    public ResponseEntity<ImportPreview> uploadImport(MultipartFile file) {
        ImportPreview preview = service.preview(file, currentUser.profile());
        return ResponseEntity.created(URI.create("/api/v1/imports/" + preview.getId())).body(preview);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")
    public ResponseEntity<ImportResult> commitImport(UUID id) {
        return ResponseEntity.ok(committer.commit(id, currentUser.profile()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")
    public ResponseEntity<ImportPage> listImports(Integer page, Integer size) {
        // The cooperative comes from the caller's profile (the token), never from the request.
        return ResponseEntity.ok(history.list(currentUser.profile().tenantId(), page, size));
    }
}
