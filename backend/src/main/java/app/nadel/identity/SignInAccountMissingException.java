package app.nadel.identity;

/**
 * A change was asked for a Keycloak account that does not exist (any more): someone removed it by hand. The invitation
 * of the member makes a new one.
 */
public class SignInAccountMissingException extends RuntimeException {

    public SignInAccountMissingException(String accountId) {
        super("The sign-in account " + accountId + " does not exist");
    }
}
