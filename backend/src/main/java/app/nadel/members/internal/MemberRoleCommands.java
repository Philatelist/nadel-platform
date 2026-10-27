package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.problem.ApiProblemException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the set of roles of a member. One transaction that also publishes the audit event. The caller must hold
 * {@code ROLES_MANAGE} (checked on the controller).
 * <p>
 * An archived member may be given and lose roles: it only matters once they are restored. When the new set lacks
 * Chairman, {@link LastChairmanGuard} runs first (it takes the cooperative lock before counting), so two concurrent
 * removals cannot both leave the cooperative without a chairman.
 */
@Service
class MemberRoleCommands {

    private final MemberRepository members;
    private final RoleRepository roles;
    private final RoleGrantReader grants;
    private final LastChairmanGuard lastChairman;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final MemberCrypto crypto;

    MemberRoleCommands(MemberRepository members, RoleRepository roles, RoleGrantReader grants,
            LastChairmanGuard lastChairman, EntityManager entityManager, ApplicationEventPublisher events,
            MemberCrypto crypto) {
        this.members = members;
        this.roles = roles;
        this.grants = grants;
        this.lastChairman = lastChairman;
        this.entityManager = entityManager;
        this.events = events;
        this.crypto = crypto;
    }

    @Transactional
    nadel.contract.model.Member setRoles(UUID memberId, Set<UUID> roleIds, AccessProfile actor) {
        Member member = members.findById(memberId).orElseThrow(ApiProblemException::notFound);
        Map<UUID, Role> wanted = roles.findAllById(roleIds).stream()
                .filter(r -> r.getTenantId().equals(actor.tenantId()))
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        if (wanted.size() != roleIds.size()) {
            throw ApiProblemException.notFound(); // unknown or other cooperative's role
        }

        boolean keepsChairman = wanted.values().stream()
                .anyMatch(r -> PermissionUnion.CHAIRMAN.equals(r.getBaseCode()));
        if (!keepsChairman) {
            lastChairman.ensureNotLast(actor.tenantId(), member.getId());
        }

        List<RoleGrant> current = grants.read(member);
        Set<UUID> currentIds = current.stream().map(RoleGrant::id).collect(Collectors.toSet());
        Set<UUID> removed = new HashSet<>(currentIds);
        removed.removeAll(wanted.keySet());
        Set<UUID> added = new HashSet<>(wanted.keySet());
        added.removeAll(currentIds);

        if (!removed.isEmpty() || !added.isEmpty()) {
            if (!removed.isEmpty()) {
                roles.deleteMemberRoles(member.getId(), removed);
            }
            Instant now = Instant.now();
            for (UUID roleId : added) {
                entityManager.persist(new MemberRole(actor.tenantId(), member.getId(), roleId, now));
            }
            events.publishEvent(AuditEvent.now(
                    actor.tenantId(),
                    new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                    "MEMBER_ROLES_CHANGED",
                    "MEMBER",
                    member.getId(),
                    member.getLastName() + " " + member.getFirstName(),
                    List.of(new FieldChange("roles",
                            names(current.stream()
                                    .map(g -> RoleSnapshot.auditName(g.name(), g.baseCode(), g.nameCustomized())).toList()),
                            names(wanted.values().stream()
                                    .map(r -> RoleSnapshot.auditName(r.getName(), r.getBaseCode(), r.isNameCustomized()))
                                    .toList())))));
        }
        return MemberDtoMapper.toDto(member, crypto.open(member), grants.read(member), actor);
    }

    /** Role names sorted and joined; null when there are none. */
    private static @Nullable String names(List<String> names) {
        return names.isEmpty() ? null
                : String.join(", ", names.stream().sorted(String.CASE_INSENSITIVE_ORDER
                        .thenComparing(Comparator.naturalOrder())).toList());
    }
}
