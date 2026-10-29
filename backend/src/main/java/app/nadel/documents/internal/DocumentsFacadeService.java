package app.nadel.documents.internal;

import app.nadel.documents.DocumentsFacade;
import app.nadel.documents.PrintScope;
import app.nadel.identity.AccessProfile;
import app.nadel.tenancy.TenantContext;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the public {@link DocumentsFacade} with the services of the endpoints: the stored versions
 * ({@link InvoiceRepository}, {@link InvoiceSnapshots}, {@link InvoiceLayout}), the on-request build
 * ({@link InvoiceBuilder#forPlot}), the cash receipt ({@link CashReceipts}) and the print set ({@link PrintBundle}).
 * It acts as the system: no member, subject {@value #SYSTEM}, no permission check (the facade's comment says what the
 * caller keeps). Nothing it reads is logged.
 */
@Service
class DocumentsFacadeService implements DocumentsFacade {

    /** The subject of what the system does through the facade (the audit entry of {@link #invoiceForToday}). */
    static final String SYSTEM = "system:documents";

    private final InvoiceRepository invoices;
    private final InvoiceSnapshots snapshots;
    private final InvoiceBuilder builder;
    private final CashReceipts receipts;
    private final PrintBundle printBundle;

    DocumentsFacadeService(InvoiceRepository invoices, InvoiceSnapshots snapshots, InvoiceBuilder builder,
            CashReceipts receipts, PrintBundle printBundle) {
        this.invoices = invoices;
        this.snapshots = snapshots;
        this.builder = builder;
        this.receipts = receipts;
        this.printBundle = printBundle;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> latestInvoice(UUID plotId, YearMonth month) {
        tenant();
        return invoices.findFirstByPlotIdAndMonthOrderByVersionDesc(Objects.requireNonNull(plotId, "plotId"),
                month.atDay(1)).map(InvoiceRecord::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> invoicePdf(UUID invoiceId) {
        UUID tenantId = tenant();
        return invoices.findById(Objects.requireNonNull(invoiceId, "invoiceId"))
                .map(record -> InvoiceLayout.render(snapshots.open(tenantId, record.getId(), record.getSnapshot())));
    }

    @Override
    @Transactional
    public UUID invoiceForToday(UUID plotId) {
        UUID tenantId = tenant();
        AccessProfile system = new AccessProfile(tenantId, null, SYSTEM, "", Set.of(), List.of(), false);
        return builder.forPlot(Objects.requireNonNull(plotId, "plotId"), null, system).id();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> receiptPdf(UUID paymentId) {
        UUID tenantId = tenant();
        return receipts.receipt(Objects.requireNonNull(paymentId, "paymentId"), tenantId)
                .map(CashReceipts.ReceiptFile::content);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] printPdf(YearMonth month, PrintScope scope) {
        return printBundle.print(tenant(), month, scope).content();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> qrLink(UUID invoiceId) {
        tenant();
        return invoices.findById(Objects.requireNonNull(invoiceId, "invoiceId"))
                .map(record -> record.getSnapshot().get("qr"))
                .filter(Map.class::isInstance)
                .map(qr -> ((Map<?, ?>) qr).get("link"))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    /** The cooperative the caller bound; the facade never works without one. */
    private static UUID tenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("DocumentsFacade needs a bound cooperative"));
    }
}
