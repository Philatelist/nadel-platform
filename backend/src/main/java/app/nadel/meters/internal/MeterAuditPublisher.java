package app.nadel.meters.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a meter (entity type {@code METER}, label: the name of a named meter or the
 * serial number of a plot meter, both user content). The publication is stored by the Spring Modulith outbox in the
 * transaction of the change, so a committed change always has its audit entry owed.
 */
@Component
class MeterAuditPublisher {

    static final String METER = "METER";

    private final ApplicationEventPublisher events;

    MeterAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, METER, meter.getId(),
                meter.auditLabel(), changes));
    }
}
