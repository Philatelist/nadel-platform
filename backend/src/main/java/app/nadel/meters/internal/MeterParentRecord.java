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
 * One period in which a meter had one parent in the electricity tree (table {@code meter_parent}, V12): both dates are
 * days of the period, {@code validTo} is null while it lasts. The periods of one meter never overlap (the exclusion
 * constraint {@code meter_parent_no_overlap_ex}), so a row that ends must be flushed before the next row of the same
 * meter is inserted.
 */
@Entity
@Table(name = "meter_parent")
class MeterParentRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "meter_id", nullable = false, updatable = false)
    private UUID meterId;

    @Column(name = "parent_meter_id", nullable = false)
    private UUID parentMeterId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeterParentRecord() {
    }

    /** A new period of {@code meterId} under {@code parentMeterId}. */
    static MeterParentRecord of(UUID tenantId, UUID meterId, UUID parentMeterId, LocalDate validFrom,
            @Nullable LocalDate validTo, Instant now) {
        MeterParentRecord row = new MeterParentRecord();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.meterId = meterId;
        row.parentMeterId = parentMeterId;
        row.validFrom = validFrom;
        row.validTo = validTo;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    /** Ends the period on {@code lastDay}, a day of the period. */
    void endOn(LocalDate lastDay, Instant now) {
        this.validTo = lastDay;
        this.updatedAt = now;
    }

    /** Puts the whole period under another parent (the meter that replaced the parent before the period started). */
    void reparent(UUID parentMeterId, Instant now) {
        this.parentMeterId = parentMeterId;
        this.updatedAt = now;
    }

    UUID getMeterId() {
        return meterId;
    }

    UUID getParentMeterId() {
        return parentMeterId;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    @Nullable LocalDate getValidTo() {
        return validTo;
    }

    /** Whether the period has a day on or after {@code date}. */
    boolean reaches(LocalDate date) {
        return validTo == null || !validTo.isBefore(date);
    }
}
