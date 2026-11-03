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
 * The description of a stored receipt file of an expense (table {@code expense_attachment}, V32) <b>without its
 * content</b>: the list of an expense's files and the counts of the expense list read this entity, so they never load
 * the bytes (up to 5 MiB each). The file itself is {@link ExpenseAttachmentContent}, the second entity of the same
 * table, read only by the download and written only by a new upload. Append-only ({@code ledger_append_only}, no
 * {@code UPDATE}/{@code DELETE} for {@code nadel_app}), so {@link Immutable}; this entity is never persisted.
 */
@Entity
@Immutable
@Table(name = "expense_attachment")
class ExpenseAttachmentRecord {

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

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExpenseAttachmentRecord() {
    }

    UUID expenseId() {
        return expenseId;
    }

    /** The number of the file within its expense, 1 to 5. */
    int position() {
        return position;
    }

    String contentType() {
        return contentType;
    }

    String fileName() {
        return fileName;
    }

    int sizeBytes() {
        return sizeBytes;
    }
}
