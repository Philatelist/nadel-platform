package app.nadel.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Keycloak account as {@link SignInAccounts} reads it. The user name and the e-mail are personal data:
 * {@link #toString()} prints neither, never log them.
 *
 * @param id            the Keycloak user id (the {@code sub} of its tokens)
 * @param username      the user name (the e-mail for every account the application creates)
 * @param email         the e-mail; null when the account has none
 * @param enabled       whether the account may sign in
 * @param emailVerified whether the e-mail is verified
 * @param tenantId      the cooperative of the account ({@code tenant_id} attribute); null when it has none or it is not
 *                      a UUID
 */
public record SignInAccount(String id, String username, @Nullable String email, boolean enabled,
        boolean emailVerified, @Nullable UUID tenantId) {

    /** Whether the account belongs to the cooperative. */
    public boolean belongsTo(UUID cooperative) {
        return cooperative.equals(tenantId);
    }

    /** Never prints the user name or the e-mail. */
    @Override
    public String toString() {
        return "SignInAccount[id=" + id + ", enabled=" + enabled + ", emailVerified=" + emailVerified + ", tenantId="
                + tenantId + "]";
    }
}
