package app.nadel.plots.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cached plot status for the map. Rebuilt from the charges and allocations ledger,
 * so it can be overwritten at any time (unlike ledger entries).
 */
@Entity
@Table(name = "plot_status")
class PlotStatusEntry {

    @Id
    @Column(name = "plot_id")
    private UUID plotId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlotStatusCode status;

    @Column(nullable = false)
    private BigDecimal debt;

    @Column(name = "oldest_debt_date")
    private LocalDate oldestDebtDate;

    // set by the database on insert; written when the cached status changes
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected PlotStatusEntry() {
    }

    /** The status row of a new plot: GREY with no debt, until ownership and the ledger exist. */
    static PlotStatusEntry initial(UUID plotId, UUID tenantId) {
        var entry = new PlotStatusEntry();
        entry.plotId = plotId;
        entry.tenantId = tenantId;
        entry.status = PlotStatusCode.GREY;
        entry.debt = BigDecimal.ZERO.setScale(2);
        return entry;
    }

    /** Overwrites the cached status (the cache can be rebuilt at any time); the debt is not touched. */
    void changeStatus(PlotStatusCode newStatus, Instant now) {
        status = newStatus;
        updatedAt = now;
    }

    UUID getPlotId() {
        return plotId;
    }

    UUID getTenantId() {
        return tenantId;
    }

    PlotStatusCode getStatus() {
        return status;
    }

    BigDecimal getDebt() {
        return debt;
    }
}
