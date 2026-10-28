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
 * Publishes the audit events of the documents (feature 016 §2.11) inside the transaction that stores the document, so
 * the Spring Modulith outbox keeps them with it ({@code audit/README.md}). {@code INVOICE_REISSUED} (an invoice issued
 * on request, entity type {@code INVOICE}): about the plot whose invoice it is, so the log links to the plot card
 * (the entity id is the plot, the label its number, user content); the fields, new values only, are {@code number}
 * ({@code YYYY-MM/plot}), {@code month} ({@code YYYY-MM}), {@code version} and {@code due} (two decimals). No names:
 * the invoice's people stay sealed in its snapshot. Downloads are never audited.
 */
@Component
class InvoiceAuditPublisher {

    static final String INVOICE = "INVOICE";

    /** Every action the documents write; each is a value of the contract's {@code AuditAction}. */
    enum Action {
        INVOICE_REISSUED
    }

    private final ApplicationEventPublisher events;

    InvoiceAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void reissued(AccessProfile actor, InvoiceDocument invoice, Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at,
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), Action.INVOICE_REISSUED.name(),
                INVOICE, invoice.plot().id(), invoice.plot().number(), List.of(
                        new FieldChange("number", null, invoice.number()),
                        new FieldChange("month", null, invoice.month().toString()),
                        new FieldChange("version", null, Integer.toString(invoice.version())),
                        new FieldChange("due", null, invoice.totals().due().toString()))));
    }
}
