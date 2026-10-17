package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A set of permissions of one cooperative. Base roles have a {@code baseCode}; custom roles do not. */
@Entity
@Table(name = "role")
class Role {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** CHAIRMAN, TREASURER or MEMBER; null for custom roles. */
    @Column(name = "base_code")
    private String baseCode;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Role() {
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getBaseCode() {
        return baseCode;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }
}
