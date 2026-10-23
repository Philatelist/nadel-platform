package app.nadel.meters.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a meter (entity type {@code METER}) or to one of its readings (entity type
 * {@code METER_READING}), and of a water connection of a plot ({@link #publishWater}). The first two are about the
 * meter: the entity id is the meter's id, so the log links to the meter card (as {@code OWNERSHIP} entries carry the
 * plot), and the label is the name of a named meter or the serial number of a plot meter, both user content. The
 * publication is stored by the Spring Modulith outbox in the transaction of the change, so a committed change always
 * has its audit entry owed.
 */
@Component
class MeterAuditPublisher {

    static final String METER = "METER";
    static final String METER_READING = "METER_READING";
    static final String PLOT = "PLOT";

    /**
     * The actions of the water connections (feature 011), about the plot (entity type {@code PLOT}). Each must also be
     * a value of the contract's {@code AuditAction}, or the audit listing cannot read the entry back
     * ({@code WaterAuditActionsTests}).
     */
    enum WaterAction {
        WATER_CONNECTED,
        WATER_DISCONNECTED
    }

    private final ApplicationEventPublisher events;

    MeterAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * A change to the water connection of a plot (entity type {@code PLOT}): the entity id is the plot's id, so the log
     * links to the plot card, and the label its number (user content).
     */
    void publishWater(AccessProfile actor, WaterAction action, UUID plotId, String plotNumber,
            List<FieldChange> changes) {
        events.publishEvent(waterEvent(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, plotId, plotNumber,
                changes));
    }

    /**
     * The water entry {@link #publishWater} would publish, without publishing it: the Excel import returns its events
     * to the caller, which publishes them in batches ({@code audit.AuditBatch}).
     */
    AuditEvent waterEvent(UUID tenantId, Actor actor, WaterAction action, UUID plotId, String plotNumber,
            List<FieldChange> changes) {
        return AuditEvent.now(tenantId, actor, action.name(), PLOT, plotId, plotNumber, changes);
    }

    void publish(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        publish(actor, action, METER, meter, changes);
    }

    /** A change to a reading of the meter: {@code takenOn} as an ISO date, {@code value} with three decimals. */
    void publishReading(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        publish(actor, action, METER_READING, meter, changes);
    }

    /**
     * The entry {@link #publishReading} would publish, without publishing it: a reading change booked with a
     * recalculation ({@code ReadingChanges}) returns it to the ledger, which publishes it with the recalculation's
     * entry, or drops it with a rolled-back preview.
     */
    AuditEvent readingEvent(AccessProfile actor, String action, MeterRecord meter, List<FieldChange> changes) {
        return AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, METER_READING,
                meter.getId(), meter.auditLabel(), changes);
    }

    /** Publishes an entry made by {@link #readingEvent}. */
    void publish(AuditEvent event) {
        events.publishEvent(event);
    }

    private void publish(AccessProfile actor, String action, String entityType, MeterRecord meter,
            List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, entityType, meter.getId(),
                meter.auditLabel(), changes));
    }

    /**
     * The event of a change to a meter without publishing it: the Excel import returns its events to the caller, which
     * publishes them in batches ({@code audit.AuditBatch}).
     */
    AuditEvent event(UUID tenantId, Actor actor, String action, MeterRecord meter, List<FieldChange> changes) {
        return AuditEvent.now(tenantId, actor, action, METER, meter.getId(), meter.auditLabel(), changes);
    }
}
