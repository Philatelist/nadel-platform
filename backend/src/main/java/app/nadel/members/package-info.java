/**
 * Members of a cooperative, their roles and permissions (ADR 0009).
 * <p>
 * No public API yet: the module implements {@link app.nadel.identity.AccessProfileSource} for the sign-in flow and
 * serves {@code /members}. Features 004 and 005 will add a lookup API here.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Members and roles")
package app.nadel.members;
