package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.RoleDetails;
import nadel.contract.model.RoleInput;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits and deletes roles. Every method is one transaction that also publishes the audit event. The
 * cooperative always comes from the caller's {@link AccessProfile}. The caller must hold {@code ROLES_MANAGE}
 * (checked on the controller).
 * <p>
 * Every role can be renamed, base roles included (names are unique per cooperative, ignoring case). A base role keeps
 * a stored flag {@code name_customized}: it is false while the role has its standard name and
 * {@code useDefaultName} restores that. Base roles cannot be deleted, and the Chairman role always has every
 * permission. Treasurer and Member permissions and the descriptions of all roles can be edited.
 */
@Service
class RoleCommands {

    private static final String NAME_INDEX = "role_tenant_name_uq";
    private static final int NAME_MAX = 60;

    private final RoleRepository roles;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    RoleCommands(RoleRepository roles, EntityManager entityManager, ApplicationEventPublisher events) {
        this.roles = roles;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Transactional
    RoleDetails create(RoleInput input, AccessProfile actor) {
        String name = cleanName(input.getName());
        String description = blankToNull(input.getDescription());
        Set<Permission> permissions = permissionsOf(input);
        ensureNameFree(actor.tenantId(), name, null);

        Role role = save(Role.custom(actor.tenantId(), name, description, Instant.now()));
        for (Permission permission : permissions) {
            entityManager.persist(new RolePermission(actor.tenantId(), role.getId(), permission.name()));
        }
        publish(actor, "ROLE_CREATED", role,
                RoleSnapshot.diff(RoleSnapshot.NONE, RoleSnapshot.of(name, description, permissions)));
        return RoleDtoMapper.toDto(role, permissions, 0);
    }

    @Transactional
    RoleDetails update(UUID id, RoleInput input, AccessProfile actor) {
        Role role = find(id, actor);
        boolean base = role.getBaseCode() != null;
        boolean restore = Boolean.TRUE.equals(input.getUseDefaultName());
        if (restore && !base) {
            throw invalid("useDefaultName");
        }

        String name;
        boolean customized;
        if (restore) {
            name = BaseRoles.defaultName(role.getBaseCode());
            customized = false;
        } else {
            name = cleanName(input.getName());
            customized = !name.equals(role.getName()) || role.isNameCustomized();
        }
        String description = blankToNull(input.getDescription());
        Set<Permission> requested = permissionsOf(input);

        boolean chairman = PermissionUnion.CHAIRMAN.equals(role.getBaseCode());
        if (chairman && !requested.equals(EnumSet.allOf(Permission.class))) {
            throw baseRoleImmutable("The Chairman role always has every permission.");
        }
        boolean renamed = !name.equals(role.getName());
        if (renamed) {
            ensureNameFree(actor.tenantId(), name, role.getId());
        }

        Set<Permission> current = RoleDtoMapper.effective(role,
                roles.findPermissions(role.getId()).stream().map(RolePermissionRow::permission).toList());
        List<FieldChange> changes = RoleSnapshot.diff(
                RoleSnapshot.of(RoleSnapshot.auditName(role.getName(), role.getBaseCode(), role.isNameCustomized()),
                        role.getDescription(), current),
                RoleSnapshot.of(RoleSnapshot.auditName(name, role.getBaseCode(), customized), description, requested));
        boolean flagChanged = customized != role.isNameCustomized();
        if (!changes.isEmpty() || flagChanged) {
            role.edit(name, customized, description, Instant.now());
            role = save(role);
            if (!chairman) {
                writePermissions(actor.tenantId(), role.getId(), current, requested);
            }
            if (!changes.isEmpty()) {
                publish(actor, "ROLE_UPDATED", role, changes);
            }
        }
        return RoleDtoMapper.toDto(role, requested, activeHolders(actor.tenantId(), role.getId()));
    }

    @Transactional
    void delete(UUID id, AccessProfile actor) {
        Role role = find(id, actor);
        if (role.getBaseCode() != null) {
            throw baseRoleImmutable("A base role cannot be deleted.");
        }
        if (roles.isAssigned(role.getId())) {
            throw roleInUse();
        }
        Set<Permission> permissions = RoleDtoMapper.parse(
                roles.findPermissions(role.getId()).stream().map(RolePermissionRow::permission).toList());
        RoleSnapshot before = RoleSnapshot.of(role.getName(), role.getDescription(), permissions);
        roles.deleteAllPermissions(role.getId());
        roles.delete(role);
        try {
            roles.flush();
        } catch (DataIntegrityViolationException e) {
            // a member was given the role between the check and the delete: the foreign key refuses
            throw roleInUse();
        }
        publish(actor, "ROLE_DELETED", role, RoleSnapshot.diff(before, RoleSnapshot.NONE));
    }

    private void writePermissions(UUID tenantId, UUID roleId, Set<Permission> current, Set<Permission> requested) {
        Set<String> removed = current.stream().filter(p -> !requested.contains(p)).map(Permission::name)
                .collect(Collectors.toSet());
        if (!removed.isEmpty()) {
            roles.deletePermissions(roleId, removed);
        }
        for (Permission permission : requested) {
            if (!current.contains(permission)) {
                entityManager.persist(new RolePermission(tenantId, roleId, permission.name()));
            }
        }
    }

    /** The role of the caller's cooperative (RLS hides other cooperatives), or 404. */
    private Role find(UUID id, AccessProfile actor) {
        return roles.findById(id).filter(r -> r.getTenantId().equals(actor.tenantId()))
                .orElseThrow(ApiProblemException::notFound);
    }

    /** Saves and flushes, so a unique violation of the name index surfaces here as {@code ROLE_NAME_TAKEN}. */
    private Role save(Role role) {
        try {
            return roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException e) {
            if (violatesNameIndex(e)) {
                throw nameTaken();
            }
            throw e;
        }
    }

    /** Fast, friendly check; the unique index remains the guarantee against a concurrent request. */
    private void ensureNameFree(UUID tenantId, String name, @Nullable UUID selfId) {
        Optional<Role> holder = roles.findByName(tenantId, name);
        if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
            throw nameTaken();
        }
    }

