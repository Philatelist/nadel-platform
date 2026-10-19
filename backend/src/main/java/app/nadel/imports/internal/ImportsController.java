package app.nadel.imports.internal;

import nadel.contract.api.ImportsApi;
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
 * Endpoints of the Excel import. All of them need {@code IMPORT_RUN}; the platform operator holds no cooperative
 * permissions and is refused with 403.
 */
@RestController
@RequestMapping("/api/v1")
class ImportsController implements ImportsApi {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final String TEMPLATE_FILE_NAME = "nadel-import-template.xlsx";

    private final TemplateWriter templateWriter = new TemplateWriter();

    @Override
    @PreAuthorize("hasAuthority('PERM_IMPORT_RUN')")
    public ResponseEntity<Resource> downloadImportTemplate() {
        byte[] template = templateWriter.write();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(template.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(TEMPLATE_FILE_NAME).build().toString())
                .body(new ByteArrayResource(template));
    }
}
