/**
 * Sign-in and the current user: validates Keycloak JWTs, resolves the member behind the token and exposes their
 * permissions.
 * <p>
 * Public API: {@link app.nadel.identity.Permission}, {@link app.nadel.identity.AccessProfile},
 * {@link app.nadel.identity.RoleRef}, {@link app.nadel.identity.CurrentUser}, the SPI
 * {@link app.nadel.identity.AccessProfileSource} and {@link app.nadel.identity.Roles}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Users and roles")
package app.nadel.identity;
