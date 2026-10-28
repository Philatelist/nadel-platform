package app.nadel.documents.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One issued invoice version (table {@code invoice}, V28). Append-only: the application role has no {@code UPDATE} or
 * {@code DELETE} on the table and a trigger refuses both, so the entity is {@link Immutable} and only ever inserted.
 * {@code snapshot} is the stored form of {@link InvoiceSnapshots} (the names sealed); {@code contentHash} its keyed
 * content hash; {@code due} «До сплати».
 */
@Entity
@Immutable
@Table(name = "invoice")
class InvoiceRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    /** The first day of the billing month. */
    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> snapshot;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private byte[] contentHash;

    @Column(nullable = false, updatable = false)
    private BigDecimal due;

    @Column(name = "as_of", nullable = false, updatable = false)
    private Instant asOf;

    @Column(name = "issued_by", updatable = false)
    private UUID issuedBy;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    protected InvoiceRecord() {
    }

    static InvoiceRecord issue(UUID id, UUID tenantId, InvoiceDocument document, InvoiceSnapshots.Sealed sealed,
            @Nullable UUID issuedBy) {
        InvoiceRecord record = new InvoiceRecord();
        record.id = Objects.requireNonNull(id, "id");
        record.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        record.plotId = document.plot().id();
        record.month = document.month().atDay(1);
        record.number = document.number();
        record.version = document.version();
        record.snapshot = sealed.snapshot();
        record.contentHash = sealed.contentHash().clone();
        record.due = document.totals().due().value();
        record.asOf = document.asOf();
        record.issuedBy = issuedBy;
        record.issuedAt = document.issuedAt();
        return record;
    }

    UUID getId() {
        return id;
    }

    UUID getPlotId() {
        return plotId;
    }

    LocalDate getMonth() {
        return month;
    }

    String getNumber() {
        return number;
    }

    int getVersion() {
        return version;
    }

    Map<String, Object> getSnapshot() {
        return snapshot;
    }

    byte[] getContentHash() {
        return contentHash.clone();
    }

    BigDecimal getDue() {
        return due;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }
}
