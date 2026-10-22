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
 * Publishes the audit event of a manual change to the ledger (entity type {@code CHARGE}): a reversal or an opening
 * balance. The entry is about the plot whose account changed: the entity id is the plot's id, so the log links to the
 * plot card, and the label its number (user content). Values are codes and parameters: the charge type as
 * {@code base:<CODE>} or its stored name, amounts with two decimals, ISO dates, the billing month as {@code YYYY-MM},
 * the reason as typed. The charges themselves are the ledger and are not repeated in the log. The publication is stored
 * by the Spring Modulith outbox in the transaction of the change.
 */
@Component
class LedgerAuditPublisher {

    static final String CHARGE = "CHARGE";

    private final ApplicationEventPublisher events;

    LedgerAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(AccessProfile actor, String action, UUID plotId, String plotNumber, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(Objects.requireNonNull(actor.tenantId()),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, CHARGE, plotId, plotNumber,
                changes));
    }
}
