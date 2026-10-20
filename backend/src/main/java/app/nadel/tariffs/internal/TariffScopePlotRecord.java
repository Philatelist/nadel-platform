package app.nadel.tariffs.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A plot named by a tariff's scope: {@code SELECTED} for a tariff of selected plots, {@code EXCLUDED} (with a reason)
 * for a tariff of all plots. Rows are never edited: a scope change deletes the rows that no longer hold and inserts the
 * new ones.
 */
@Entity
@IdClass(ScopePlotKey.class)
@Table(name = "tariff_scope_plot")
class TariffScopePlotRecord {

    /** What the row says about its plot. */
    enum Kind {
        SELECTED,
        EXCLUDED
    }

    @Id
    @Column(name = "tariff_id", nullable = false, updatable = false)
    private UUID tariffId;

    @Id
    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(updatable = false)
    private @Nullable String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TariffScopePlotRecord() {
    }

    static TariffScopePlotRecord selected(UUID tenantId, UUID tariffId, UUID plotId, Instant now) {
        return create(tenantId, tariffId, plotId, Kind.SELECTED, null, now);
    }

    /** An exclusion; {@code reason} is already trimmed and not blank. */
    static TariffScopePlotRecord excluded(UUID tenantId, UUID tariffId, UUID plotId, String reason, Instant now) {
        return create(tenantId, tariffId, plotId, Kind.EXCLUDED, reason, now);
    }

    private static TariffScopePlotRecord create(UUID tenantId, UUID tariffId, UUID plotId, Kind kind,
            @Nullable String reason, Instant now) {
        TariffScopePlotRecord row = new TariffScopePlotRecord();
        row.tenantId = tenantId;
        row.tariffId = tariffId;
        row.plotId = plotId;
        row.kind = kind;
        row.reason = reason;
        row.createdAt = now;
        return row;
    }

    UUID getTariffId() {
        return tariffId;
    }

    UUID getPlotId() {
        return plotId;
    }

    Kind getKind() {
        return kind;
    }

    @Nullable String getReason() {
        return reason;
    }
}
