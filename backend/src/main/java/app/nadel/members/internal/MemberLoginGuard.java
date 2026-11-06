package app.nadel.members.internal;

import app.nadel.identity.SignInAccount;
import app.nadel.identity.SignInAccounts;
import app.nadel.privacy.IndexKind;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pre-check of an e-mail change (feature 020, technical considerations 2.1): the e-mail is the login of a member
 * with access or an open invitation, so before {@code PUT /members/{id}} changes it, the new address is looked up in
 * Keycloak, synchronously and outside any transaction. A login of another cooperative (or of none) is 409
 * {@code EMAIL_IN_OTHER_COOPERATIVE} on the field {@code email}, never saying which; an account of this cooperative that
 * is not the member's own is 409 {@code SIGN_IN_ACCOUNT_IN_USE}; Keycloak unreachable is 503
 * {@code SIGN_IN_SERVICE_UNAVAILABLE}. Members without access, archived members and unchanged e-mails need no lookup,
 * so editing them never depends on Keycloak. The update transaction then decides again under the member's lock.
 */
@Component
class MemberLoginGuard {

    private final MemberRepository members;
    private final MemberCrypto crypto;
    private final MemberAccessRevocation revocation;
    private final SignInAccounts accounts;
    private final TransactionTemplate reading;

    MemberLoginGuard(MemberRepository members, MemberCrypto crypto, MemberAccessRevocation revocation,
            SignInAccounts accounts, PlatformTransactionManager transactionManager) {
        this.members = members;
        this.crypto = crypto;
        this.revocation = revocation;
        this.accounts = accounts;
        this.reading = new TransactionTemplate(transactionManager);
        this.reading.setReadOnly(true);
    }

    /**
     * Refuses a new e-mail that cannot become the member's login. Does nothing when the member is unknown (the update
     * answers 404), archived, without access or invitation, or the e-mail is removed or unchanged.
     */
    void ensureLoginFree(UUID tenantId, UUID memberId, @Nullable String email) {
        String login = PhoneIndexValues.normalisedEmail(email);
        if (login == null) {
            return;
        }
        Optional<Holder> holder = Objects.requireNonNull(reading.execute(status -> members.findById(memberId)
                .filter(member -> !member.isArchived())
                .filter(member -> !Arrays.equals(member.getEmailBidx(),
                        crypto.index(tenantId, IndexKind.EMAIL, login)))
                .filter(revocation::hasAccessOrInvitation)
                .map(member -> new Holder(member.getKeycloakSubject()))));
        if (holder.isEmpty()) {
            return;
        }
        List<SignInAccount> found = accounts.findByLogin(login);
        if (found.stream().anyMatch(account -> !account.belongsTo(tenantId))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.EMAIL_IN_OTHER_COOPERATIVE,
                    "The e-mail is already a login elsewhere.",
                    List.of(new FieldProblem("email", ProblemCodes.EMAIL_IN_OTHER_COOPERATIVE)));
        }
        if (found.stream().anyMatch(account -> !account.id().equals(holder.get().subject()))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.SIGN_IN_ACCOUNT_IN_USE,
                    "A sign-in account with this e-mail belongs to someone else.");
        }
    }

    /** The member's Keycloak subject; null when not linked yet. */
    private record Holder(@Nullable String subject) {
    }
}
