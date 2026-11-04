package app.nadel.treasury.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * A stored receipt file of an expense with its content (table {@code expense_attachment}, V32): inserted once by a new
 * upload (the bytes already checked and stripped of metadata, {@link AttachmentUploads}) and read only by the download,
 * by expense and number. Every list reads {@link ExpenseAttachmentRecord} instead, which leaves the content out.
 * Append-only, so {@link Immutable}. {@code size_bytes} is the length of the stored content and {@code sha256} its
 * SHA-256 (the CHECKs of V32).
 */
@Entity
@Immutable
@Table(name = "expense_attachment")
class ExpenseAttachmentContent {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(nullable = false, updatable = false)
    private short position;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private byte[] sha256;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExpenseAttachmentContent() {
    }

    /** A new file of the expense at {@code position}; size and digest are those of the stored bytes. */
    static ExpenseAttachmentContent create(UUID tenantId, UUID expenseId, int position,
            AttachmentUploads.Prepared file, @Nullable UUID createdBy, Instant now) {
        ExpenseAttachmentContent attachment = new ExpenseAttachmentContent();
        attachment.id = UUID.randomUUID();
        attachment.tenantId = tenantId;
        attachment.expenseId = expenseId;
        attachment.position = (short) position;
        attachment.contentType = file.kind().mediaType();
        attachment.fileName = file.fileName();
        attachment.content = file.content();
        attachment.sizeBytes = file.content().length;
        attachment.sha256 = file.sha256();
        attachment.createdBy = createdBy;
        attachment.createdAt = now;
        return attachment;
    }

    String contentType() {
        return contentType;
    }

    String fileName() {
        return fileName;
    }

    byte[] content() {
        return content;
    }
}
