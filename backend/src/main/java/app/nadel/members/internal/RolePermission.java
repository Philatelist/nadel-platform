package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/** One permission of a role. The name is validated against the Java enum, not by the database. */
@Entity
@Table(name = "role_permission")
@IdClass(RolePermission.Key.class)
class RolePermission {

    record Key(UUID roleId, String permission) {
    }

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Id
    @Column(nullable = false, updatable = false)
    private String permission;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected RolePermission() {
    }

    RolePermission(UUID tenantId, UUID roleId, String permission) {
        this.tenantId = tenantId;
        this.roleId = roleId;
        this.permission = permission;
    }

    UUID getRoleId() {
        return roleId;
    }

    String getPermission() {
        return permission;
    }

    UUID getTenantId() {
        return tenantId;
    }
}
