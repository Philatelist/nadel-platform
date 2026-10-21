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
 * Publishes the audit event of a change to a meter (entity type {@code METER}) or to one of its readings (entity type
 * {@code METER_READING}). Both are about the meter: the entity id is the meter's id, so the log links to the meter card
 * (as {@code OWNERSHIP} entries carry the plot), and the label is the name of a named meter or the serial number of a
 * plot meter, both user content. The publication is stored by the Spring Modulith outbox in the transaction of the
 * change, so a committed change always has its audit entry owed.
 */
@Component
class MeterAuditPublisher {

    static final String METER = "METER";
    static final String METER_READING = "METER_READING";

    private final ApplicationEventPublisher events;

    MeterAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        publish(actor, action, METER, meter, changes);
    }

    /** A change to a reading of the meter: {@code takenOn} as an ISO date, {@code value} with three decimals. */
    void publishReading(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        publish(actor, action, METER_READING, meter, changes);
    }

    private void publish(AccessProfile actor, String action, String entityType, MeterRecord meter,
            List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, entityType, meter.getId(),
                meter.auditLabel(), changes));
    }
}
