package app.nadel.documents;

import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * The documents for other modules (feature 017: delivering invoices by e-mail, Telegram and Viber, the bot), so they
 * never read the {@code invoice} table or draw a PDF themselves. Ids, links and file bytes only.
 * <p>
 * <b>Cooperative and transactions.</b> Every method works for the cooperative bound by the caller: the request's token,
 * or {@code TenantContext.runAs(tenantId, ...)} in a job or a listener (ADR 0007); without one it throws
 * {@link IllegalStateException}. Each method runs in a transaction of the tenant-aware transaction manager
 * ({@code REQUIRED}: it joins the caller's transaction when there is one, otherwise it opens its own), so a job needs
 * no transaction of its own. Row-level security hides every other cooperative.
 * <p>
 * <b>Permissions.</b> The facade checks none: it answers as the system, and the caller decides who receives what. The
 * rules the endpoints apply are the caller's to keep: a member may receive only the latest version of an invoice of a
 * plot they own now ({@code GET /invoices/{id}}) and only a receipt of a payment group whose every plot they own now
 * ({@code GET /payments/{id}/receipt.pdf}). The receipt is drawn without the payer note (the endpoints show it only to
 * a viewer with {@code MEMBERS_VIEW}). Nothing here is audited except {@link #invoiceForToday} when it issues a new
 * version.
 */
public interface DocumentsFacade {

    /** The latest version of the plot's invoice for the month; empty when the month has none for the plot. */
    Optional<UUID> latestInvoice(UUID plotId, YearMonth month);

    /** The PDF of an invoice version, rendered from its snapshot (always the same bytes); empty for an unknown id. */
    Optional<byte[]> invoicePdf(UUID invoiceId);

    /**
     * Issues the plot's invoice for the current month (Europe/Kyiv) as of now («Рахунок на сьогодні») and returns its
     * id: the same build as {@code POST /plots/{id}/invoices}, with the same month lock and the same answers
     * ({@code ApiProblemException} 404 {@code PLOT_NOT_FOUND}, 409 {@code REQUISITES_MISSING} and
     * {@code PLOT_NOT_INVOICEABLE}). A new version (with the audit entry {@code INVOICE_REISSUED}, issued by the
     * system, no member) only when its content differs from the latest version of the month; otherwise the latest
     * version's id, nothing written.
     */
    UUID invoiceForToday(UUID plotId);

    /**
     * The printed cash receipt of the payment's group, drawn now; empty for an unknown payment, a reversal row and a
     * bank payment (which has no receipt).
     */
    Optional<byte[]> receiptPdf(UUID paymentId);

    /**
     * The print set of the month's invoices ({@code GET /billing-months/{month}/invoices/print.pdf}): the latest
     * version of each invoice in the scope, one per page, in street and plot order; one page saying so when there is
     * none.
     */
    byte[] printPdf(YearMonth month, PrintScope scope);

    /**
     * The NBU payment link stored with the invoice version ({@code https://qr.bank.gov.ua/...}, the content of its QR
     * code); empty for an unknown id and for a version with nothing due (it has no QR code).
     */
    Optional<String> qrLink(UUID invoiceId);
}
