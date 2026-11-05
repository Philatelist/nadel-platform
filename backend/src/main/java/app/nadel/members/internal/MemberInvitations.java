package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.SignInAccount;
import app.nadel.identity.SignInAccountChange;
import app.nadel.identity.SignInAccountMissingException;
import app.nadel.identity.SignInAccounts;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.members.internal.MemberAccessState.Row;
import app.nadel.members.internal.MemberAccessState.State;
import app.nadel.members.internal.MemberInvitationRows.Invitation;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.InvitationInfo;
import nadel.contract.model.MemberAccessStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invites members into the system and accepts their invitations (feature 020 §2.1, technical considerations 2.1).
 * <p>
 * <b>Invite</b> is two-phase, so a letter that fails costs nothing:
 * <ol>
 *   <li>a short read checks the member (404, 409 {@code MEMBER_ARCHIVED}, {@code INVITATION_PENDING},
 *       {@code ACCESS_ALREADY_ACTIVE}, {@code MEMBER_EMAIL_MISSING}, {@code DELIVERY_NOT_CONFIGURED});</li>
 *   <li>outside any transaction, {@link #ensureAccount} finds or makes the Keycloak account of the member's e-mail;</li>
 *   <li>a transaction locks the member ({@code FOR UPDATE}), checks again, links the account
 *       ({@code keycloak_subject}) and inserts the new link unsent;</li>
 *   <li>outside any transaction, {@link InvitationDeliveries#deliver} sends the letter;</li>
 *   <li>a transaction marks the link sent, revokes the member's older links, sets {@code INVITED} and publishes the
 *       audit event {@code MEMBER_INVITED} (field {@code channels} only).</li>
 * </ol>
 * When the letter is not sent a compensating transaction revokes the unsent link and the answer is 502
 * {@code INVITATION_NOT_SENT} with the failure code: the access state is unchanged, nothing is audited and an earlier
 * link still works.
 * <p>
 * <b>Accept</b> (public, no token): the SHA-256 of the token finds the cooperative ({@code members_invitation_tenant}),
 * which is bound with {@code TenantContext.runAs}; one transaction locks the member and the link, checks the link
 * (sent, not used, not revoked, not expired by the {@link Clock}, the member active and {@code INVITED}, the e-mail
 * unchanged), sets the password in Keycloak and turns the account on with the e-mail verified and no required action,
 * then marks the link used and the access {@code ACTIVE}. Every link that cannot be used is the same 404
 * {@code INVITATION_INVALID}.
 * <p>
 * The token, the link and the password live only on the stack: never in the audit log, the outbox, a log line or an
 * exception. Logs carry ids and codes only.
 */
@Service
class MemberInvitations {

    private static final Logger log = LoggerFactory.getLogger(MemberInvitations.class);

    static final String INVITED_ACTION = "MEMBER_INVITED";
    private static final String SUBJECT_INDEX = "member_tenant_subject_uq";

    private final MemberRepository members;
    private final MemberCrypto crypto;
    private final MemberAccessState access;
    private final MemberInvitationRows invitations;
    private final InvitationDeliveries deliveries;
    private final SignInAccounts accounts;
    private final MemberAuditPublisher audit;
    private final Clock clock;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;
    private final String setPasswordUrl;
    private final Duration ttl;

    MemberInvitations(MemberRepository members, MemberCrypto crypto, MemberAccessState access,
            MemberInvitationRows invitations, InvitationDeliveries deliveries, SignInAccounts accounts,
            MemberAuditPublisher audit, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${nadel.web.url}") String webUrl, @Value("${nadel.invitations.ttl}") Duration ttl) {
        this.members = members;
        this.crypto = crypto;
        this.access = access;
        this.invitations = invitations;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
        String base = webUrl.strip();
        this.setPasswordUrl = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/set-password";
        this.ttl = ttl;
    }

    /**
     * Invites the member of the actor's cooperative and answers the member's new access ({@code INVITED}). The actor
     * holds {@code MEMBERS_VIEW} and {@code MEMBERS_INVITE} (checked on the controller).
     */
    nadel.contract.model.MemberAccess invite(UUID memberId, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        Candidate candidate = reading.execute(status -> candidate(tenantId, memberId));
        if (!deliveries.emailReady(tenantId)) {
            throw notConfigured();
        }
        String accountId = ensureAccount(candidate);

        String token = InvitationTokens.newToken();
        Instant created = clock.instant();
        Instant expiresAt = created.plus(ttl);
        UUID invitationId = writing.execute(status -> prepare(candidate, accountId,
                InvitationTokens.hash(token), created, expiresAt, actor));

        InvitationDeliveryResult result;
        try {
            result = deliveries.deliver(tenantId, memberId, candidate.email(),
                    URI.create(setPasswordUrl + "?token=" + token), expiresAt);
        } catch (RuntimeException e) {
            revoke(invitationId);
            throw e;
        }
        if (!result.isSent()) {
            revoke(invitationId);
            log.info("Invitation of member {} of cooperative {} not sent: {}", memberId, tenantId, result.failure());
            throw result.failure() == InvitationDeliveryResult.Failure.NOT_CONFIGURED ? notConfigured()
                    : ApiProblemException.invitationNotSent(result.failure().name());
        }
        nadel.contract.model.MemberAccess answer = writing.execute(status -> complete(tenantId, memberId,
                invitationId, result, actor));
        log.info("Invited member {} of cooperative {}", memberId, tenantId);
        return answer;
    }

    /** The e-mail a usable link was sent to (the login); 404 {@code INVITATION_INVALID} for every other link. */
    InvitationInfo inspect(String token) {
        byte[] hash = InvitationTokens.hash(token);
        UUID tenantId = tenantOf(hash);
        return TenantContext.runAs(tenantId, () -> reading.execute(status -> {
            Invitation invitation = invitations.findByHash(hash).orElseThrow(MemberInvitations::invalid);
            Member member = members.findById(invitation.memberId()).orElseThrow(MemberInvitations::invalid);
            requireUsable(invitation, member);
            return new InvitationInfo(Objects.requireNonNull(crypto.email(member)));
        }));
    }

    /**
     * Sets the member's password with a usable link and gives them access; 404 {@code INVITATION_INVALID} for every
     * other link, 400 {@code PASSWORD_POLICY} when Keycloak refuses the password (the link stays usable).
     */
    void accept(String token, String password) {
        byte[] hash = InvitationTokens.hash(token);
        UUID tenantId = tenantOf(hash);
        UUID memberId = TenantContext.runAs(tenantId, () -> writing.execute(status -> {
            Invitation found = invitations.findByHash(hash).orElseThrow(MemberInvitations::invalid);
            // the member first, then the link: the order the invitation itself locks them in
            Member member = members.findByIdForUpdate(found.memberId()).orElseThrow(MemberInvitations::invalid);
            Invitation invitation = invitations.lock(found.id()).orElseThrow(MemberInvitations::invalid);
            requireUsable(invitation, member);
            String subject = member.getKeycloakSubject();
            try {
                accounts.resetPassword(subject, password);
                accounts.update(subject, SignInAccountChange.activated());
            } catch (SignInAccountMissingException e) {
                log.warn("Invitation of member {} of cooperative {}: the sign-in account is missing", member.getId(),
                        tenantId);
                throw invalid();
            } catch (ApiProblemException e) {
                if (ProblemCodes.SIGN_IN_ACCOUNT_IN_USE.equals(e.code())) {
                    throw invalid(); // the account no longer belongs to this cooperative
                }
                throw e;
            }
            Instant now = clock.instant();
            invitations.markUsed(invitation.id(), now);
            access.set(tenantId, member.getId(), State.ACTIVE, now);
            return member.getId();
        }));
        log.info("Member {} of cooperative {} set the password of the invitation", memberId, tenantId);
    }

    /** Step 1: the member as it can be invited now, or the reason it cannot. */
    private Candidate candidate(UUID tenantId, UUID memberId) {
        Member member = members.findById(memberId).orElseThrow(ApiProblemException::notFound);
        requireInvitable(member, access.find(memberId));
        String email = PhoneIndexValues.normalisedEmail(crypto.email(member));
        if (email == null || member.getEmailBidx() == null) {
            throw conflict(ProblemCodes.MEMBER_EMAIL_MISSING, "The member has no e-mail; it is the login.");
        }
        return new Candidate(tenantId, memberId, email, member.getEmailBidx(), member.getFirstName(),
                member.getLastName(), member.getKeycloakSubject());
    }

    /**
     * Step 2, outside any transaction: the Keycloak account of the member's e-mail.
     * <ul>
     *   <li>an account of another cooperative, or of none, has the e-mail: 409 {@code EMAIL_IN_OTHER_COOPERATIVE}
     *       (which one is never said);</li>
     *   <li>an account of this cooperative has it: it is linked (and turned on), unless another member of the
     *       cooperative is linked to it, or this member is linked to another account that still exists: 409
     *       {@code SIGN_IN_ACCOUNT_IN_USE};</li>
     *   <li>none has it: a new account (no password, the e-mail not verified).</li>
     * </ul>
     */
    private String ensureAccount(Candidate candidate) {
        List<SignInAccount> found = accounts.findByLogin(candidate.email());
        if (found.stream().anyMatch(account -> !account.belongsTo(candidate.tenantId()))) {
            throw emailInOtherCooperative();
        }
        if (found.size() > 1) {
            throw accountInUse();
        }
        SignInAccount account = found.isEmpty()
                ? accounts.create(candidate.email(), candidate.firstName(), candidate.lastName())
                : found.getFirst();
        if (!account.belongsTo(candidate.tenantId())) {
            throw emailInOtherCooperative(); // created meanwhile in another cooperative
        }
        Optional<UUID> holder = reading.execute(status -> members.findAnyBySubject(candidate.tenantId(),
                account.id()).map(Member::getId));
        if (holder.isPresent() && !holder.get().equals(candidate.memberId())) {
            throw accountInUse();
        }
        if (candidate.subject() != null && !candidate.subject().equals(account.id())
                && accounts.get(candidate.subject()).isPresent()) {
            throw accountInUse();
        }
        if (!account.enabled()) {
            accounts.update(account.id(), SignInAccountChange.enable());
        }
        return account.id();
    }

    /** Step 3: under the member's lock, checks again, links the account and inserts the unsent link. */
    private UUID prepare(Candidate candidate, String accountId, byte[] tokenHash, Instant created, Instant expiresAt,
            AccessProfile actor) {
        Member member = members.findByIdForUpdate(candidate.memberId()).orElseThrow(ApiProblemException::notFound);
        Optional<Row> row = access.find(member.getId());
        requireInvitable(member, row);
        if (!accountId.equals(member.getKeycloakSubject())) {
            if (!Objects.equals(member.getKeycloakSubject(), candidate.subject())) {
                throw accountInUse(); // linked meanwhile by a sign-in
            }
            member.linkTo(accountId);
            try {
                members.saveAndFlush(member);
            } catch (DataIntegrityViolationException e) {
                if (ConstraintViolations.violates(e, SUBJECT_INDEX)) {
                    throw accountInUse();
                }
                throw e;
            }
        }
        if (row.isEmpty()) {
            // a linked member without a row reads as ACTIVE: keep it NOT_INVITED until the letter is sent
            access.set(candidate.tenantId(), member.getId(), State.NONE, created);
        }
        return invitations.insertPending(candidate.tenantId(), member.getId(), tokenHash, candidate.emailBidx(),
                created, expiresAt, actor.memberId());
    }

    /** Step 5: the letter went out. */
    private nadel.contract.model.MemberAccess complete(UUID tenantId, UUID memberId, UUID invitationId,
            InvitationDeliveryResult result, AccessProfile actor) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiProblemException::notFound);
        Instant now = clock.instant();
        invitations.markSent(invitationId, memberId, now);
        access.set(tenantId, memberId, State.INVITED, now);
        String channels = result.channels().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        audit.publish(tenantId, new Actor(actor.memberId(), actor.subject(), actor.displayName()), INVITED_ACTION,
                member, List.of(new FieldChange("channels", null, channels)));
        return access.view(member, true, () -> deliveries.emailReady(tenantId));
    }

    private void revoke(UUID invitationId) {
        writing.executeWithoutResult(status -> invitations.revoke(invitationId, clock.instant()));
    }

    /** The cooperative of a token hash, read before one is bound; 404 when no link has the hash. */
    private UUID tenantOf(byte[] hash) {
        return Objects.requireNonNull(reading.execute(status -> invitations.tenantOf(hash)))
                .orElseThrow(MemberInvitations::invalid);
    }

    private void requireUsable(Invitation invitation, Member member) {
        boolean usable = invitation.usable(clock.instant())
                && !member.isArchived()
                && member.getKeycloakSubject() != null
                && Arrays.equals(invitation.emailBidx(), member.getEmailBidx())
                && MemberAccessState.status(member, access.find(member.getId())) == MemberAccessStatus.INVITED;
        if (!usable) {
            throw invalid();
        }
    }

    private static void requireInvitable(Member member, Optional<Row> row) {
        if (member.isArchived()) {
            throw conflict(ProblemCodes.MEMBER_ARCHIVED, "The member is archived.");
        }
        switch (MemberAccessState.status(member, row)) {
            case INVITED -> throw conflict(ProblemCodes.INVITATION_PENDING,
                    "The member is already invited; send the invitation again instead.");
            case ACTIVE -> throw conflict(ProblemCodes.ACCESS_ALREADY_ACTIVE, "The member already has access.");
            default -> { }
        }
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private static ApiProblemException notConfigured() {
        return conflict(ProblemCodes.DELIVERY_NOT_CONFIGURED, "The cooperative's mailbox is not set up.");
    }

    private static ApiProblemException emailInOtherCooperative() {
        return conflict(ProblemCodes.EMAIL_IN_OTHER_COOPERATIVE, "The e-mail is already a login elsewhere.");
    }

    private static ApiProblemException accountInUse() {
        return conflict(ProblemCodes.SIGN_IN_ACCOUNT_IN_USE, "The sign-in account is linked to another member.");
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.INVITATION_INVALID,
                "The invitation link cannot be used.");
    }

    /** What step 1 read: personal data, so {@link #toString()} prints ids only. */
    private record Candidate(UUID tenantId, UUID memberId, String email, byte[] emailBidx, String firstName,
            String lastName, @Nullable String subject) {

        @Override
        public String toString() {
            return "Candidate[tenantId=" + tenantId + ", memberId=" + memberId + "]";
        }
    }
}
