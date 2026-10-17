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

    /** Stored flag; read it through {@link #isNameCustomized()}, which also covers custom roles. */
    @Column(name = "name_customized", nullable = false)
    private boolean nameCustomized;

    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Role() {
    }

    /** A new custom role (no base code). */
    static Role custom(UUID tenantId, String name, String description, Instant now) {
        Role role = new Role();
        role.id = UUID.randomUUID();
        role.tenantId = tenantId;
        role.name = name;
        role.nameCustomized = true;
        role.description = description;
        role.createdAt = now;
        role.updatedAt = now;
        return role;
    }

    void edit(String name, String description, Instant now) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
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

    /** True for custom roles and for base roles that were renamed; false for a base role with its standard name. */
    boolean isNameCustomized() {
        return isNameCustomized(baseCode, nameCustomized);
    }

    /** The rule behind {@link #isNameCustomized()}, shared with the query rows: custom roles are always customised. */
    static boolean isNameCustomized(String baseCode, boolean storedFlag) {
        return baseCode == null || storedFlag;
    }
}
