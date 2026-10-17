package app.nadel.members.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A role held by a member. */
@Entity
@Table(name = "member_role")
@IdClass(MemberRole.Key.class)
class MemberRole {

    record Key(UUID memberId, UUID roleId) {
    }

    @Id
    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected MemberRole() {
    }

    MemberRole(UUID tenantId, UUID memberId, UUID roleId, Instant assignedAt) {
        this.tenantId = tenantId;
        this.memberId = memberId;
        this.roleId = roleId;
        this.assignedAt = assignedAt;
    }

    UUID getMemberId() {
        return memberId;
    }

    UUID getRoleId() {
        return roleId;
    }

    UUID getTenantId() {
        return tenantId;
    }
}
