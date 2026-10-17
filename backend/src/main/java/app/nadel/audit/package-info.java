/**
 * Append-only audit log of admin changes.
 * <p>
 * Public API: {@link app.nadel.audit.AuditEvent}, {@link app.nadel.audit.Actor},
 * {@link app.nadel.audit.FieldChange} and the SPI {@link app.nadel.audit.ActorNames} (implemented by
 * {@code members}). Other modules publish an {@code AuditEvent} with {@code ApplicationEventPublisher} inside the
 * transaction that makes the change; the listener and {@code GET /audit} are internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit log")
package app.nadel.audit;
