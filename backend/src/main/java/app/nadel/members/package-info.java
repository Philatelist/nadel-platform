/**
 * Members of a cooperative, their roles and permissions (ADR 0009).
 * <p>
 * Public API: {@link app.nadel.members.MemberDirectory} (read-only lookup by id and by name) with
 * {@link app.nadel.members.MemberRef}, for modules that must show or search members without reading their tables
 * ({@code plots} for owners), and the SPI {@link app.nadel.members.MemberArchiveGuard}, a veto other modules implement
 * to refuse archiving a member ({@code plots} refuses a current owner), and {@link app.nadel.members.MemberImport},
 * which plans the member rows of an Excel import ({@code imports}). The module also implements {@link app.nadel.identity.AccessProfileSource} for the
 * sign-in flow and {@link app.nadel.audit.ActorNames}, and serves {@code /members} and {@code /roles}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Members and roles")
package app.nadel.members;
