package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a member. Shared by the member endpoints ({@link MemberCommands}) and the
 * Excel import, so a member created or edited by either way is audited identically. The outbox stores the publication
 * in the caller's transaction.
 */
@Component
class MemberAuditPublisher {

    private final ApplicationEventPublisher events;

    MemberAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(UUID tenantId, Actor actor, String action, Member member, List<FieldChange> changes) {
        events.publishEvent(event(tenantId, actor, action, member, changes));
    }

    /**
     * The audit event of a change, not yet published: the Excel import collects thousands of them and publishes them
     * as batches.
     */
    AuditEvent event(UUID tenantId, Actor actor, String action, Member member, List<FieldChange> changes) {
        return AuditEvent.now(
                tenantId,
                actor,
                action,
                "MEMBER",
                member.getId(),
                member.getLastName() + " " + member.getFirstName(),
                changes);
    }
}
