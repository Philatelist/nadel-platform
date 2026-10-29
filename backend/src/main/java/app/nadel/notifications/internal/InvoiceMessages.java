package app.nadel.notifications.internal;

import app.nadel.documents.DocumentFormats;
import app.nadel.documents.DocumentsFacade;
import app.nadel.documents.InvoiceSummary;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The message of an invoice delivery (feature 017 §2.4), in Ukrainian from {@code messages_uk.properties}: «Рахунок за
 * жовтень 2026, ділянка 9: до сплати 413,26 ₴» with the «Оплатити» link of the invoice when something is due, or
 * «… Боргу немає, дякуємо!», and the invoice's PDF. Reads the documents in the caller's transaction of the bound
 * cooperative.
 */
@Component
class InvoiceMessages {

    private final DocumentsFacade documents;
    private final TenantDirectory tenants;

    InvoiceMessages(DocumentsFacade documents, TenantDirectory tenants) {
        this.documents = documents;
        this.tenants = tenants;
    }

    /** The message of the invoice version; empty when it does not exist. */
    @Transactional(readOnly = true)
    Optional<DeliveryMessage> of(UUID invoiceId) {
        Optional<InvoiceSummary> found = documents.invoiceSummary(invoiceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        InvoiceSummary invoice = found.get();
        byte[] pdf = documents.invoicePdf(invoiceId).orElseThrow();
        NotificationTexts texts = NotificationTexts.uk();
        String month = DocumentFormats.month(invoice.month());
        boolean due = invoice.due().signum() > 0;
        String text = due
                ? texts.text("invoice.due", Map.of("month", month, "plot", invoice.plotNumber(), "amount",
                        DocumentFormats.money(invoice.due())))
                : texts.text("invoice.nothingDue", Map.of("month", month, "plot", invoice.plotNumber()));
        String subject = texts.text("invoice.email.subject", Map.of("month", month, "plot", invoice.plotNumber()));
        String fileName = "invoice_" + invoice.number().replaceAll("[^A-Za-z0-9-]", "_") + ".pdf";
        String sender = tenants.current().map(TenantSummary::name).orElse("");
        return Optional.of(new DeliveryMessage(subject, text, due ? invoice.qrLink().orElse(null) : null, fileName,
                pdf, sender));
    }
}
