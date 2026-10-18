package app.nadel.settings.internal;

import app.nadel.settings.PlotStatusVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The settings row of a cooperative; the primary key is the cooperative. Written only by {@link SettingsRepository}. */
@Entity
@Table(name = "cooperative_settings")
class SettingsRow {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_plot_status_visibility", nullable = false)
    private PlotStatusVisibility memberPlotStatusVisibility;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettingsRow() {
    }

    PlotStatusVisibility getMemberPlotStatusVisibility() {
        return memberPlotStatusVisibility;
    }
}
