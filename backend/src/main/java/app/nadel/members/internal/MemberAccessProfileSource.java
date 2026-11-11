package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.AccessProfileSource;
import app.nadel.identity.RoleRef;
import app.nadel.privacy.IndexKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a validated token to the active member behind it (see {@link AccessProfileSource}).
 * <p>
 * Not {@code @Transactional} itself: the first sign-in writes (links the subject) and may hit the unique index when
 * two requests race, and a failed statement poisons its PostgreSQL transaction, so each step runs in its own short
 * transaction. The usual request is a single read-only transaction. The caller has bound the tenant, so the
 * tenant-aware transaction manager sets {@code app.tenant_id} for every transaction started here.
 * <p>
 * A member whose stored access ({@link MemberAccessState}, feature 020) is {@code INVITED} (the password is not set
 * yet) or {@code DISABLED} is refused at once, whatever Keycloak says. No row or {@code NONE} keeps the rules above:
 * an account made by hand before feature 020 still links by its verified e-mail and then reads as {@code ACTIVE}.
 * <p>
 * <b>Last sign-in</b> (the member card's «останній вхід»): when the member's row is {@code ACTIVE} and its
 * {@code last_sign_in_at} is missing or older than {@code nadel.identity.last-sign-in-interval} (1 h), a short
 * transaction of its own stores the time (a conditional update, so two requests write it once). The usual request
 * writes nothing; a failure to write never refuses the sign-in. A member linked by the legacy path without a row gets
 * no time: no row is created at sign-in.
 * <p>
 * Logs only tenant ids, subjects and member ids: never names, phones or e-mail addresses.
 */
@Service
class MemberAccessProfileSource implements AccessProfileSource {

    private static final Logger log = LoggerFactory.getLogger(MemberAccessProfileSource.class);

    private final MemberRepository members;
    private final RoleGrantReader grants;
    private final MemberCrypto crypto;
    private final MemberAccessState access;
    private final Clock clock;
    private final Duration signInInterval;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    MemberAccessProfileSource(MemberRepository members, RoleGrantReader grants, MemberCrypto crypto,
            MemberAccessState access, Clock clock,
            @Value("${nadel.identity.last-sign-in-interval:1h}") Duration signInInterval,
            PlatformTransactionManager transactionManager) {
        this.members = members;
        this.grants = grants;
        this.crypto = crypto;
        this.access = access;
        this.clock = clock;
        this.signInInterval = signInInterval;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<AccessProfile> resolve(UUID tenantId, String subject, @Nullable String email,
            boolean emailVerified) {
        Optional<Entry> entry = reading.execute(status -> entryBySubject(tenantId, subject));
        if (entry.isPresent()) {
            if (entry.get().signInDue()) {
                recordSignIn(tenantId, entry.get().profile().memberId());
            }
            return Optional.of(entry.get().profile());
        }
        String normalized = normalize(email);
        if (!emailVerified || normalized == null) {
            return Optional.empty();
        }
        try {
            return writing.execute(status -> linkByEmail(tenantId, subject, normalized));
        } catch (DataIntegrityViolationException e) {
            // A concurrent first request linked this subject (unique index), or an archived member still holds it.
            return reading.execute(status -> findBySubject(tenantId, subject));
        }
    }

    private Optional<AccessProfile> findBySubject(UUID tenantId, String subject) {
        return members.findActiveBySubject(tenantId, subject).filter(this::mayEnter)
                .map(member -> profileOf(member, subject));
    }

    /** The linked member's profile and whether its last sign-in is due to be stored. */
    private Optional<Entry> entryBySubject(UUID tenantId, String subject) {
        Optional<Member> member = members.findActiveBySubject(tenantId, subject);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        Optional<MemberAccessState.Row> row = access.find(member.get().getId());
        if (!mayEnter(member.get(), row)) {
            return Optional.empty();
        }
        return Optional.of(new Entry(profileOf(member.get(), subject), signInDue(row)));
    }

    private boolean signInDue(Optional<MemberAccessState.Row> row) {
        if (row.isEmpty() || row.get().state() != MemberAccessState.State.ACTIVE) {
            return false;
        }
        Instant last = row.get().lastSignInAt();
        return last == null || last.isBefore(clock.instant().minus(signInInterval));
    }

    /** Stores the last sign-in in its own short transaction; a failure is logged, the sign-in goes on. */
    private void recordSignIn(UUID tenantId, UUID memberId) {
        Instant now = clock.instant();
        try {
            writing.executeWithoutResult(status -> access.recordSignIn(memberId, now, now.minus(signInInterval)));
        } catch (DataAccessException | TransactionException e) {
            log.warn("Last sign-in of member {} of tenant {} not stored: {}", memberId, tenantId,
                    e.getClass().getSimpleName());
        }
    }

    /** Whether the stored access lets the member in: never while {@code INVITED} or {@code DISABLED}. */
    private boolean mayEnter(Member member) {
        return mayEnter(member, access.find(member.getId()));
    }

    private boolean mayEnter(Member member, Optional<MemberAccessState.Row> row) {
        Optional<MemberAccessState.State> state = row.map(MemberAccessState.Row::state);
        if (state.isPresent() && (state.get() == MemberAccessState.State.INVITED
                || state.get() == MemberAccessState.State.DISABLED)) {
            log.info("Member {} of tenant {} has access {}: sign-in refused", member.getId(), member.getTenantId(),
                    state.get());
            return false;
        }
        return true;
    }

    private Optional<AccessProfile> linkByEmail(UUID tenantId, String subject, String email) {
        // the e-mail is sealed: the member is found by the blind index of the normalised address
        Optional<Member> candidate = members.findActiveByEmailBidx(tenantId,
                crypto.index(tenantId, IndexKind.EMAIL, email));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Member member = candidate.get();
        if (!mayEnter(member)) {
            return Optional.empty();
        }
        if (member.getKeycloakSubject() != null) {
            // Never relink: the e-mail belongs to a member that is bound to a Keycloak user already.
            if (!member.getKeycloakSubject().equals(subject)) {
                log.warn("Subject {} matches the e-mail of a member of tenant {} that is linked to another subject",
                        subject, tenantId);
                return Optional.empty();
            }
            return Optional.of(profileOf(member, subject));
        }
        if (members.linkSubject(tenantId, member.getId(), subject) == 0) {
            return findBySubject(tenantId, subject); // lost the race
        }
        log.info("Linked subject {} to member {} of tenant {}", subject, member.getId(), tenantId);
        return Optional.of(profileOf(member, subject));
    }

    private AccessProfile profileOf(Member member, String subject) {
        List<RoleGrant> roleGrants = grants.read(member);
        List<RoleRef> roles = roleGrants.stream().map(RoleGrant::ref).toList();
        return new AccessProfile(member.getTenantId(), member.getId(), subject, member.displayName(),
                PermissionUnion.of(roleGrants), roles, false);
    }

    /** A linked member as {@link #entryBySubject} found it. */
    private record Entry(AccessProfile profile, boolean signInDue) {
    }

    private static @Nullable String normalize(@Nullable String email) {
        return PhoneIndexValues.normalisedEmail(email);
    }
}
