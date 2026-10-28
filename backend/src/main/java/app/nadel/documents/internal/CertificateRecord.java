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
 * One debt certificate of the register (table {@code certificate}, V28). Written once; the only change a row ever gets
 * is its annulment, once, which {@link Certificates#annul} makes with one guarded {@code UPDATE} (a trigger refuses any
 * other change and a second annulment). So the entity is {@link Immutable}: Hibernate only inserts and reads it.
 * {@code snapshot} is the stored form of {@link CertificateSnapshots} (the names sealed).
 */
@Entity
@Immutable
@Table(name = "certificate")
class CertificateRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private short year;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "as_of", nullable = false, updatable = false)
    private LocalDate asOf;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> snapshot;

    @Column(name = "total_debt", nullable = false, updatable = false)
    private BigDecimal totalDebt;

    @Column(name = "issued_by", updatable = false)
    private UUID issuedBy;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "annulled_at", updatable = false)
    private Instant annulledAt;

    @Column(name = "annulled_by", updatable = false)
    private UUID annulledBy;

    @Column(name = "annul_reason", updatable = false)
    private String annulReason;

    protected CertificateRecord() {
    }

    static CertificateRecord issue(UUID tenantId, CertificateDocument document, Map<String, Object> snapshot,
            BigDecimal totalDebt) {
        CertificateRecord record = new CertificateRecord();
        record.id = document.id();
        record.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        record.year = (short) document.year();
        record.seq = document.seq();
        record.number = document.number();
        record.plotId = document.plot().id();
        record.asOf = document.asOf();
        record.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        record.totalDebt = Objects.requireNonNull(totalDebt, "totalDebt");
        record.issuedBy = document.issuer().memberId();
        record.issuedAt = document.issuedAt();
        return record;
    }

    UUID getId() {
        return id;
    }

    String getNumber() {
        return number;
    }

    UUID getPlotId() {
        return plotId;
    }

    LocalDate getAsOf() {
        return asOf;
    }

    Map<String, Object> getSnapshot() {
        return snapshot;
    }

    BigDecimal getTotalDebt() {
        return totalDebt;
    }

    @Nullable UUID getIssuedBy() {
        return issuedBy;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }

    @Nullable Instant getAnnulledAt() {
        return annulledAt;
    }

    @Nullable UUID getAnnulledBy() {
        return annulledBy;
    }

    @Nullable String getAnnulReason() {
        return annulReason;
    }

    /** The plot number as printed on the certificate. */
    String plotNumber() {
        Object plot = snapshot.get("plot");
        return plot instanceof Map<?, ?> map && map.get("number") != null ? map.get("number").toString() : "";
    }

    /** The annulment; null while the certificate is valid. */
    CertificateDocument.@Nullable Annulment annulment() {
        return annulledAt == null ? null
                : new CertificateDocument.Annulment(annulledAt, Objects.requireNonNullElse(annulReason, ""));
    }
}
