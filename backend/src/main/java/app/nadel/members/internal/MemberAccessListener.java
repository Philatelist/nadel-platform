package app.nadel.members.internal;

import app.nadel.identity.SignInAccountChange;
import app.nadel.identity.SignInAccountMissingException;
import app.nadel.identity.SignInAccounts;
import app.nadel.members.MemberAccessRevoked;
import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.MemberAccessStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the Keycloak account in line with a member's stored access (feature 020, technical considerations 2.1), after
 * the transaction that changed it committed.
 * <p>
 * The ADR 0007 listener pattern: {@code @Async @TransactionalEventListener} without {@code @Transactional}; the event's
 * cooperative is bound ({@link TenantContext#runAs}) before a short read-only transaction re-reads the member, and the
 * Keycloak calls run outside any transaction. The Spring Modulith registry keeps the publication until a method
 * returns, so a failure that may pass (Keycloak down: 503 {@code SIGN_IN_SERVICE_UNAVAILABLE}, a letter not sent: 502
 * {@code INVITATION_NOT_SENT}) is thrown and the publication is resubmitted; a refusal that will not pass (the account
 * is of another cooperative, the login is taken, the member changed meanwhile) is logged and the publication completes.
 * <p>
 * Idempotent, because each event only names the member and the work follows what is stored now:
 * <ul>
 *   <li>{@link MemberAccessRevoked}: only while the access is still {@code DISABLED}, the account is turned off and its
 *       sessions are ended; a missing account needs nothing;</li>
 *   <li>{@link MemberLoginChanged}: only while the access is still {@code INVITED}, the account's user name and e-mail
 *       become the member's current e-mail (not verified), the account is turned off, its credentials are removed and
 *       its sessions end, in this order (owner decision on PR 71: a password of the old login never signs in under the
 *       new one; only the new link turns the account on again, with a new password); then, when the cooperative's
 *       mailbox is set up and the member has no open link for that e-mail yet, a new link is sent through
 *       {@link MemberInvitations#sendForNewLogin} (each retry makes a new token). Without a mailbox the member stays
 *       {@code INVITED} without a link until the board sends one.</li>
 * </ul>
 * Logs carry ids and problem codes only: never an e-mail or a link.
 */
@Component
class MemberAccessListener {

    private static final Logger log = LoggerFactory.getLogger(MemberAccessListener.class);

    private final MemberRepository members;
    private final MemberCrypto crypto;
    private final MemberAccessState access;
    private final MemberInvitationRows invitations;
    private final MemberInvitations links;
    private final InvitationDeliveries deliveries;
    private final SignInAccounts accounts;
    private final Clock clock;
    private final TransactionTemplate reading;

    MemberAccessListener(MemberRepository members, MemberCrypto crypto, MemberAccessState access,
            MemberInvitationRows invitations, MemberInvitations links, InvitationDeliveries deliveries,
            SignInAccounts accounts, Clock clock, PlatformTransactionManager transactionManager) {
        this.members = members;
        this.crypto = crypto;
        this.access = access;
        this.invitations = invitations;
        this.links = links;
        this.deliveries = deliveries;
        this.accounts = accounts;
        this.clock = clock;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
    }

    @Async
    @TransactionalEventListener
    void on(MemberAccessRevoked event) {
        revoked(event);
    }

    @Async
    @TransactionalEventListener
    void on(MemberLoginChanged event) {
        loginChanged(event);
    }

    /** Turns the account off while the access is still {@code DISABLED}. */
    void revoked(MemberAccessRevoked event) {
        TenantContext.runAs(event.tenantId(), () -> {
            Optional<String> subject = reading.execute(status -> members.findById(event.memberId())
                    .filter(member -> status(member) == MemberAccessStatus.DISABLED)
                    .map(Member::getKeycloakSubject));
            if (subject == null || subject.isEmpty()) {
                return null;
            }
            try {
                accounts.update(subject.get(), SignInAccountChange.disable());
                accounts.logout(subject.get());
                log.info("Sign-in account of member {} of cooperative {} turned off", event.memberId(),
                        event.tenantId());
            } catch (SignInAccountMissingException e) {
                log.info("Member {} of cooperative {} has no sign-in account to turn off", event.memberId(),
                        event.tenantId());
            } catch (ApiProblemException e) {
                passOn(e, event.tenantId(), event.memberId());
            }
            return null;
        });
    }

    /**
     * Moves the account to the new login, locks it out (off, no credential, no session) and sends a new link while the
     * access is still {@code INVITED}.
     */
    void loginChanged(MemberLoginChanged event) {
        TenantContext.runAs(event.tenantId(), () -> {
            Optional<Target> found = reading.execute(status -> members.findById(event.memberId())
                    .filter(member -> !member.isArchived() && status(member) == MemberAccessStatus.INVITED)
                    .map(this::target));
            if (found == null || found.isEmpty()) {
                return null;
            }
            Target target = found.get();
            if (target.subject() != null && target.email() != null) {
                try {
                    accounts.update(target.subject(), SignInAccountChange.newLogin(target.email()));
                    accounts.removeCredentials(target.subject());
                    accounts.logout(target.subject());
                } catch (SignInAccountMissingException e) {
                    // the new link makes a new account for the new e-mail
                    log.info("Member {} of cooperative {} has no sign-in account to move", event.memberId(),
                            event.tenantId());
                } catch (ApiProblemException e) {
                    passOn(e, event.tenantId(), event.memberId());
                    return null;
                }
            }
            if (target.email() == null || target.linkOpen() || !deliveries.emailReady(event.tenantId())) {
                return null;
            }
            try {
                links.sendForNewLogin(event.tenantId(), event.memberId());
            } catch (ApiProblemException e) {
                passOn(e, event.tenantId(), event.memberId());
            }
            return null;
        });
    }

    private MemberAccessStatus status(Member member) {
        return MemberAccessState.status(member, access.find(member.getId()));
    }

    private Target target(Member member) {
        String email = PhoneIndexValues.normalisedEmail(crypto.email(member));
        boolean open = member.getEmailBidx() != null
                && invitations.hasOpen(member.getId(), member.getEmailBidx(), clock.instant());
        return new Target(member.getKeycloakSubject(), email, open);
    }

    /** Throws what may pass, so the publication is resubmitted; logs what will not. */
    private static void passOn(ApiProblemException e, UUID tenantId, UUID memberId) {
        if (e.status().is5xxServerError()) {
            log.warn("Sign-in account work for member {} of cooperative {} failed ({}); it will be retried", memberId,
                    tenantId, e.code());
            throw e;
        }
        log.warn("Sign-in account work for member {} of cooperative {} refused ({})", memberId, tenantId, e.code());
    }

    /** What the listener needs of the member: personal data, so {@link #toString()} prints none. */
    private record Target(@Nullable String subject, @Nullable String email, boolean linkOpen) {

        @Override
        public String toString() {
            return "Target[linkOpen=" + linkOpen + "]";
        }
    }
}
