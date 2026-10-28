package app.nadel.documents.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
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
 * the invoice's people stay sealed in its snapshot. {@code INVOICES_ISSUED} (one build of a month's invoices): about
 * the billing month (entity type {@code BILLING_MONTH}, label {@code YYYY-MM}, the entity id the same for every build of
 * the month: the name-based UUID of {@code invoices:YYYY-MM}); the fields, new values only, are {@code month},
 * {@code built}, {@code newVersions}, {@code unchanged} and {@code skipped} (integers). Downloads are never audited.
 */
@Component
class InvoiceAuditPublisher {

    static final String INVOICE = "INVOICE";
    static final String BILLING_MONTH = "BILLING_MONTH";

    /** Every action the documents write; each is a value of the contract's {@code AuditAction}. */
    enum Action {
        INVOICE_REISSUED,
        INVOICES_ISSUED
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

    /** One build of the month's invoices with its counts. */
    void issued(AccessProfile actor, YearMonth month, int built, int newVersions, int unchanged, int skipped,
            Instant at) {
        UUID tenantId = Objects.requireNonNull(actor.tenantId(), "tenantId");
        events.publishEvent(new AuditEvent(UUID.randomUUID(), tenantId, at,
                new Actor(actor.memberId(), actor.subject(), actor.displayName()), Action.INVOICES_ISSUED.name(),
                BILLING_MONTH, monthEntity(month), month.toString(), List.of(
                        new FieldChange("month", null, month.toString()),
                        new FieldChange("built", null, Integer.toString(built)),
                        new FieldChange("newVersions", null, Integer.toString(newVersions)),
                        new FieldChange("unchanged", null, Integer.toString(unchanged)),
                        new FieldChange("skipped", null, Integer.toString(skipped)))));
    }

    /** The entity id of every build of the month: the name-based UUID of {@code invoices:YYYY-MM}. */
    static UUID monthEntity(YearMonth month) {
        return UUID.nameUUIDFromBytes(("invoices:" + month).getBytes(StandardCharsets.UTF_8));
    }
}
