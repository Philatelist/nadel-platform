package app.nadel.meters;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The points of a plot meter changed on a date (feature 011 §2.4): a reading was entered, approved, corrected or
 * deleted, or the meter was removed or replaced (its final value). Published inside the transaction of the change, for
 * {@link MeterKind#PLOT} meters only; a correction that moves a reading to another date publishes one event per date.
 * The ledger listens (through the Spring Modulith outbox), binds {@code tenantId} with {@code TenantContext.runAs} and
 * charges the intervals of the meter around {@code on} that are not charged yet, so a redelivered event changes
 * nothing. Carries ids, codes and a date only, no personal data.
 *
 * @param tenantId the cooperative
 * @param meterId  the plot meter
 * @param plotId   its plot
 * @param resource what it measures
 * @param on       the date of the point that changed (the reading date, the removal date)
 * @param cause    what changed it
 */
public record PlotMeterPointsChanged(UUID tenantId, UUID meterId, UUID plotId, MeterResource resource, LocalDate on,
        Cause cause) {

    /** What changed the points. */
    public enum Cause {
        READING_ENTERED,
        READING_APPROVED,
        READING_CORRECTED,
        READING_DELETED,
        METER_REMOVED,
        METER_REPLACED
    }

    public PlotMeterPointsChanged {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(meterId, "meterId");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(cause, "cause");
    }
}
