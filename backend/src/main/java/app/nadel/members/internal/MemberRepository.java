package app.nadel.members.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MemberRepository extends JpaRepository<Member, UUID> {

    @Query("select m from Member m where m.tenantId = :tenantId and m.keycloakSubject = :subject "
            + "and m.archivedAt is null")
    Optional<Member> findActiveBySubject(@Param("tenantId") UUID tenantId, @Param("subject") String subject);

    /** {@code email} must be lower-case. At most one active member per e-mail (unique index). */
    @Query("select m from Member m where m.tenantId = :tenantId and lower(m.email) = :email "
            + "and m.archivedAt is null")
    Optional<Member> findActiveByEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    /**
     * Binds the member to a Keycloak user only while it is still unbound, so concurrent first sign-ins cannot
     * overwrite each other. Returns the number of rows changed (0 when someone else linked it first).
     */
    @Modifying
    @Query("update Member m set m.keycloakSubject = :subject where m.tenantId = :tenantId and m.id = :id "
            + "and m.keycloakSubject is null and m.archivedAt is null")
    int linkSubject(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("subject") String subject);

    @Query("select new app.nadel.members.internal.RoleGrantRow(r.id, r.name, r.baseCode, rp.permission) "
            + "from MemberRole mr join Role r on r.id = mr.roleId "
            + "left join RolePermission rp on rp.roleId = r.id "
            + "where mr.tenantId = :tenantId and mr.memberId = :memberId")
    List<RoleGrantRow> findRoleGrants(@Param("tenantId") UUID tenantId, @Param("memberId") UUID memberId);
}
