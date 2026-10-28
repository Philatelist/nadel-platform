package app.nadel.members.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MemberRepository extends JpaRepository<Member, UUID> {

    /**
     * The member with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction. Archiving takes it
     * first; it conflicts with the shared lock a change of owner takes through {@code MemberDirectory.lockActive}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") UUID id);

    @Query("select m from Member m where m.tenantId = :tenantId and m.keycloakSubject = :subject "
            + "and m.archivedAt is null")
    Optional<Member> findActiveBySubject(@Param("tenantId") UUID tenantId, @Param("subject") String subject);

    /** The active member with this e-mail blind index; at most one (unique {@code member_tenant_email_bidx_uq}). */
    @Query("select m from Member m where m.tenantId = :tenantId and m.emailBidx = :emailBidx "
            + "and m.archivedAt is null")
    Optional<Member> findActiveByEmailBidx(@Param("tenantId") UUID tenantId, @Param("emailBidx") byte[] emailBidx);

    /** The active members whose e-mail blind index is one of {@code emailBidxs}, for the Excel import. */
    @Query(value = """
            select m.* from member m
            where m.tenant_id = :tenantId and m.archived_at is null and m.email_bidx in (:emailBidxs)
            """, nativeQuery = true)
    List<Member> findActiveByEmailBidxIn(@Param("tenantId") UUID tenantId,
            @Param("emailBidxs") Collection<byte[]> emailBidxs);

    /**
     * The active members with a phone whose blind index ({@code member_phone_index.phone_bidx}) is one of
     * {@code phoneBidxs}, for the Excel import.
     */
    @Query(value = """
            select m.* from member m
            where m.tenant_id = :tenantId and m.archived_at is null
              and m.id in (select pi.member_id from member_phone_index pi
                           where pi.tenant_id = :tenantId and pi.phone_bidx in (:phoneBidxs))
            """, nativeQuery = true)
    List<Member> findActiveByPhoneBidxIn(@Param("tenantId") UUID tenantId,
            @Param("phoneBidxs") Collection<byte[]> phoneBidxs);

    /**
     * Binds the member to a Keycloak user only while it is still unbound, so concurrent first sign-ins cannot
     * overwrite each other. Returns the number of rows changed (0 when someone else linked it first).
     */
    @Modifying
    @Query("update Member m set m.keycloakSubject = :subject where m.tenantId = :tenantId and m.id = :id "
            + "and m.keycloakSubject is null and m.archivedAt is null")
    int linkSubject(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("subject") String subject);

    @Query("select new app.nadel.members.internal.RoleGrantRow(r.id, r.name, r.baseCode, r.nameCustomized, rp.permission) "
            + "from MemberRole mr join Role r on r.id = mr.roleId "
            + "left join RolePermission rp on rp.roleId = r.id "
            + "where mr.tenantId = :tenantId and mr.memberId = :memberId")
    List<RoleGrantRow> findRoleGrants(@Param("tenantId") UUID tenantId, @Param("memberId") UUID memberId);

    /**
     * One page of the member list. {@code textPattern} is a LIKE pattern over lower-cased names ({@code %token%}, with
     * {@code !} as the escape character); the three blind indexes match the e-mail, a full phone and the last 4 digits
     * of a phone exactly (a candidate the query does not stand for is bound as 32 zero bytes, which matches nothing).
     * Order is fixed here, so the {@code Pageable} must be unsorted.
     */
    @Query(value = """
            select m.* from member m
            where m.tenant_id = :tenantId
              and (:includeArchived = true or m.archived_at is null)
              and (lower(m.last_name) like :textPattern escape '!'
                   or lower(m.first_name) like :textPattern escape '!'
                   or lower(m.middle_name) like :textPattern escape '!'
                   or m.email_bidx = :emailBidx
                   or m.id in (select pi.member_id from member_phone_index pi
                               where pi.tenant_id = :tenantId
                                 and (pi.phone_bidx = :phoneBidx or pi.last4_bidx = :last4Bidx)))
            order by lower(m.last_name), lower(m.first_name), m.id
            """,
            countQuery = """
            select count(*) from member m
            where m.tenant_id = :tenantId
              and (:includeArchived = true or m.archived_at is null)
              and (lower(m.last_name) like :textPattern escape '!'
                   or lower(m.first_name) like :textPattern escape '!'
                   or lower(m.middle_name) like :textPattern escape '!'
                   or m.email_bidx = :emailBidx
                   or m.id in (select pi.member_id from member_phone_index pi
                               where pi.tenant_id = :tenantId
                                 and (pi.phone_bidx = :phoneBidx or pi.last4_bidx = :last4Bidx)))
            """,
            nativeQuery = true)
    Page<Member> search(@Param("tenantId") UUID tenantId, @Param("includeArchived") boolean includeArchived,
            @Param("textPattern") String textPattern, @Param("emailBidx") byte[] emailBidx,
            @Param("phoneBidx") byte[] phoneBidx, @Param("last4Bidx") byte[] last4Bidx, Pageable pageable);

    /** Roles of several members in one query (no permissions), for the list. */
    @Query("select new app.nadel.members.internal.MemberRoleRow(mr.memberId, r.id, r.name, r.baseCode, r.nameCustomized) "
            + "from MemberRole mr join Role r on r.id = mr.roleId "
            + "where mr.tenantId = :tenantId and mr.memberId in :memberIds")
    List<MemberRoleRow> findRolesOfMembers(@Param("tenantId") UUID tenantId,
            @Param("memberIds") Collection<UUID> memberIds);

    /** Ids of the active members holding the Chairman base role in a cooperative. */
    @Query("select distinct m.id from Member m join MemberRole mr on mr.memberId = m.id "
            + "join Role r on r.id = mr.roleId "
            + "where m.tenantId = :tenantId and m.archivedAt is null and r.baseCode = 'CHAIRMAN'")
    List<UUID> findActiveChairmanIds(@Param("tenantId") UUID tenantId);

    /**
     * Takes a transaction-scoped advisory lock for the cooperative (released at commit or rollback). Two requests
     * that change who the chairmen are serialise here, so both cannot see "another chairman remains".
     * Returns 1; the select only exists to run the function.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(hashtextextended(cast(:tenantId as text), 0))) l",
            nativeQuery = true)
    long lockChairmen(@Param("tenantId") UUID tenantId);

    /**
     * Ids of the members (archived included) whose last, first or middle name matches a LIKE pattern over lower-cased
     * text ({@code %token%}, with {@code !} as the escape character). The cooperative is hidden by RLS.
     */
    @Query("""
            select m.id from Member m
            where lower(m.lastName) like :pattern escape '!'
               or lower(m.firstName) like :pattern escape '!'
               or lower(m.middleName) like :pattern escape '!'
            order by lower(m.lastName), lower(m.firstName), m.id
            """)
    List<UUID> findIdsByName(@Param("pattern") String pattern, Pageable limit);

    /** The given members (archived included) with a shared row lock ({@code FOR SHARE}), in id order. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Member m where m.id in :ids order by m.id")
    List<Member> lockAllById(@Param("ids") Collection<UUID> ids);

    /**
     * The given members with an exclusive row lock ({@code FOR UPDATE}), in id order, for the Excel import. The lock
     * conflicts with archiving (which takes the same lock) and with the shared lock of a change of owner.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id in :ids order by m.id")
    List<Member> lockAllForUpdate(@Param("ids") Collection<UUID> ids);
}
