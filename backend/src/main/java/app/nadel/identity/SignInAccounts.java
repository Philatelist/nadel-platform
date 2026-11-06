package app.nadel.identity;

import java.util.List;
import java.util.Optional;

/**
 * The sign-in accounts of Keycloak (feature 020), through its Admin API: the application creates the account of an
 * invited member, links an existing one, sets the password the member chose on the set-password page and turns an
 * account on or off. Implemented by {@code identity.internal.KeycloakAdminClient} with a service account that may only
 * manage the users of the realm; role mappings are never touched (cooperative roles live in the database, ADR 0009).
 * <p>
 * <b>Tenant guard.</b> Every change to an existing account ({@link #update}, {@link #resetPassword}, {@link #logout})
 * first reads it and refuses with 409 {@code SIGN_IN_ACCOUNT_IN_USE} when its {@code tenant_id} attribute is not the
 * cooperative bound by {@code TenantContext}; {@link #create} writes the bound cooperative. A caller without a bound
 * cooperative is a programming error ({@link IllegalStateException}).
 * <p>
 * <b>Failures</b> are {@code problem.ApiProblemException}s: 503 {@code SIGN_IN_SERVICE_UNAVAILABLE} when Keycloak cannot
 * be reached, does not answer in time, answers 5xx or refuses the service account (the log line carries the HTTP status
 * only); 400 {@code VALIDATION_FAILED} with the field {@code password} and the code {@code PASSWORD_POLICY} when
 * Keycloak refuses a password by the realm's policy. A change to an account that does not exist (any more) is
 * {@link SignInAccountMissingException}. Request and response bodies are never logged: they hold e-mails and passwords.
 * <p>
 * Every method blocks on the network: call it outside a database transaction where the flow allows it.
 */
public interface SignInAccounts {

    /**
     * The accounts whose e-mail or user name is exactly {@code login} (Keycloak compares them in lower case), each once;
     * empty when there is none. The realm refuses duplicate e-mails, so there are at most two (one by e-mail, one by
     * user name).
     */
    List<SignInAccount> findByLogin(String login);

    /**
     * Creates the account of the bound cooperative: the user name and the e-mail are {@code email}, with the names,
     * enabled, the e-mail not verified, no password and no required action. When Keycloak answers that the login exists
     * already (a concurrent invitation), the existing account is looked up again and returned, whatever cooperative it
     * belongs to: the caller checks {@link SignInAccount#tenantId()}.
     */
    SignInAccount create(String email, String firstName, String lastName);

    /** The account with this id; empty when it does not exist. */
    Optional<SignInAccount> get(String id);

    /** Applies the change to an account of the bound cooperative (tenant guard). */
    void update(String id, SignInAccountChange change);

    /** Sets the account's password, not temporary (tenant guard). The password is never logged or kept. */
    void resetPassword(String id, String password);

    /** Ends every session of the account (tenant guard). */
    void logout(String id);
}
