package app.nadel.plots.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

    protected PlotStatusEntry() {
    }
}
