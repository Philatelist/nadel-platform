package app.nadel.reports.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A report template of the cooperative (table {@code report_template}, V20): its name, description, slot and status.
 * The files are its versions ({@link ReportTemplateVersionRecord}). A template is archived, never deleted
 * ({@code nadel_app} has no {@code DELETE}); an archived template frees its name and its slot (partial unique indexes
 * {@code report_template_name_uq} and {@code report_template_slot_uq} cover active templates only). The cooperative
 * comes from the caller's profile (the token), never from the request.
 */
@Entity
@Table(name = "report_template")
class ReportTemplateRecord {

    /** The only slot: the tax report (contract {@code ReportTemplateSlot.TAX}). */
    static final String TAX_SLOT = "TAX";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private @Nullable String slot;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private @Nullable String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateStatus status;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_by")
    private @Nullable UUID archivedBy;

    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    protected ReportTemplateRecord() {
    }

    /** A new active template. */
    static ReportTemplateRecord create(UUID tenantId, @Nullable String slot, String name,
            @Nullable String description, @Nullable UUID createdBy, Instant now) {
        ReportTemplateRecord template = new ReportTemplateRecord();
        template.id = UUID.randomUUID();
        template.tenantId = tenantId;
        template.slot = slot;
        template.name = name;
        template.description = description;
        template.status = TemplateStatus.ACTIVE;
        template.createdBy = createdBy;
        template.createdAt = now;
        return template;
    }

    /** Archives the template for good. */
    void archive(@Nullable UUID by, Instant now) {
        this.status = TemplateStatus.ARCHIVED;
        this.archivedBy = by;
        this.archivedAt = now;
    }

    boolean isArchived() {
        return status == TemplateStatus.ARCHIVED;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    @Nullable String getSlot() {
        return slot;
    }

    String getName() {
        return name;
    }

    @Nullable String getDescription() {
        return description;
    }

    TemplateStatus getStatus() {
        return status;
    }
}
