package app.nadel.imports.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One uploaded import file. The author is a snapshot (member id, Keycloak subject, display name), so the history stays
 * readable when a member is renamed or removed. The {@code plan} holds the normalised rows of a preview that can still
 * be committed and contains personal data: it is cleared as soon as it is no longer needed, and queries that only
 * need the history must not load it.
 */
@Entity
@Table(name = "import_run")
class ImportRun {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Sanitised base name of the file. Never logged. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "created_by_member_id")
    private UUID createdByMemberId;

    @Column(name = "created_by_subject", nullable = false)
    private String createdBySubject;

    @Column(name = "created_by_name", nullable = false)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> counts;

    @Column(name = "problem_count", nullable = false)
    private int problemCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> plan;

    @Column(name = "plan_digest")
    private String planDigest;

    protected ImportRun() {
    }

    /**
     * A new run.
     *
     * @param plan       the normalised rows of a preview without problems; null for a run with problems
     * @param planDigest the digest of the whole plan (see {@code PlanDigest}); null for a run with problems
     */
    static ImportRun create(UUID id, UUID tenantId, String fileName, @Nullable UUID createdByMemberId,
            String createdBySubject, String createdByName, Instant createdAt, Instant expiresAt, ImportStatus status,
            Map<String, Object> counts, int problemCount, @Nullable Map<String, Object> plan,
            @Nullable String planDigest) {
        ImportRun run = new ImportRun();
        run.id = id;
        run.tenantId = tenantId;
        run.fileName = fileName;
        run.createdByMemberId = createdByMemberId;
        run.createdBySubject = createdBySubject;
        run.createdByName = createdByName;
        run.createdAt = createdAt;
        run.expiresAt = expiresAt;
        run.status = status;
        run.counts = counts;
        run.problemCount = problemCount;
        run.plan = plan;
        run.planDigest = planDigest;
        return run;
    }

    UUID getId() {
        return id;
    }

    String getFileName() {
        return fileName;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    ImportStatus getStatus() {
        return status;
    }

    Map<String, Object> getCounts() {
        return counts;
    }

    /** The stored rows of a preview that can still be committed; null once cleared. Personal data. */
    @Nullable Map<String, Object> getPlan() {
        return plan;
    }

    @Nullable String getPlanDigest() {
        return planDigest;
    }
}
