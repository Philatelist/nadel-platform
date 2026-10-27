package app.nadel.members.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.AccessProfileSource;
import app.nadel.identity.RoleRef;
import app.nadel.privacy.IndexKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a validated token to the active member behind it (see {@link AccessProfileSource}).
 * <p>
 * Not {@code @Transactional} itself: the first sign-in writes (links the subject) and may hit the unique index when
 * two requests race, and a failed statement poisons its PostgreSQL transaction, so each step runs in its own short
 * transaction. The usual request is a single read-only transaction. The caller has bound the tenant, so the
 * tenant-aware transaction manager sets {@code app.tenant_id} for every transaction started here.
 * <p>
 * Logs only tenant ids, subjects and member ids: never names, phones or e-mail addresses.
 */
@Service
class MemberAccessProfileSource implements AccessProfileSource {

    private static final Logger log = LoggerFactory.getLogger(MemberAccessProfileSource.class);

    private final MemberRepository members;
    private final RoleGrantReader grants;
    private final MemberCrypto crypto;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;

    MemberAccessProfileSource(MemberRepository members, RoleGrantReader grants, MemberCrypto crypto,
            PlatformTransactionManager transactionManager) {
        this.members = members;
        this.grants = grants;
        this.crypto = crypto;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<AccessProfile> resolve(UUID tenantId, String subject, @Nullable String email,
            boolean emailVerified) {
        Optional<AccessProfile> linked = reading.execute(status -> findBySubject(tenantId, subject));
        if (linked.isPresent()) {
            return linked;
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
        return members.findActiveBySubject(tenantId, subject).map(member -> profileOf(member, subject));
    }

    private Optional<AccessProfile> linkByEmail(UUID tenantId, String subject, String email) {
        // the e-mail is sealed: the member is found by the blind index of the normalised address
        Optional<Member> candidate = members.findActiveByEmailBidx(tenantId,
                crypto.index(tenantId, IndexKind.EMAIL, email));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Member member = candidate.get();
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

    private static @Nullable String normalize(@Nullable String email) {
        return PhoneIndexValues.normalisedEmail(email);
    }
}
