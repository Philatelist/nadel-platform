package app.nadel.ledger.internal;

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
 * Publishes the audit event of a change to the ledger: a reversal, an opening balance or a retried "not charged" item
 * (entity type {@code CHARGE}), or the closing or the run of a billing month (entity type {@code BILLING_MONTH}). A
 * {@code CHARGE} entry is about the plot whose account changed: the entity id is the plot's id, so the log links to the
 * plot card, and the label its number (user content). Values are codes and parameters: the charge type as
 * {@code base:<CODE>} or its stored name, amounts with two decimals, ISO dates, the billing month as {@code YYYY-MM},
 * the reason as typed. The charges themselves are the ledger and are not repeated in the log. The publication is stored
 * by the Spring Modulith outbox in the transaction of the change.
 */
@Component
class LedgerAuditPublisher {

    static final String CHARGE = "CHARGE";
    static final String BILLING_MONTH = "BILLING_MONTH";

    /**
     * Every action the ledger writes to the audit log. Each must also be a value of the contract's {@code AuditAction},
     * or the audit listing cannot read the entry back ({@code LedgerAuditActionsTests}).
     */
    enum Action {
        CHARGE_REVERSED,
        OPENING_BALANCE_ENTERED,
        NOT_CHARGED_RETRIED,
        BILLING_MONTH_CLOSED,
        BILLING_MONTH_RUN
    }

    private final ApplicationEventPublisher events;

    LedgerAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(AccessProfile actor, Action action, UUID plotId, String plotNumber, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action.name(), CHARGE, plotId,
                plotNumber, changes));
    }

    /**
     * An entry about a billing month (entity type {@code BILLING_MONTH}): the entity id is the month's row, the label
     * the month as {@code YYYY-MM} (a code).
     */
    void publishMonth(AccessProfile actor, Action action, UUID monthId, String month, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action.name(), BILLING_MONTH,
                monthId, month, changes));
    }
}
