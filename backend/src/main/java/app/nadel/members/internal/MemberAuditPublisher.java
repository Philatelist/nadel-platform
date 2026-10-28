package app.nadel.members.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.privacy.PersonalDataCipher;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a member. Shared by the member endpoints ({@link MemberCommands}) and the
 * Excel import, so a member created or edited by either way is audited identically. The outbox stores the publication
 * in the caller's transaction.
 * <p>
 * The values of the personal fields ({@link #PERSONAL_FIELDS}) are sealed here, before the event exists, at
 * {@link FieldChange#oldValueRef} / {@link FieldChange#newValueRef} of the event id (which becomes the audit entry
 * id), so neither the outbox ({@code event_publication}) nor the audit log ever holds them in the clear (ADR 0016).
 */
@Component
class MemberAuditPublisher {

    /** The {@link MemberSnapshot} fields that hold personal data: their audit values are sealed. */
    static final Set<String> PERSONAL_FIELDS = Set.of("phones", "email", "postalAddress", "telegramUsername",
            "viberPhone", "notes");

    private final ApplicationEventPublisher events;
    private final PersonalDataCipher cipher;

    MemberAuditPublisher(ApplicationEventPublisher events, PersonalDataCipher cipher) {
        this.events = events;
        this.cipher = cipher;
    }

    void publish(UUID tenantId, Actor actor, String action, Member member, List<FieldChange> changes) {
        events.publishEvent(event(tenantId, actor, action, member, changes));
    }

    /**
     * The audit event of a change, not yet published: the Excel import collects thousands of them and publishes them
     * as batches. Runs in the cooperative's transaction ({@code tenantId} is the current cooperative).
     */
    AuditEvent event(UUID tenantId, Actor actor, String action, Member member, List<FieldChange> changes) {
        UUID eventId = UUID.randomUUID();
        return new AuditEvent(
                eventId,
                tenantId,
                Instant.now(),
                actor,
                action,
                "MEMBER",
                member.getId(),
                member.getLastName() + " " + member.getFirstName(),
                changes.stream().map(change -> sealIfPersonal(tenantId, eventId, change)).toList());
    }

    private FieldChange sealIfPersonal(UUID tenantId, UUID eventId, FieldChange change) {
        if (change.sealed() || !PERSONAL_FIELDS.contains(change.field())) {
            return change;
        }
        return new FieldChange(change.field(),
                seal(tenantId, eventId, change.field(), change.oldValue(), true),
                seal(tenantId, eventId, change.field(), change.newValue(), false),
                true);
    }

    private @Nullable String seal(UUID tenantId, UUID eventId, String field, @Nullable String value, boolean old) {
        if (value == null) {
            return null;
        }
        byte[] sealed = cipher.encrypt(tenantId,
                old ? FieldChange.oldValueRef(eventId, field) : FieldChange.newValueRef(eventId, field), value);
        return Base64.getEncoder().encodeToString(sealed);
    }
}
