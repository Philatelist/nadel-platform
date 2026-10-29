package app.nadel.documents.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit events of the debt certificates (feature 016 §2.11) inside the transaction that stores the
 * change, so the Spring Modulith outbox keeps them with it ({@code audit/README.md}). Entity type {@code CERTIFICATE}:
 * the entity id is the certificate, the label its number ({@code Д-2026-1}). {@code CERTIFICATE_ISSUED} has, new
 * values only, {@code plot} (the plot number, user content), {@code number}, {@code asOf} (ISO date) and
 * {@code totalDebt} (two decimals); {@code CERTIFICATE_ANNULLED} has {@code number} and {@code reason} (user content).
 * No names: the people on a certificate stay sealed in its snapshot. Downloads are never audited.
 */
@Component
class CertificateAuditPublisher {

    static final String CERTIFICATE = "CERTIFICATE";

    /** Every action the certificates write; each is a value of the contract's {@code AuditAction}. */
    enum Action {
        CERTIFICATE_ISSUED,
        CERTIFICATE_ANNULLED
    }

    private final ApplicationEventPublisher events;

    CertificateAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void issued(AccessProfile actor, CertificateDocument certificate) {
        publish(actor, Action.CERTIFICATE_ISSUED, certificate.id(), certificate.number(), certificate.issuedAt(),
                List.of(new FieldChange("plot", null, certificate.plot().number()),
                        new FieldChange("number", null, certificate.number()),
                        new FieldChange("asOf", null, certificate.asOf().toString()),
                        new FieldChange("totalDebt", null, certificate.totalDebt().toString())));
    }

    void annulled(AccessProfile actor, UUID certificateId, String number, String reason, Instant at) {
        publish(actor, Action.CERTIFICATE_ANNULLED, certificateId, number, at,
                List.of(new FieldChange("number", null, number), new FieldChange("reason", null, reason)));
    }

    private void publish(AccessProfile actor, Action action, UUID certificateId, String number, Instant at,
            List<FieldChange> changes) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at,
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), action.name(), CERTIFICATE,
                certificateId, number, changes));
    }
}
