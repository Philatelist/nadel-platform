package app.nadel.identity;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A change to a Keycloak account ({@link SignInAccounts#update}); a null field keeps the account's value. The login is
 * personal data: {@link #toString()} does not print it.
 *
 * @param login           the new user name and e-mail together (lower case); null keeps both
 * @param emailVerified   whether the e-mail is verified
 * @param enabled         whether the account may sign in
 * @param requiredActions the actions Keycloak asks for at the next sign-in; an empty list clears them
 */
public record SignInAccountChange(@Nullable String login, @Nullable Boolean emailVerified, @Nullable Boolean enabled,
        @Nullable List<String> requiredActions) {

    public SignInAccountChange {
        requiredActions = requiredActions == null ? null : List.copyOf(requiredActions);
    }

    /** Turns the account on, nothing else. */
    public static SignInAccountChange enable() {
        return new SignInAccountChange(null, null, true, null);
    }

    /** Turns the account off (access disabled or the member archived), nothing else. */
    public static SignInAccountChange disable() {
        return new SignInAccountChange(null, null, false, null);
    }

    /**
     * A new login after the member's e-mail changed: the user name and the e-mail become {@code login}, which is not
     * verified until the member opens the link sent to it.
     */
    public static SignInAccountChange newLogin(String login) {
        return new SignInAccountChange(login, false, null, null);
    }

    /**
     * What accepting an invitation does once the password is set: the e-mail is verified (the member opened the link
     * sent to it), no action is left for the next sign-in, and the account is on.
     */
    public static SignInAccountChange activated() {
        return new SignInAccountChange(null, true, true, List.of());
    }

    /** Never prints the login. */
    @Override
    public String toString() {
        return "SignInAccountChange[login=" + (login == null ? "<keep>" : "<new>") + ", emailVerified=" + emailVerified
                + ", enabled=" + enabled + ", requiredActions=" + requiredActions + "]";
    }
}
