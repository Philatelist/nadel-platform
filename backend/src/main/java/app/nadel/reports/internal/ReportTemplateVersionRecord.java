package app.nadel.reports.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One uploaded file of a report template (table {@code report_template_version}, V20). Append-only: inserted once,
 * never changed or deleted ({@code nadel_app} has neither {@code UPDATE} nor {@code DELETE}, and a guard trigger stops
 * everyone else), so the entity is {@link Immutable}.
 * <p>
 * The entity is only ever used to insert a version. Every read goes through a projection of
 * {@link ReportTemplateVersionRepository} that leaves {@code content} out ({@link TemplateVersionRow}); the file
 * itself is read only by the download, by id. Without bytecode enhancement a lazy basic attribute would still be
 * loaded, so the projection is the guarantee that a list never loads the files.
 */
@Entity
@Immutable
@Table(name = "report_template_version")
class ReportTemplateVersionRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by", updatable = false)
    private @Nullable UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected ReportTemplateVersionRecord() {
    }

    /** A new version of the template; size and digest are those of {@code content}, as the stored file. */
    static ReportTemplateVersionRecord create(UUID tenantId, UUID templateId, int versionNo,
            TemplateUploadChecks.Checked file, @Nullable UUID uploadedBy, Instant now) {
        ReportTemplateVersionRecord version = new ReportTemplateVersionRecord();
        version.id = UUID.randomUUID();
        version.tenantId = tenantId;
        version.templateId = templateId;
        version.versionNo = versionNo;
        version.fileName = file.fileName();
        version.content = file.content();
        version.sizeBytes = file.content().length;
        version.sha256 = file.sha256();
        version.uploadedBy = uploadedBy;
        version.uploadedAt = now;
        return version;
    }

    /** The columns a list shows, without the file. */
    TemplateVersionRow toRow() {
        return new TemplateVersionRow(id, templateId, versionNo, fileName, sizeBytes, sha256, uploadedBy, uploadedAt);
    }
}
