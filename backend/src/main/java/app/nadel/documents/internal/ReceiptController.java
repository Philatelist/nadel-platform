package app.nadel.documents.internal;

import app.nadel.documents.internal.CashReceipts.ReceiptFile;
import app.nadel.identity.CurrentUser;
import java.util.UUID;
import nadel.contract.api.DocumentsApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code documents} (feature 016). {@code GET /payments/{id}/receipt.pdf}: the printed cash
 * receipt; who may print it ({@code PAYMENTS_VIEW}, or a member who owns every plot of the payment's group) is decided
 * by {@link CashReceipts}, since a member holds no permission for it. The file is sent as an attachment and never
 * cached.
 */
@RestController
@RequestMapping("/api/v1")
class ReceiptController implements DocumentsApi {

    private final CurrentUser currentUser;
    private final CashReceipts receipts;

    ReceiptController(CurrentUser currentUser, CashReceipts receipts) {
        this.currentUser = currentUser;
        this.receipts = receipts;
    }

    @Override
    public ResponseEntity<Resource> getPaymentReceiptPdf(UUID id) {
        ReceiptFile file = receipts.receipt(id, currentUser.profile());
        return pdf(file.fileName(), file.content());
    }

    /** A PDF file as an attachment. */
    static ResponseEntity<Resource> pdf(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(new ByteArrayResource(content));
    }
}
