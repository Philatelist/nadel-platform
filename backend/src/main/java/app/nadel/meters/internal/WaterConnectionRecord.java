package app.nadel.meters.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One connection of a plot to water (table {@code plot_water_connection}, V16): both dates are days of the connection,
 * {@code validTo} is null while it lasts; {@code sourceMeterId} is the common consumer meter (a well pump) or null for
 * the cooperative's water supply. The connections of one plot never share a day ({@code
 * plot_water_connection_no_overlap_ex}) and at most one lasts ({@code plot_water_connection_active_uq}). A
 * disconnection ends the row; rows are never deleted.
 */
@Entity
@Table(name = "plot_water_connection")
class WaterConnectionRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "source_meter_id", updatable = false)
    private @Nullable UUID sourceMeterId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private @Nullable LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WaterConnectionRecord() {
    }

    /** A new lasting connection of the plot from {@code validFrom}. */
    static WaterConnectionRecord of(UUID tenantId, UUID plotId, @Nullable UUID sourceMeterId, LocalDate validFrom,
            Instant now) {
        WaterConnectionRecord row = new WaterConnectionRecord();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.plotId = plotId;
        row.sourceMeterId = sourceMeterId;
        row.validFrom = validFrom;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    /** Ends the connection on {@code lastDay}, the last connected day. */
    void endOn(LocalDate lastDay, Instant now) {
        this.validTo = lastDay;
        this.updatedAt = now;
    }

    boolean lasts() {
        return validTo == null;
    }

    /** Whether the plot is connected on the day. */
    boolean covers(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getPlotId() {
        return plotId;
    }

    @Nullable
    UUID getSourceMeterId() {
        return sourceMeterId;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    @Nullable
    LocalDate getValidTo() {
        return validTo;
    }
}
