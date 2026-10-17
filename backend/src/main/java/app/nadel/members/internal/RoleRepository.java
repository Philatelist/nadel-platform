package app.nadel.members.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query("select r from Role r where r.tenantId = :tenantId")
    List<Role> findAllOf(@Param("tenantId") UUID tenantId);

    /** Case-insensitive name lookup; both sides are lower-cased by the database, like the unique index. */
    @Query("select r from Role r where r.tenantId = :tenantId and lower(r.name) = lower(:name)")
    Optional<Role> findByName(@Param("tenantId") UUID tenantId, @Param("name") String name);

    @Query("select new app.nadel.members.internal.RolePermissionRow(rp.roleId, rp.permission) "
            + "from RolePermission rp where rp.tenantId = :tenantId")
    List<RolePermissionRow> findAllPermissions(@Param("tenantId") UUID tenantId);

    @Query("select new app.nadel.members.internal.RolePermissionRow(rp.roleId, rp.permission) "
            + "from RolePermission rp where rp.roleId = :roleId")
    List<RolePermissionRow> findPermissions(@Param("roleId") UUID roleId);

    /** Active (not archived) holders per role; roles nobody holds are absent. */
    @Query("select new app.nadel.members.internal.RoleMemberCount(mr.roleId, count(m.id)) "
            + "from MemberRole mr join Member m on m.id = mr.memberId "
            + "where mr.tenantId = :tenantId and m.archivedAt is null group by mr.roleId")
    List<RoleMemberCount> countActiveHolders(@Param("tenantId") UUID tenantId);

    /** Any member row, active or archived: the foreign key refuses the deletion in both cases. */
    @Query("select count(mr) > 0 from MemberRole mr where mr.roleId = :roleId")
    boolean isAssigned(@Param("roleId") UUID roleId);

    @Modifying(flushAutomatically = true)
    @Query("delete from RolePermission rp where rp.roleId = :roleId and rp.permission in :permissions")
    int deletePermissions(@Param("roleId") UUID roleId, @Param("permissions") Collection<String> permissions);

    @Modifying(flushAutomatically = true)
    @Query("delete from RolePermission rp where rp.roleId = :roleId")
    int deleteAllPermissions(@Param("roleId") UUID roleId);

    @Modifying(flushAutomatically = true)
    @Query("delete from MemberRole mr where mr.memberId = :memberId and mr.roleId in :roleIds")
    int deleteMemberRoles(@Param("memberId") UUID memberId, @Param("roleIds") Collection<UUID> roleIds);
}
