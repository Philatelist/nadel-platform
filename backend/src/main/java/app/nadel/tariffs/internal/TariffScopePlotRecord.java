package app.nadel.tariffs.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A plot named by a tariff's scope: {@code SELECTED} for a tariff of selected plots, {@code EXCLUDED} (with a reason)
 * for a tariff of all plots. Read here to count the plots of a selected-plots tariff; written by the scope endpoint.
 */
@Entity
@IdClass(ScopePlotKey.class)
@Table(name = "tariff_scope_plot")
class TariffScopePlotRecord {

    @Id
    @Column(name = "tariff_id", nullable = false, updatable = false)
    private UUID tariffId;

    @Id
    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String kind;

    protected TariffScopePlotRecord() {
    }

    UUID getTariffId() {
        return tariffId;
    }

    UUID getPlotId() {
        return plotId;
    }
}
