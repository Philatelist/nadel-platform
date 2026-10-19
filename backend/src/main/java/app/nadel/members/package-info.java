/**
 * Members of a cooperative, their roles and permissions (ADR 0009).
 * <p>
 * Public API: {@link app.nadel.members.MemberDirectory} (read-only lookup by id and by name) with
 * {@link app.nadel.members.MemberRef}, for modules that must show or search members without reading their tables
 * ({@code plots} for owners). The module also implements {@link app.nadel.identity.AccessProfileSource} for the
 * sign-in flow and {@link app.nadel.audit.ActorNames}, and serves {@code /members} and {@code /roles}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Members and roles")
package app.nadel.members;
