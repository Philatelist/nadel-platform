package app.nadel.documents.internal;

import app.nadel.documents.PrintScope;
import app.nadel.documents.internal.CashReceipts.ReceiptFile;
import app.nadel.identity.CurrentUser;
import app.nadel.ledger.InvoiceLedger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.api.DocumentsApi;
import nadel.contract.model.Basis;
import nadel.contract.model.BillingMonthStatus;
import nadel.contract.model.CalculationUnit;
import nadel.contract.model.ChargeCalculationPart;
import nadel.contract.model.ChargeCalculationReading;
import nadel.contract.model.Frequency;
import nadel.contract.model.InvoiceBuild;
import nadel.contract.model.InvoiceBuildRequest;
import nadel.contract.model.InvoiceCooperative;
import nadel.contract.model.InvoiceLaterPayment;
import nadel.contract.model.InvoiceLine;
import nadel.contract.model.InvoiceLineKind;
import nadel.contract.model.InvoiceLineSource;
import nadel.contract.model.InvoiceOwner;
import nadel.contract.model.InvoicePage;
import nadel.contract.model.InvoicePlot;
import nadel.contract.model.InvoicePrintScope;
import nadel.contract.model.InvoiceQr;
import nadel.contract.model.InvoiceReadiness;
import nadel.contract.model.InvoiceRow;
import nadel.contract.model.InvoiceSnapshot;
import nadel.contract.model.InvoiceTotals;
import nadel.contract.model.InvoiceTypeRow;
import nadel.contract.model.IssueInvoiceRequest;
import nadel.contract.model.RequisiteField;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code documents} (feature 016). {@code GET /payments/{id}/receipt.pdf}: the printed cash
 * receipt; who may print it ({@code PAYMENTS_VIEW}, or a member who owns every plot of the payment's group) is decided
 * by {@link CashReceipts}, since a member holds no permission for it. The invoices: {@code POST /plots/{id}/invoices}
 * ({@code INVOICES_ISSUE}, {@link InvoiceBuilder}), {@code GET /invoices} and {@code GET /invoices/readiness}
 * ({@code CHARGES_VIEW}), {@code GET /invoices/{id}}, {@code /pdf} and {@code /qr.png} ({@code CHARGES_VIEW} or a
 * current owner and the latest version, decided by {@link Invoices}). The month: {@code POST
 * /billing-months/{month}/invoices} ({@code INVOICES_ISSUE}, {@link InvoiceBuilder#forMonth}) and {@code GET
 * /billing-months/{month}/invoices/print.pdf} ({@code INVOICES_ISSUE}, {@link PrintBundle}). Files are sent as
 * attachments and never cached.
 */
@RestController
@RequestMapping("/api/v1")
class DocumentsController implements DocumentsApi {

    private final CurrentUser currentUser;
    private final CashReceipts receipts;
    private final InvoiceBuilder builder;
    private final Invoices invoices;
    private final PrintBundle printBundle;

    DocumentsController(CurrentUser currentUser, CashReceipts receipts, InvoiceBuilder builder, Invoices invoices,
            PrintBundle printBundle) {
        this.currentUser = currentUser;
        this.receipts = receipts;
        this.builder = builder;
        this.invoices = invoices;
        this.printBundle = printBundle;
    }

    @Override
    public ResponseEntity<Resource> getPaymentReceiptPdf(UUID id) {
        ReceiptFile file = receipts.receipt(id, currentUser.profile());
        return pdf(file.fileName(), file.content());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<InvoiceRow> issuePlotInvoice(UUID id, @Nullable IssueInvoiceRequest request) {
        YearMonth month = request == null || request.getMonth() == null ? null : YearMonth.parse(request.getMonth());
        InvoiceBuilder.Issued issued = builder.forPlot(id, month, currentUser.profile());
        return ResponseEntity.status(HttpStatus.CREATED).body(new InvoiceRow(issued.id(), issued.plotId(),
                issued.plotNumber(), issued.month().toString(), issued.number(), issued.version(),
                issued.due().toString(), offset(issued.issuedAt()), true));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<InvoiceBuild> buildMonthInvoices(String month, Boolean preview,
            @Nullable InvoiceBuildRequest request) {
        boolean includeNothingDue = request != null && Boolean.TRUE.equals(request.getIncludeNothingDue());
        InvoiceBuilder.MonthBuild result = builder.forMonth(YearMonth.parse(month), includeNothingDue,
                Boolean.TRUE.equals(preview), currentUser.profile());
        InvoiceBuild body = new InvoiceBuild(result.preview(), result.invoiceable());
        if (result.preview()) {
            body.toBuild(result.toBuild()).nothingDue(result.nothingDue()).existing(result.existing());
        } else {
            body.built(result.built()).newVersions(result.newVersions()).unchanged(result.unchanged())
                    .skipped(result.skipped());
        }
        return ResponseEntity.ok(body);
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_INVOICES_ISSUE')")
    public ResponseEntity<Resource> getMonthInvoicesPrintPdf(String month, @Nullable InvoicePrintScope scope) {
        YearMonth target = YearMonth.parse(month);
        PrintScope printScope = scope == null ? PrintScope.PAPER : PrintScope.valueOf(scope.getValue());
        UUID tenantId = Objects.requireNonNull(currentUser.profile().tenantId(), "tenantId");
        Invoices.File file = printBundle.printListed(tenantId, target, printScope);
        return pdf(file.fileName(), file.content());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<InvoicePage> listInvoices(@Nullable String month, @Nullable UUID plotId, Integer page,
            Integer size) {
        Invoices.Page found = invoices.list(month == null ? null : YearMonth.parse(month), plotId, page, size);
        List<InvoiceRow> items = found.items().stream().map(row -> new InvoiceRow(row.id(), row.plotId(),
                row.plotNumber(), row.month().toString(), row.number(), row.version(), row.due().toString(),
                offset(row.issuedAt()), row.latest())).toList();
        return ResponseEntity.ok(new InvoicePage(items, found.page(), found.size(), found.totalItems(),
                found.totalPages()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<InvoiceReadiness> getInvoiceReadiness() {
        return ResponseEntity.ok(new InvoiceReadiness(invoices.missingRequisites().stream()
                .map(RequisiteField::fromValue).toList()));
    }

    @Override
    public ResponseEntity<InvoiceSnapshot> getInvoice(UUID id) {
        return ResponseEntity.ok(snapshot(invoices.document(id, currentUser.profile())));
    }

    @Override
    public ResponseEntity<Resource> getInvoicePdf(UUID id) {
        Invoices.File file = invoices.pdf(id, currentUser.profile());
        return pdf(file.fileName(), file.content());
    }

    @Override
    public ResponseEntity<Resource> getInvoiceQrPng(UUID id) {
        Invoices.File file = invoices.qr(id, currentUser.profile());
        return file(MediaType.IMAGE_PNG, file.fileName(), file.content());
    }

    /** A PDF file as an attachment. */
    static ResponseEntity<Resource> pdf(String fileName, byte[] content) {
        return file(MediaType.APPLICATION_PDF, fileName, content);
    }

    private static ResponseEntity<Resource> file(MediaType type, String fileName, byte[] content) {
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(content.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(new ByteArrayResource(content));
    }

    /** The contract form of a stored version. */
    static InvoiceSnapshot snapshot(InvoiceDocument d) {
        InvoiceCooperative cooperative = new InvoiceCooperative(d.cooperative().officialName())
                .edrpou(d.cooperative().edrpou())
                .address(d.cooperative().address())
                .bankName(d.cooperative().bankName())
                .iban(d.cooperative().iban());
        InvoicePlot plot = new InvoicePlot(d.plot().id(), d.plot().number()).street(d.plot().street());
        List<InvoiceOwner> owners = d.owners().stream()
                .map(owner -> new InvoiceOwner(owner.memberId(), owner.name(), owner.share())).toList();
        List<InvoiceTypeRow> types = d.types().stream().map(type -> new InvoiceTypeRow(type.chargeTypeId(),
                type.name(), type.start().toString(), type.charged().toString(), type.paid().toString(),
                type.end().toString()).code(type.code())).toList();
        List<InvoiceLine> lines = d.lines().stream().map(DocumentsController::line).toList();
        List<InvoiceLaterPayment> later = d.receivedAfter().stream().map(payment -> new InvoiceLaterPayment(
                payment.paymentId(), payment.receivedOn(), payment.bookedIn().toString(),
                payment.amount().toString())).toList();
        InvoiceTotals totals = new InvoiceTotals(d.totals().endDebt().toString(), d.totals().prepayment().toString(),
                d.totals().receivedAfter().toString(), d.totals().due().toString());
        InvoiceSnapshot snapshot = new InvoiceSnapshot(d.id(), InvoiceSnapshots.SCHEMA, d.number(), d.version(),
                d.month().toString(), BillingMonthStatus.fromValue(d.monthStatus().name()), offset(d.asOf()),
                offset(d.issuedAt()), d.issuedOn(), cooperative, plot, owners, d.chairmen(), d.treasurers(), types,
                lines, later, totals);
        if (d.qr() != null) {
            snapshot.setQr(new InvoiceQr(d.qr().link(), d.qr().purpose()));
        }
        return snapshot;
    }

    private static InvoiceLine line(InvoiceLedger.Line line) {
        List<ChargeCalculationPart> parts = line.parts().stream().map(part -> {
            ChargeCalculationPart dto = new ChargeCalculationPart(part.days(), part.from(), part.to())
                    .quantity(part.quantity())
                    .rate(part.rate());
            if (part.basis() != null) {
                dto.setBasis(Basis.fromValue(part.basis()));
            }
            if (part.frequency() != null) {
                dto.setFrequency(Frequency.fromValue(part.frequency()));
            }
            if (part.unit() != null) {
                dto.setUnit(CalculationUnit.fromValue(part.unit()));
            }
            return dto;
        }).toList();
        List<ChargeCalculationReading> readings = line.readings().stream()
                .map(reading -> new ChargeCalculationReading(reading.meterId(), reading.takenOn(), reading.value()))
                .toList();
        return new InvoiceLine(line.chargeId(), line.chargeTypeId(), InvoiceLineKind.fromValue(line.kind().name()),
                InvoiceLineSource.fromValue(line.source().name()), line.amount().toString(), line.serviceFrom(),
                line.serviceTo(), parts, readings)
                .corrects(line.corrects() == null ? null : line.corrects().toString())
                .lossesPercent(line.lossesPercent())
                .consumption(line.consumption())
                .consumerName(line.consumerName());
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
