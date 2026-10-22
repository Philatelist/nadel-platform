package app.nadel.tariffs.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the cooperative charges for. The five seeded types have a {@code code}; custom types have none. A seeded type
 * keeps its English default name until it is renamed, which sets {@code nameCustomized} for good (the pattern of the
 * role name). A type is archived, never deleted.
 */
@Entity
@Table(name = "charge_type")
class ChargeTypeRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_customized", nullable = false)
    private boolean nameCustomized;

    private String description;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChargeTypeRecord() {
    }

    /** A new custom, active charge type. */
    static ChargeTypeRecord custom(UUID tenantId, String name, @Nullable String description, int position,
            Instant now) {
        ChargeTypeRecord type = new ChargeTypeRecord();
        type.id = UUID.randomUUID();
        type.tenantId = tenantId;
        type.name = name;
        type.nameCustomized = true;
        type.description = description;
        type.active = true;
        type.position = position;
        type.createdAt = now;
        type.updatedAt = now;
        return type;
    }

    /** Overwrites the editable fields; the caller decides whether the name counts as customised and stamps the time. */
    void apply(String name, boolean nameCustomized, @Nullable String description, boolean active, Instant now) {
        this.name = name;
        this.nameCustomized = nameCustomized;
        this.description = description;
        this.active = active;
        this.updatedAt = now;
    }

    void moveTo(int position, Instant now) {
        this.position = position;
        this.updatedAt = now;
    }

    void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    @Nullable String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    boolean isNameCustomized() {
        return nameCustomized;
    }

    @Nullable String getDescription() {
        return description;
    }

    boolean isActive() {
        return active;
    }

    int getPosition() {
        return position;
    }

    @Nullable Instant getArchivedAt() {
        return archivedAt;
    }
}
