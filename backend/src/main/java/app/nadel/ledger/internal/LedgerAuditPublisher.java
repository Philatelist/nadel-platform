package app.nadel.ledger.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to the ledger: a reversal, an opening balance or a retried "not charged" item
 * (entity type {@code CHARGE}), the closing or the run of a billing month (entity type {@code BILLING_MONTH}), a losses
 * run booked or reversed (entity type {@code LOSSES_RUN}, label {@code from/to}), a common consumer run booked or
 * reversed (entity type {@code COMMON_CONSUMER_RUN}, label {@code from/to}), or the retry of a losses node or common
 * consumer item and a change of how a common consumer is shared (entity type {@code METER} about the meter, label its
 * name), or a booked recalculation (entity type {@code RECALCULATION}, label its months {@code from/to}, published in
 * one {@code AuditBatch} with the change that caused it, feature 012). A {@code CHARGE} entry is about the plot whose
 * account changed: the entity id is the plot's id, so the log links to the plot card, and the label its number (user
 * content). Values are codes and parameters: the charge type as
 * {@code base:<CODE>} or its stored name, amounts with two decimals, ISO dates, the billing month as {@code YYYY-MM},
 * the reason as typed. The charges themselves are the ledger and are not repeated in the log. The publication is stored
 * by the Spring Modulith outbox in the transaction of the change.
 */
@Component
class LedgerAuditPublisher {

    static final String CHARGE = "CHARGE";
    static final String BILLING_MONTH = "BILLING_MONTH";
    static final String LOSSES_RUN = "LOSSES_RUN";
    static final String COMMON_CONSUMER_RUN = "COMMON_CONSUMER_RUN";
    static final String METER = "METER";
    static final String RECALCULATION = "RECALCULATION";

    /**
     * Every action the ledger writes to the audit log. Each must also be a value of the contract's {@code AuditAction},
     * or the audit listing cannot read the entry back ({@code LedgerAuditActionsTests}).
     */
    enum Action {
        CHARGE_REVERSED,
        OPENING_BALANCE_ENTERED,
        NOT_CHARGED_RETRIED,
        BILLING_MONTH_CLOSED,
        BILLING_MONTH_RUN,
        LOSSES_RUN_BOOKED,
        LOSSES_RUN_REVERSED,
        COMMON_CONSUMER_RUN_BOOKED,
        COMMON_CONSUMER_RUN_REVERSED,
        COMMON_CONSUMER_BILLING_CHANGED,
        RECALCULATION_BOOKED
    }

    private final ApplicationEventPublisher events;

    LedgerAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(AccessProfile actor, Action action, UUID plotId, String plotNumber, List<FieldChange> changes) {
        events.publishEvent(event(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, plotId, plotNumber,
                changes));
    }

    /**
     * The {@code CHARGE} entry {@link #publish} would publish, without publishing it: the Excel import returns its
     * events to the caller, which publishes them in batches ({@code audit.AuditBatch}).
     */
    AuditEvent event(UUID tenantId, Actor actor, Action action, UUID plotId, String plotNumber,
            List<FieldChange> changes) {
        return AuditEvent.now(tenantId, actor, action.name(), CHARGE, plotId, plotNumber, changes);
    }

    /**
     * An entry about a billing month (entity type {@code BILLING_MONTH}): the entity id is the month's row, the label
     * the month as {@code YYYY-MM} (a code).
     */
    void publishMonth(AccessProfile actor, Action action, UUID monthId, String month, List<FieldChange> changes) {
        publishAbout(actor, action, BILLING_MONTH, monthId, month, changes);
    }

    /** An entry about a losses run (entity type {@code LOSSES_RUN}): the label is its range {@code from/to}. */
    void publishLossesRun(AccessProfile actor, Action action, UUID runId, String range, List<FieldChange> changes) {
        publishAbout(actor, action, LOSSES_RUN, runId, range, changes);
    }

    /** An entry about a common consumer run (entity type {@code COMMON_CONSUMER_RUN}): the label is its range. */
    void publishCommonConsumerRun(AccessProfile actor, Action action, UUID runId, String range,
            List<FieldChange> changes) {
        publishAbout(actor, action, COMMON_CONSUMER_RUN, runId, range, changes);
    }

    /**
     * An entry about a meter (entity type {@code METER}): the retry of a losses node or common consumer item, about the
     * node or consumer meter, or a change of how a common consumer is shared; the label is the meter's name (user
     * content), null when unknown.
     */
    void publishMeter(AccessProfile actor, Action action, UUID meterId, @Nullable String name,
            List<FieldChange> changes) {
        publishAbout(actor, action, METER, meterId, name, changes);
    }

    /**
     * The entry about a booked recalculation (entity type {@code RECALCULATION}), not yet published: the booking
     * publishes it in one {@link AuditBatch} with the change that caused it (a tariff version or a reading, from slice
     * 6). The label is its months {@code from/to} ({@code YYYY-MM}, codes).
     */
    AuditEvent recalculationEvent(AccessProfile actor, UUID recalculationId, String months,
            List<FieldChange> changes) {
        return AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), Action.RECALCULATION_BOOKED.name(),
                RECALCULATION, recalculationId, months, changes);
    }

    /** Publishes the entries of one cooperative as {@link AuditBatch}es (at most {@value AuditBatch#MAX_ENTRIES}). */
    void publishBatch(UUID tenantId, List<AuditEvent> entries) {
        AuditBatch.of(tenantId, entries).forEach(events::publishEvent);
    }

    private void publishAbout(AccessProfile actor, Action action, String entityType, UUID entityId,
            @Nullable String label, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action.name(), entityType,
                entityId, label, changes));
    }
}
