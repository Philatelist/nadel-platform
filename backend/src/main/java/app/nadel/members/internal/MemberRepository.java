package app.nadel.members.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * One page of the member list. {@code textPattern} is a LIKE pattern over lower-cased text ({@code %token%}, with
     * {@code !} as the escape character); {@code phonePattern} is a LIKE pattern over digits and only used when
     * {@code phoneSearch} is true. Order is fixed here, so the {@code Pageable} must be unsorted.
     */
    @Query(value = """
            select m.* from member m
            where m.tenant_id = :tenantId
              and (:includeArchived = true or m.archived_at is null)
              and (lower(m.last_name) like :textPattern escape '!'
                   or lower(m.first_name) like :textPattern escape '!'
                   or lower(m.middle_name) like :textPattern escape '!'
                   or lower(m.email) like :textPattern escape '!'
                   or (:phoneSearch = true and exists (
                         select 1 from unnest(m.phones) as phone(value)
                         where regexp_replace(phone.value, '[^0-9]', '', 'g') like :phonePattern escape '!')))
            order by lower(m.last_name), lower(m.first_name), m.id
            """,
            countQuery = """
            select count(*) from member m
            where m.tenant_id = :tenantId
              and (:includeArchived = true or m.archived_at is null)
              and (lower(m.last_name) like :textPattern escape '!'
                   or lower(m.first_name) like :textPattern escape '!'
                   or lower(m.middle_name) like :textPattern escape '!'
                   or lower(m.email) like :textPattern escape '!'
                   or (:phoneSearch = true and exists (
                         select 1 from unnest(m.phones) as phone(value)
                         where regexp_replace(phone.value, '[^0-9]', '', 'g') like :phonePattern escape '!')))
            """,
            nativeQuery = true)
    Page<Member> search(@Param("tenantId") UUID tenantId, @Param("includeArchived") boolean includeArchived,
            @Param("textPattern") String textPattern, @Param("phoneSearch") boolean phoneSearch,
            @Param("phonePattern") String phonePattern, Pageable pageable);

    /** Roles of several members in one query (no permissions), for the list. */
    @Query("select new app.nadel.members.internal.MemberRoleRow(mr.memberId, r.id, r.name, r.baseCode) "
            + "from MemberRole mr join Role r on r.id = mr.roleId "
            + "where mr.tenantId = :tenantId and mr.memberId in :memberIds")
    List<MemberRoleRow> findRolesOfMembers(@Param("tenantId") UUID tenantId,
            @Param("memberIds") Collection<UUID> memberIds);
}
