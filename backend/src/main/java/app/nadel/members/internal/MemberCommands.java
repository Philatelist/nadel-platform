package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.members.MemberArchiveGuard;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.MemberInput;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits, archives and restores members. Every method is one transaction that also publishes the
 * audit event: the outbox stores the publication in the same transaction, so a change is never committed
 * without its audit entry being owed.
 * <p>
 * The cooperative always comes from the caller's {@link AccessProfile}, never from the request. The caller must hold
 * {@code MEMBERS_EDIT} (checked on the controller).
 */
@Service
class MemberCommands {

    private static final String EMAIL_INDEX = "member_tenant_email_uq";

    private final MemberRepository members;
    private final RoleGrantReader grants;
    private final LastChairmanGuard lastChairman;
    private final List<MemberArchiveGuard> archiveGuards;
    private final MemberAuditPublisher audit;

    MemberCommands(MemberRepository members, RoleGrantReader grants, LastChairmanGuard lastChairman,
            List<MemberArchiveGuard> archiveGuards, MemberAuditPublisher audit) {
        this.members = members;
        this.grants = grants;
        this.lastChairman = lastChairman;
        this.archiveGuards = archiveGuards;
        this.audit = audit;
    }

    @Transactional
    nadel.contract.model.Member create(MemberInput input, AccessProfile actor) {
        MemberData data = MemberData.from(input);
        if (!actor.can(Permission.MEMBERS_VIEW)) {
            // notes are read-protected, so an editor who cannot read them cannot write them either
            data = withNotes(data, null);
        }
        ensureEmailFree(actor.tenantId(), data.email(), null);
        Member member = save(Member.create(actor.tenantId(), data, Instant.now()));
        publish(actor, "MEMBER_CREATED", member, MemberSnapshot.diff(MemberSnapshot.NONE, MemberSnapshot.of(member)));
        return card(member, actor);
    }

    @Transactional
    nadel.contract.model.Member update(UUID id, MemberInput input, AccessProfile actor) {
        MemberData data = MemberData.from(input);
        Member member = find(id);
        if (!actor.can(Permission.MEMBERS_VIEW)) {
            data = withNotes(data, member.getNotes()); // keep what is stored: the editor cannot see it
        }
        if (!member.isArchived()) {
            ensureEmailFree(actor.tenantId(), data.email(), member.getId());
        }
        MemberSnapshot before = MemberSnapshot.of(member);
        member.apply(data);
        List<FieldChange> changes = MemberSnapshot.diff(before, MemberSnapshot.of(member));
        if (!changes.isEmpty()) {
            member.touch(Instant.now());
            save(member);
            publish(actor, "MEMBER_UPDATED", member, changes);
        }
        return card(member, actor);
    }

    /**
     * Archives a member. The member row is locked first ({@code FOR UPDATE}): a concurrent change of owner that adds
     * this member holds a {@code FOR SHARE} lock on it, so the two serialise, and whichever runs second sees the
     * other's result. Then the last-chairman check and every {@link MemberArchiveGuard} run. The chairman lock is
     * taken before the row lock, the order a role change already uses (see {@link LastChairmanGuard#lock}).
     */
    @Transactional
    nadel.contract.model.Member archive(UUID id, AccessProfile actor) {
        lastChairman.lock(actor.tenantId());
        Member member = members.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (!member.isArchived()) {
            lastChairman.ensureNotLast(actor.tenantId(), member.getId());
            archiveGuards.forEach(guard -> guard.ensureCanArchive(member.getId()));
            MemberSnapshot before = MemberSnapshot.of(member);
            member.archive(Instant.now());
            save(member);
            publish(actor, "MEMBER_ARCHIVED", member, MemberSnapshot.diff(before, MemberSnapshot.of(member)));
        }
        return card(member, actor);
    }

    @Transactional
    nadel.contract.model.Member restore(UUID id, AccessProfile actor) {
        Member member = find(id);
        if (member.isArchived()) {
            ensureEmailFree(actor.tenantId(), member.getEmail(), member.getId());
            MemberSnapshot before = MemberSnapshot.of(member);
            member.restore(Instant.now());
            save(member);
            publish(actor, "MEMBER_RESTORED", member, MemberSnapshot.diff(before, MemberSnapshot.of(member)));
        }
        return card(member, actor);
    }

    /** The member of the current cooperative (RLS hides other cooperatives), or 404. */
    private Member find(UUID id) {
        return members.findById(id).orElseThrow(ApiProblemException::notFound);
    }

    /** Saves and flushes, so a unique violation of the e-mail index surfaces here as {@code EMAIL_TAKEN}. */
    private Member save(Member member) {
        try {
            return members.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            if (violatesEmailIndex(e)) {
                throw emailTaken();
            }
            throw e;
        }
    }

    /** Fast, friendly check; the unique index remains the guarantee against a concurrent request. */
    private void ensureEmailFree(UUID tenantId, String email, UUID selfId) {
        if (email == null) {
            return;
        }
        Optional<Member> holder = members.findActiveByEmail(tenantId, email);
        if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
            throw emailTaken();
        }
    }

    private void publish(AccessProfile actor, String action, Member member, List<FieldChange> changes) {
        audit.publish(actor.tenantId(), new Actor(actor.memberId(), actor.subject(), actor.displayName()), action,
                member, changes);
    }

    private nadel.contract.model.Member card(Member member, AccessProfile viewer) {
        return MemberDtoMapper.toDto(member, grants.read(member), viewer);
    }

    private static MemberData withNotes(MemberData data, String notes) {
        return new MemberData(data.lastName(), data.firstName(), data.middleName(), data.phones(), data.email(),
                data.postalAddress(), data.deliveryChannel(), data.telegramUsername(), data.viberPhone(), notes);
    }

    private static ApiProblemException emailTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.EMAIL_TAKEN,
                "Another active member already uses this email.",
                List.of(new FieldProblem("email", ProblemCodes.EMAIL_TAKEN)));
    }

    private static boolean violatesEmailIndex(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && EMAIL_INDEX.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(EMAIL_INDEX)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
