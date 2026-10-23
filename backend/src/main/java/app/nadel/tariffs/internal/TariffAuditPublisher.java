package app.nadel.tariffs.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a charge type or a tariff. The publication is stored by the
 * Spring Modulith outbox in the transaction of the change, so a committed change always has its audit entry owed.
 */
@Component
class TariffAuditPublisher {

    static final String CHARGE_TYPE = "CHARGE_TYPE";
    static final String TARIFF = "TARIFF";

    private final ApplicationEventPublisher events;

    TariffAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(UUID tenantId, Actor actor, String action, String entityType, UUID entityId, String label,
            List<FieldChange> changes) {
        events.publishEvent(event(tenantId, actor, action, entityType, entityId, label, changes));
    }

    /** Publishes an event built by {@link #event}. */
    void publish(AuditEvent event) {
        events.publishEvent(event);
    }

    /**
     * The event {@link #publish} would publish, without publishing it: a change of a tariff version that a
     * recalculation books (feature 012) is published by the ledger, together with the recalculation's entry.
     */
    AuditEvent event(UUID tenantId, Actor actor, String action, String entityType, UUID entityId, String label,
            List<FieldChange> changes) {
        return AuditEvent.now(tenantId, actor, action, entityType, entityId, label, changes);
    }
}
