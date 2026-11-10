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
import java.util.HexFormat;
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
 *       ({@code keycloak_subject}) and inserts the new link unsent; for an invitation (never invited, or access turned
 *       off) of an account that existed already, outside any transaction the account is then locked out
 *       ({@link #lockOut}: off, no credential, no session), so only the new link lets the member in;</li>
 *   <li>outside any transaction, {@link InvitationDeliveries#deliver} sends the letter;</li>
 *   <li>a transaction locks the member and checks again (a disable, archive or e-mail change made while the letter was
 *       on its way wins: 409, the unsent link is revoked, nothing is written or audited), then marks the link sent,
 *       revokes the member's older links, sets {@code INVITED} and publishes the audit event {@code MEMBER_INVITED}
 *       (field {@code channels} only).</li>
 * </ol>
 * When the letter is not sent a compensating transaction revokes the unsent link and the answer is 502
 * {@code INVITATION_NOT_SENT} with the failure code: the access state is unchanged, nothing is audited and an earlier
 * link still works.
 * <p>
 * <b>Resend</b> runs the same steps for a member who is {@code INVITED} ({@code MEMBER_INVITE_RESENT}); after an e-mail
 * change {@link MemberAccessListener} runs them without an actor and without an audit entry. <b>Disable</b> stores
 * {@code DISABLED}, revokes the open links and leaves the Keycloak account to the listener
 * ({@link MemberAccessRevocation}).
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
    static final String RESENT_ACTION = "MEMBER_INVITE_RESENT";
    static final String DISABLED_ACTION = "MEMBER_ACCESS_DISABLED";
    private static final String SUBJECT_INDEX = "member_tenant_subject_uq";

    private final MemberRepository members;
    private final MemberCrypto crypto;
    private final MemberAccessState access;
    private final MemberInvitationRows invitations;
    private final InvitationDeliveries deliveries;
    private final SignInAccounts accounts;
    private final MemberAuditPublisher audit;
    private final MemberAccessRevocation revocation;
    private final LastChairmanGuard lastChairman;
    private final Clock clock;
    private final TransactionTemplate reading;
    private final TransactionTemplate writing;
    private final String setPasswordUrl;
    private final Duration ttl;

    MemberInvitations(MemberRepository members, MemberCrypto crypto, MemberAccessState access,
            MemberInvitationRows invitations, InvitationDeliveries deliveries, SignInAccounts accounts,
            MemberAuditPublisher audit, MemberAccessRevocation revocation, LastChairmanGuard lastChairman,
            Clock clock, PlatformTransactionManager transactionManager,
            @Value("${nadel.web.url}") String webUrl, @Value("${nadel.invitations.ttl}") Duration ttl) {
        this.members = members;
        this.crypto = crypto;
        this.access = access;
        this.invitations = invitations;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.audit = audit;
        this.revocation = revocation;
        this.lastChairman = lastChairman;
        this.clock = clock;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
        String base = webUrl.strip();
        this.setPasswordUrl = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/set-password";
        this.ttl = ttl;
    }

    /**
     * Where the first-chairman command puts a link when the cooperative has no mailbox ({@link FirstChairman}): the
     * operator's terminal. Called at most once per link, never with a logger.
     */
    @FunctionalInterface
    interface LinkPrinter {
        void print(URI link, Instant expiresAt);
    }

    /** Why a link is issued: the first invitation, the board's resend, or the listener after an e-mail change. */
    enum Purpose {
        /** {@code POST /members/{id}/invitation}: never invited, or access turned off. */
        INVITE,
        /** {@code POST /members/{id}/invitation/resend}: still {@code INVITED}. */
        RESEND,
        /** {@link MemberAccessListener} after the e-mail changed: {@code INVITED}, no audit entry of its own. */
        NEW_LOGIN
    }

    /**
     * Invites the member of the actor's cooperative and answers the member's new access ({@code INVITED}). The actor
     * holds {@code MEMBERS_VIEW} and {@code MEMBERS_INVITE} (checked on the controller).
     */
    nadel.contract.model.MemberAccess invite(UUID memberId, AccessProfile actor) {
        return issue(Objects.requireNonNull(actor.tenantId(), "tenantId"), memberId, actor, Purpose.INVITE, null);
    }

    /**
     * The invitation of the first-chairman command ({@link FirstChairman}), with the cooperative bound by the caller:
     * {@link #invite}, or {@link #resend} when {@code resend}, with one difference. When the cooperative's mailbox is
     * not set up (a new cooperative never has one) the link goes to {@code printer} instead of a letter, and the audit
     * entry has no {@code channels} field; with a mailbox the letter is sent as usual. The HTTP endpoints never pass a
     * printer, so without a mailbox they still answer 409 {@code DELIVERY_NOT_CONFIGURED}.
     */
    nadel.contract.model.MemberAccess issueForCommand(UUID memberId, AccessProfile actor, boolean resend,
            LinkPrinter printer) {
        return issue(Objects.requireNonNull(actor.tenantId(), "tenantId"), memberId, actor,
                resend ? Purpose.RESEND : Purpose.INVITE, Objects.requireNonNull(printer, "printer"));
    }

    /**
     * Sends an invited member a new link (feature 020, technical considerations 2.4): the same two phases as
     * {@link #invite}; the member must be {@code INVITED} (409 {@code NOT_INVITED}). Only a sent letter revokes the
     * older links, so when it fails (502 {@code INVITATION_NOT_SENT}) the previous link still works. Audited as
     * {@code MEMBER_INVITE_RESENT} with the channels.
     */
    nadel.contract.model.MemberAccess resend(UUID memberId, AccessProfile actor) {
        return issue(Objects.requireNonNull(actor.tenantId(), "tenantId"), memberId, actor, Purpose.RESEND, null);
    }

    /**
     * The new link after the member's e-mail changed ({@link MemberAccessListener}), with the cooperative bound by the
     * caller: the same two phases as {@link #resend}, without an actor and without an audit entry (the e-mail change
     * itself is audited as {@code MEMBER_UPDATED}). Throws like {@link #resend}.
     */
    void sendForNewLogin(UUID tenantId, UUID memberId) {
        issue(tenantId, memberId, null, Purpose.NEW_LOGIN, null);
        log.info("New login link sent to member {} of cooperative {}", memberId, tenantId);
    }

    /**
     * Turns the member's access off (feature 020, technical considerations 2.1): {@code INVITED} or {@code ACTIVE} (a
     * legacy link included) becomes {@code DISABLED}, the open links are revoked and {@link MemberAccessRevoked} asks
     * {@link MemberAccessListener} to turn the Keycloak account off. Already {@code DISABLED} is a no-op without an
     * audit entry; never invited is 409 {@code ACCESS_NOT_GRANTED}. Audited as {@code MEMBER_ACCESS_DISABLED}.
     * <p>
     * The actor's own access is 409 {@code OWN_ACCESS}, and the access of the last active chairman whose access is on
     * is 409 {@code LAST_CHAIRMAN} ({@link LastChairmanGuard#ensureNotLastWithAccess}): a cooperative never locks its
     * board out. The chairman lock is taken before the member row, the order of the archive and of a role change.
     */
    nadel.contract.model.MemberAccess disable(UUID memberId, AccessProfile actor) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        if (memberId.equals(actor.memberId())) {
            throw conflict(ProblemCodes.OWN_ACCESS, "A member cannot turn off their own access.");
        }
        nadel.contract.model.MemberAccess answer = writing.execute(status -> {
            lastChairman.lock(tenantId);
            Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiProblemException::notFound);
            MemberAccessStatus current = MemberAccessState.status(member, access.find(memberId));
            if (current == MemberAccessStatus.NOT_INVITED) {
                throw conflict(ProblemCodes.ACCESS_NOT_GRANTED, "The member was never invited.");
            }
            if (current != MemberAccessStatus.DISABLED) {
                lastChairman.ensureNotLastWithAccess(tenantId, memberId);
                revocation.revoke(member, clock.instant());
                audit.publish(tenantId, actorOf(actor), DISABLED_ACTION, member, List.of());
                log.info("Access of member {} of cooperative {} disabled", memberId, tenantId);
            }
            String email = crypto.email(member);
            return access.view(member, email != null && !email.isBlank(), () -> deliveries.emailReady(tenantId));
        });
        return Objects.requireNonNull(answer);
    }

    /**
     * Both phases of a new link; see the class comment. {@code actor} is null only for {@link Purpose#NEW_LOGIN};
     * {@code printer} is set only by {@link #issueForCommand} and takes the link instead of a letter when there is no
     * mailbox.
     */
    private nadel.contract.model.MemberAccess issue(UUID tenantId, UUID memberId, @Nullable AccessProfile actor,
            Purpose purpose, @Nullable LinkPrinter printer) {
        Candidate candidate = reading.execute(status -> candidate(tenantId, memberId, purpose));
        boolean mailboxReady = deliveries.emailReady(tenantId);
        if (!mailboxReady && printer == null) {
            throw notConfigured();
        }
        EnsuredAccount account = ensureAccount(candidate);
        String accountId = account.id();

        String token = InvitationTokens.newToken();
        Instant created = clock.instant();
        Instant expiresAt = created.plus(ttl);
        UUID invitationId = writing.execute(status -> prepare(candidate, accountId,
                InvitationTokens.hash(token), created, expiresAt, actor, purpose));
        if (purpose == Purpose.INVITE && account.existing()) {
            try {
                lockOut(tenantId, memberId, accountId);
            } catch (RuntimeException e) {
                revoke(invitationId);
                throw e;
            }
        }

        URI link = URI.create(setPasswordUrl + "?token=" + token);
        if (!mailboxReady) {
            try {
                Objects.requireNonNull(printer).print(link, expiresAt);
            } catch (RuntimeException e) {
                revoke(invitationId);
                throw e;
            }
            nadel.contract.model.MemberAccess answer = completeOrRevoke(tenantId, memberId, invitationId, List.of(),
                    actor, purpose);
            log.info("Invitation ({}) of member {} of cooperative {} handed to the operator", purpose, memberId,
                    tenantId);
            return answer;
        }
        InvitationDeliveryResult result;
        try {
            result = deliveries.deliver(tenantId, memberId, candidate.email(), link, expiresAt);
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
        String channels = result.channels().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        nadel.contract.model.MemberAccess answer = completeOrRevoke(tenantId, memberId, invitationId,
                List.of(new FieldChange("channels", null, channels)), actor, purpose);
        log.info("Invitation ({}) of member {} of cooperative {} sent", purpose, memberId, tenantId);
        return answer;
    }

    /**
     * The key the e-mail limit of the public endpoints counts a link under ({@link InvitationRateLimiter}): the
     * cooperative and the blind index of the e-mail the link was made for, so every link of one e-mail shares it. Empty
     * for a token no link has. Never the e-mail itself, and never logged.
     */
    Optional<String> rateLimitKey(String token) {
        byte[] hash = InvitationTokens.hash(token);
        Optional<UUID> tenantId = Objects.requireNonNull(reading.execute(status -> invitations.tenantOf(hash)));
        return tenantId.flatMap(tenant -> Objects.requireNonNull(TenantContext.runAs(tenant,
                () -> reading.execute(status -> invitations.findByHash(hash))))
                .map(invitation -> tenant + "|" + HexFormat.of().formatHex(invitation.emailBidx())));
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
    private Candidate candidate(UUID tenantId, UUID memberId, Purpose purpose) {
        Member member = members.findById(memberId).orElseThrow(ApiProblemException::notFound);
        requireIssuable(member, access.find(memberId), purpose);
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
     *   <li>an account of this cooperative has it: it is linked, unless another member of the cooperative is linked
     *       to it, or this member is linked to another account that still exists: 409
     *       {@code SIGN_IN_ACCOUNT_IN_USE};</li>
     *   <li>none has it: a new account (no password, the e-mail not verified).</li>
     * </ul>
     * The account is never turned on here: only {@link #accept} does, once the member set the password with the link.
     * An account that was turned off (access disabled, the member archived) stays off until then.
     */
    private EnsuredAccount ensureAccount(Candidate candidate) {
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
        return new EnsuredAccount(account.id(), !found.isEmpty());
    }

    /**
     * Step 3a of an invitation to an account that existed already (the member's access was turned off, or an account
     * made before): the account is turned off, every credential is removed and every session ended, in this order, so a
     * password the member had before no longer signs in and only the new link lets them in ({@link #accept} sets the
     * new password and turns the account on). Runs after step 3, when the member is known to be invitable and the new
     * link is not sent yet (nothing can accept it meanwhile). An account removed meanwhile needs nothing: the link then
     * cannot be accepted.
     */
    private void lockOut(UUID tenantId, UUID memberId, String accountId) {
        try {
            accounts.update(accountId, SignInAccountChange.disable());
            accounts.removeCredentials(accountId);
            accounts.logout(accountId);
            log.info("Sign-in account of member {} of cooperative {} locked out until the new link is used", memberId,
                    tenantId);
        } catch (SignInAccountMissingException e) {
            log.info("Member {} of cooperative {} has no sign-in account to lock out", memberId, tenantId);
        }
    }

    /** Step 3: under the member's lock, checks again, links the account and inserts the unsent link. */
    private UUID prepare(Candidate candidate, String accountId, byte[] tokenHash, Instant created, Instant expiresAt,
            @Nullable AccessProfile actor, Purpose purpose) {
        Member member = members.findByIdForUpdate(candidate.memberId()).orElseThrow(ApiProblemException::notFound);
        Optional<Row> row = access.find(member.getId());
        requireIssuable(member, row, purpose);
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
                created, expiresAt, actor == null ? null : actor.memberId());
    }

    /**
     * Step 5 in its own transaction; when it refuses (the member changed while the letter was on its way), a
     * compensating transaction revokes the unsent link, as for a letter that was not sent, and the refusal is thrown.
     */
    private nadel.contract.model.MemberAccess completeOrRevoke(UUID tenantId, UUID memberId, UUID invitationId,
            List<FieldChange> changes, @Nullable AccessProfile actor, Purpose purpose) {
        try {
            return Objects.requireNonNull(writing.execute(status -> complete(tenantId, memberId, invitationId,
                    changes, actor, purpose)));
        } catch (ApiProblemException e) {
            revoke(invitationId);
            log.info("Invitation ({}) of member {} of cooperative {} went out but was refused: {}", purpose,
                    memberId, tenantId, e.code());
            throw e;
        }
    }

    /**
     * Step 5: the letter went out (or the command printed the link). {@code changes} are the audit fields:
     * {@code channels} for a letter, none for a printed link.
     * <p>
     * The letter is sent without a lock, so the board may have disabled or archived the member, or changed the
     * e-mail, meanwhile (each revokes the unused links under the same member lock). Under the lock the member and the
     * link are checked again before anything is written: the member still issuable for {@code purpose} (409
     * {@code MEMBER_ARCHIVED}, {@code NOT_INVITED}, {@code INVITATION_PENDING} or {@code ACCESS_ALREADY_ACTIVE}), and
     * the link still unsent, unused, not revoked and made for the member's current e-mail (otherwise 409
     * {@code INVITATION_PENDING} for a resend or a new login, whose e-mail change sends its own link, and 409
     * {@code NOT_INVITED} for an invitation). A refusal writes neither {@code INVITED} nor an audit entry.
     */
    private nadel.contract.model.MemberAccess complete(UUID tenantId, UUID memberId, UUID invitationId,
            List<FieldChange> changes, @Nullable AccessProfile actor, Purpose purpose) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(ApiProblemException::notFound);
        requireIssuable(member, access.find(memberId), purpose);
        // the member first, then the link: the order accept locks them in
        Invitation invitation = invitations.lock(invitationId).orElseThrow(ApiProblemException::notFound);
        if (invitation.sentAt() != null || invitation.usedAt() != null || invitation.revokedAt() != null
                || !Arrays.equals(invitation.emailBidx(), member.getEmailBidx())) {
            throw conflict(purpose == Purpose.INVITE ? ProblemCodes.NOT_INVITED : ProblemCodes.INVITATION_PENDING,
                    "The member changed while the invitation was on its way.");
        }
        Instant now = clock.instant();
        invitations.markSent(invitationId, memberId, now);
        access.set(tenantId, memberId, State.INVITED, now);
        if (actor != null && purpose != Purpose.NEW_LOGIN) {
            audit.publish(tenantId, actorOf(actor), purpose == Purpose.RESEND ? RESENT_ACTION : INVITED_ACTION,
                    member, changes);
        }
        return access.view(member, true, () -> deliveries.emailReady(tenantId));
    }

    private static Actor actorOf(AccessProfile actor) {
        return new Actor(actor.memberId(), actor.subject(), actor.displayName());
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

    private static void requireIssuable(Member member, Optional<Row> row, Purpose purpose) {
        if (member.isArchived()) {
            throw conflict(ProblemCodes.MEMBER_ARCHIVED, "The member is archived.");
        }
        if (purpose != Purpose.INVITE) {
            if (MemberAccessState.status(member, row) != MemberAccessStatus.INVITED) {
                throw conflict(ProblemCodes.NOT_INVITED, "The member is not invited; invite them instead.");
            }
            return;
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

    /** The account of step 2 and whether it existed before (otherwise step 2 made it, without a password). */
    private record EnsuredAccount(String id, boolean existing) {
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
