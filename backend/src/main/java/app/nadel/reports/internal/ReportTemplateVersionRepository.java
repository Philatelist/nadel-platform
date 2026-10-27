package app.nadel.reports.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The versions of the templates. Deliberately a plain {@link Repository} with no {@code findAll} or {@code findById}:
 * loading a {@link ReportTemplateVersionRecord} would load its file, so every read is a projection without
 * {@code content} ({@link TemplateVersionRow}), except {@link #findFile}, the download. Every query names the
 * cooperative on top of row level security.
 */
interface ReportTemplateVersionRepository extends Repository<ReportTemplateVersionRecord, UUID> {

    String ROW = "select new app.nadel.reports.internal.TemplateVersionRow(v.id, v.templateId, v.versionNo, "
            + "v.fileName, v.sizeBytes, v.sha256, v.uploadedBy, v.uploadedAt) from ReportTemplateVersionRecord v ";

    /** Inserts the version (versions are never changed). */
    ReportTemplateVersionRecord save(ReportTemplateVersionRecord version);

    /** Writes the pending insert now, so a violated unique key surfaces where it can be answered. */
    void flush();

    /** The versions of the template, newest first, without their files. */
    @Query(ROW + "where v.tenantId = :tenantId and v.templateId = :templateId order by v.versionNo desc")
    List<TemplateVersionRow> findRows(@Param("tenantId") UUID tenantId, @Param("templateId") UUID templateId);

    /** The latest version of each of the templates, without its file; templates without a version are absent. */
    @Query(ROW + "where v.tenantId = :tenantId and v.templateId in :templateIds and v.versionNo = "
            + "(select max(w.versionNo) from ReportTemplateVersionRecord w where w.tenantId = v.tenantId "
            + "and w.templateId = v.templateId)")
    List<TemplateVersionRow> findLatestRows(@Param("tenantId") UUID tenantId,
            @Param("templateIds") Collection<UUID> templateIds);

    /** How many versions each of the templates has, as {@code [templateId, count]}. */
    @Query("select v.templateId, count(v) from ReportTemplateVersionRecord v where v.tenantId = :tenantId "
            + "and v.templateId in :templateIds group by v.templateId")
    List<Object[]> countByTemplate(@Param("tenantId") UUID tenantId,
            @Param("templateIds") Collection<UUID> templateIds);

    /** The highest version number of the template, 0 when it has none. */
    @Query("select coalesce(max(v.versionNo), 0) from ReportTemplateVersionRecord v where v.tenantId = :tenantId "
            + "and v.templateId = :templateId")
    int maxVersionNo(@Param("tenantId") UUID tenantId, @Param("templateId") UUID templateId);

    /** The file of the version of that template; empty for an unknown id or a version of another template. */
    @Query("select new app.nadel.reports.internal.TemplateFile(v.fileName, v.content) "
            + "from ReportTemplateVersionRecord v where v.tenantId = :tenantId and v.templateId = :templateId "
            + "and v.id = :versionId")
    Optional<TemplateFile> findFile(@Param("tenantId") UUID tenantId, @Param("templateId") UUID templateId,
            @Param("versionId") UUID versionId);
}