    private long activeHolders(UUID tenantId, UUID roleId) {
        return roles.countActiveHolders(tenantId).stream()
                .filter(c -> c.roleId().equals(roleId)).mapToLong(RoleMemberCount::count).findFirst().orElse(0);
    }

    private void publish(AccessProfile actor, String action, Role role, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(
                actor.tenantId(),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                action,
                "ROLE",
                role.getId(),
                RoleSnapshot.auditName(role.getName(), role.getBaseCode(), role.isNameCustomized()),
                changes));
    }

    /** Trimmed name of 1 to 60 characters. */
    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw invalidName(ProblemCodes.REQUIRED);
        }
        if (name.length() > NAME_MAX) {
            throw invalidName(ProblemCodes.TOO_LONG);
        }
        return name;
    }

    private static Set<Permission> permissionsOf(RoleInput input) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        for (nadel.contract.model.Permission permission : input.getPermissions()) {
            result.add(Permission.valueOf(permission.name()));
        }
        return result;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ApiProblemException invalidName(String code) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem("name", code)));
    }

    private static ApiProblemException invalid(String field) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", List.of(new FieldProblem(field, ProblemCodes.INVALID)));
    }

    private static ApiProblemException nameTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.ROLE_NAME_TAKEN,
                "A role with this name already exists.",
                List.of(new FieldProblem("name", ProblemCodes.ROLE_NAME_TAKEN)));
    }

    private static ApiProblemException roleInUse() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.ROLE_IN_USE,
                "This role is assigned to members and cannot be deleted.");
    }

    private static ApiProblemException baseRoleImmutable(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.BASE_ROLE_IMMUTABLE, detail);
    }

    static boolean violatesNameIndex(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && NAME_INDEX.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(NAME_INDEX)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
