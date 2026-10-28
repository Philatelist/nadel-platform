package app.nadel.documents.internal;

import app.nadel.identity.CurrentUser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import nadel.contract.api.CertificatesApi;
import nadel.contract.model.CertificateAnnulment;
import nadel.contract.model.CertificatePage;
import nadel.contract.model.CertificateRow;
import nadel.contract.model.CertificateStatus;
import nadel.contract.model.IssueCertificateRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints of tag {@code certificates} (feature 016 §2.7, technical considerations 2.8):
 * {@code POST /plots/{id}/certificates} and {@code POST /certificates/{id}/annul} need {@code CERTIFICATES_ISSUE};
 * {@code GET /certificates} and {@code GET /certificates/{id}/pdf} need {@code CHARGES_VIEW}, so a member (and the
 * platform operator, who holds no permission) is 403. The work is done by {@link Certificates}. Files are sent as
 * attachments and never cached.
 */
@RestController
@RequestMapping("/api/v1")
class CertificatesController implements CertificatesApi {

    private final CurrentUser currentUser;
    private final Certificates certificates;

    CertificatesController(CurrentUser currentUser, Certificates certificates) {
        this.currentUser = currentUser;
        this.certificates = certificates;
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CERTIFICATES_ISSUE')")
    public ResponseEntity<CertificateRow> issuePlotCertificate(UUID id, @Nullable IssueCertificateRequest request) {
        Certificates.Row issued = certificates.issue(id, request == null ? null : request.getAsOf(),
                currentUser.profile());
        return ResponseEntity.status(HttpStatus.CREATED).body(row(issued));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<CertificatePage> listCertificates(@Nullable CertificateStatus status, Integer page,
            Integer size) {
        Boolean annulled = status == null ? null : status == CertificateStatus.ANNULLED;
        Certificates.Page found = certificates.list(annulled, page, size);
        List<CertificateRow> items = found.items().stream().map(CertificatesController::row).toList();
        return ResponseEntity.ok(new CertificatePage(items, found.page(), found.size(), found.totalItems(),
                found.totalPages()));
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CHARGES_VIEW')")
    public ResponseEntity<Resource> getCertificatePdf(UUID id) {
        Certificates.File file = certificates.pdf(id, currentUser.profile());
        return DocumentsController.pdf(file.fileName(), file.content());
    }

    @Override
    @PreAuthorize("hasAuthority('PERM_CERTIFICATES_ISSUE')")
    public ResponseEntity<CertificateRow> annulCertificate(UUID id, CertificateAnnulment annulment) {
        return ResponseEntity.ok(row(certificates.annul(id, annulment.getReason(), currentUser.profile())));
    }

    /** The contract form of a register row. */
    static CertificateRow row(Certificates.Row row) {
        CertificateRow dto = new CertificateRow(row.id(), row.number(), row.plotId(), row.plotNumber(), row.asOf(),
                row.totalDebt().toString(), offset(row.issuedAt()),
                row.annulled() ? CertificateStatus.ANNULLED : CertificateStatus.ISSUED)
                .issuedById(row.issuedBy())
                .issuedByName(row.issuedByName())
                .annulReason(row.annulReason())
                .annulledByName(row.annulledByName());
        if (row.annulledAt() != null) {
            dto.setAnnulledAt(offset(row.annulledAt()));
        }
        return dto;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
