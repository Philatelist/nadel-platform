package app.nadel.ledger.internal;

import app.nadel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One booked recalculation (table {@code recalculation}, V19, feature 012): its cause, the scope it used, the reason, the
 * totals and who booked it. Append-only like the ledger (the application role has no {@code UPDATE} or {@code DELETE}
 * and a trigger refuses both to everyone else), so the entity is {@link Immutable}. Its lines are
 * {@link RecalculationLineRecord}s.
 * <p>
 * {@code scope} holds ids and codes only, with the keys of the contract's {@code RecalculationScope} ({@code months},
 * {@code chargeTypeIds}, {@code plotIds}, {@code tariffId}, {@code tariffVersionId}, {@code meterId},
 * {@code readingId}) plus {@code causeType} (the {@code RecalculationCauseType} of the request). The reason is user
 * content: never logged.
 */
@Entity
@Immutable
@Table(name = "recalculation")
class RecalculationRecord {

    /** The kind of cause (column {@code cause}, the {@code RecalculationCauseKind} of the API contract). */
    enum Cause {
        MANUAL, TARIFF_VERSION, READING
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Cause cause;

    @Column(name = "cause_ref_id", updatable = false)
    private UUID causeRefId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> scope;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "plot_count", nullable = false, updatable = false)
    private int plotCount;

    @Column(nullable = false, updatable = false)
    private String digest;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecalculationRecord() {
    }

    UUID getId() {
        return id;
    }

    Cause getCause() {
        return cause;
    }

    /** The version or reading that caused it; null for a manual recalculation (no foreign key: it may be gone). */
    @Nullable UUID getCauseRefId() {
        return causeRefId;
    }

    Map<String, Object> getScope() {
        return scope;
    }

    String getReason() {
        return reason;
    }

    /** The sum of the differences of its lines. */
    Money getTotal() {
        return Money.of(total);
    }

    int getLineCount() {
        return lineCount;
    }

    int getPlotCount() {
        return plotCount;
    }

    String getDigest() {
        return digest;
    }

    @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
