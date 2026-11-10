package app.nadel.identity;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;

/**
 * An in-memory {@link SignInAccounts} for the Spring tests that do not run Keycloak (feature 020): the same contract as
 * {@code KeycloakAdminClient} (tenant guard, {@code PASSWORD_POLICY} under {@link #MIN_PASSWORD} characters,
 * {@link SignInAccountMissingException}), with switches to make the sign-in service unavailable. One instance per
 * Spring context ({@code TestSecurityConfiguration}); a test that uses it calls {@link #reset()} afterwards.
 */
public class FakeSignInAccounts implements SignInAccounts {

    /** The realm's password policy {@code length(8)}. */
    public static final int MIN_PASSWORD = 8;

    /** One account as the fake keeps it. */
    public static final class Account {
        private final String id;
        private volatile String login;
        private final UUID tenantId;
        private volatile boolean enabled;
        private volatile boolean emailVerified;
        private volatile String password;
        private volatile List<String> requiredActions = List.of();
        private final AtomicInteger logouts = new AtomicInteger();
        private final AtomicInteger credentialRemovals = new AtomicInteger();

        Account(String id, String login, UUID tenantId, boolean enabled) {
            this.id = id;
            this.login = login;
            this.tenantId = tenantId;
            this.enabled = enabled;
        }

        public String id() {
            return id;
        }

        public String login() {
            return login;
        }

        public UUID tenantId() {
            return tenantId;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean emailVerified() {
            return emailVerified;
        }

        public String password() {
            return password;
        }

        public List<String> requiredActions() {
            return requiredActions;
        }

        public int logouts() {
            return logouts.get();
        }

        public int credentialRemovals() {
            return credentialRemovals.get();
        }

        SignInAccount view() {
            return new SignInAccount(id, login, login, enabled, emailVerified, tenantId);
        }
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile boolean credentialRemovalUnavailable;

    /** Forgets every account and turns the service back on. */
    public void reset() {
        accounts.clear();
        unavailable = false;
        credentialRemovalUnavailable = false;
    }

    /** From now on {@link #removeCredentials} alone is 503 {@code SIGN_IN_SERVICE_UNAVAILABLE}. */
    public void credentialRemovalUnavailable(boolean value) {
        credentialRemovalUnavailable = value;
    }

    /** From now on every call is 503 {@code SIGN_IN_SERVICE_UNAVAILABLE}, as when Keycloak is down. */
    public void unavailable(boolean value) {
        unavailable = value;
    }

    /** An account that exists already (made by hand, or in another cooperative); returns its id. */
    public String add(String login, UUID tenantId, boolean enabled) {
        String id = UUID.randomUUID().toString();
        accounts.put(id, new Account(id, login.toLowerCase(Locale.ROOT), tenantId, enabled));
        return id;
    }

    /** Moves an account to another cooperative (its {@code tenant_id} changed by hand), keeping its id and login. */
    public void reassign(String id, UUID tenantId) {
        Account account = accounts.get(id);
        accounts.put(id, new Account(id, account.login, tenantId, account.enabled));
    }

    /** Removes an account, as someone deleting it in Keycloak by hand. */
    public void remove(String id) {
        accounts.remove(id);
    }

    public Optional<Account> account(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    /** The accounts with this login. */
    public List<Account> byLogin(String login) {
        String wanted = login.toLowerCase(Locale.ROOT);
        return accounts.values().stream().filter(a -> a.login.equals(wanted)).toList();
    }

    @Override
    public List<SignInAccount> findByLogin(String login) {
        available();
        return new ArrayList<>(byLogin(login).stream().map(Account::view).toList());
    }

    @Override
    public SignInAccount create(String email, String firstName, String lastName) {
        available();
        UUID tenant = TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException("No cooperative"));
        List<Account> existing = byLogin(email);
        if (!existing.isEmpty()) {
            return existing.getFirst().view();
        }
        String id = add(email, tenant, true);
        return accounts.get(id).view();
    }

    @Override
    public Optional<SignInAccount> get(String id) {
        available();
        return account(id).map(Account::view);
    }

    @Override
    public void update(String id, SignInAccountChange change) {
        Account account = guarded(id);
        if (change.login() != null) {
            account.login = change.login().toLowerCase(Locale.ROOT);
        }
        if (change.emailVerified() != null) {
            account.emailVerified = change.emailVerified();
        }
        if (change.enabled() != null) {
            account.enabled = change.enabled();
        }
        if (change.requiredActions() != null) {
            account.requiredActions = change.requiredActions();
        }
    }

    @Override
    public void resetPassword(String id, String password) {
        Account account = guarded(id);
        if (password.length() < MIN_PASSWORD) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("password", ProblemCodes.PASSWORD_POLICY)));
        }
        account.password = password;
    }

    @Override
    public void removeCredentials(String id) {
        Account account = guarded(id);
        if (credentialRemovalUnavailable) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.SIGN_IN_SERVICE_UNAVAILABLE,
                    "The sign-in service could not be reached.");
        }
        account.password = null;
        account.credentialRemovals.incrementAndGet();
    }

    @Override
    public void logout(String id) {
        guarded(id).logouts.incrementAndGet();
    }

    private Account guarded(String id) {
        available();
        UUID tenant = TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException("No cooperative"));
        Account account = account(id).orElseThrow(() -> new SignInAccountMissingException(id));
        if (!account.tenantId.equals(tenant)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.SIGN_IN_ACCOUNT_IN_USE,
                    "The sign-in account belongs to another cooperative.");
        }
        return account;
    }

    private void available() {
        if (unavailable) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.SIGN_IN_SERVICE_UNAVAILABLE,
                    "The sign-in service could not be reached.");
        }
    }
}
