/**
 * Sign-in and the current user: validates Keycloak JWTs, resolves the member behind the token and exposes their
 * permissions.
 * <p>
 * Public API: {@link app.nadel.identity.Permission}, {@link app.nadel.identity.AccessProfile},
 * {@link app.nadel.identity.RoleRef}, {@link app.nadel.identity.CurrentUser}, the SPI
 * {@link app.nadel.identity.AccessProfileSource}, {@link app.nadel.identity.OwnedPlotsSource} and {@link app.nadel.identity.Roles},
 * and the port {@link app.nadel.identity.SignInAccounts} (feature 020: the Keycloak accounts of invited members, with
 * {@link app.nadel.identity.SignInAccount}, {@link app.nadel.identity.SignInAccountChange} and
 * {@link app.nadel.identity.SignInAccountMissingException}), used by {@code members}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Users and roles")
package app.nadel.identity;
