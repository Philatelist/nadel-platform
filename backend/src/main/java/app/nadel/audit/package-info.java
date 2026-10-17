/**
 * Append-only audit log of admin changes.
 * <p>
 * Public API: {@link app.nadel.audit.AuditEvent}, {@link app.nadel.audit.Actor} and
 * {@link app.nadel.audit.FieldChange}. Other modules publish an {@code AuditEvent} with
 * {@code ApplicationEventPublisher} inside the transaction that makes the change; the listener is internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit log")
package app.nadel.audit;
