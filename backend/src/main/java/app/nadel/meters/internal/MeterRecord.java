package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
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
import org.jspecify.annotations.Nullable;

/**
 * One physical meter (table {@code meter}, V12). Kind, resource, plot, installation date and initial reading are fixed
 * at registration; serial, name and note can be corrected; the removal date and final reading are set once, when the
 * meter is removed. Values are {@link MeterValue}s, held as {@code numeric(14,3)}.
 */
@Entity
@Table(name = "meter")
class MeterRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MeterKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MeterResource resource;

    @Column(nullable = false)
    private String serial;

    private String name;

    @Column(name = "plot_id", updatable = false)
    private UUID plotId;

    @Column(name = "installed_on", nullable = false, updatable = false)
    private LocalDate installedOn;

    @Column(name = "initial_value", nullable = false, updatable = false)
    private BigDecimal initialValue;

    @Column(name = "removed_on")
    private LocalDate removedOn;

    @Column(name = "final_value")
    private BigDecimal finalValue;

    private String note;

    @Column(name = "replaces_meter_id", updatable = false)
    private UUID replacesMeterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeterRecord() {
    }

    /** A new active meter. The caller has checked the rules (kind and plot, name, resource, dates). */
    static MeterRecord register(UUID tenantId, MeterKind kind, MeterResource resource, @Nullable UUID plotId,
            String serial, @Nullable String name, LocalDate installedOn, MeterValue initial, @Nullable String note,
            Instant now) {
        MeterRecord meter = new MeterRecord();
        meter.id = UUID.randomUUID();
        meter.tenantId = tenantId;
        meter.kind = kind;
        meter.resource = resource;
        meter.plotId = plotId;
        meter.serial = serial;
        meter.name = name;
        meter.installedOn = installedOn;
        meter.initialValue = initial.value();
        meter.note = note;
        meter.createdAt = now;
        meter.updatedAt = now;
        return meter;
    }

    /**
     * The meter that replaces {@code old} from {@code installedOn}: the same kind, resource, plot, name and note, a new
     * serial number (the same one for a counter that wrapped around) and initial reading, and a link back to
     * {@code old}. The caller has removed {@code old} on that day.
     */
    static MeterRecord replacement(MeterRecord old, String serial, LocalDate installedOn, MeterValue initial,
            Instant now) {
        MeterRecord meter = register(old.tenantId, old.kind, old.resource, old.plotId, serial, old.name, installedOn,
                initial, old.note, now);
        meter.replacesMeterId = old.id;
        return meter;
    }

    /** Overwrites the descriptive fields. */
    void describe(String serial, @Nullable String name, @Nullable String note, Instant now) {
        this.serial = serial;
        this.name = name;
        this.note = note;
        this.updatedAt = now;
    }

    /** Ends the working period; the caller has checked the date and the value against the readings. */
    void remove(LocalDate removedOn, MeterValue finalValue, Instant now) {
        this.removedOn = removedOn;
        this.finalValue = finalValue.value();
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    MeterKind getKind() {
        return kind;
    }

    MeterResource getResource() {
        return resource;
    }

    String getSerial() {
        return serial;
    }

    @Nullable String getName() {
        return name;
    }

    @Nullable UUID getPlotId() {
        return plotId;
    }

    LocalDate getInstalledOn() {
        return installedOn;
    }

    MeterValue getInitialValue() {
        return MeterValue.of(initialValue);
    }

    @Nullable LocalDate getRemovedOn() {
        return removedOn;
    }

    @Nullable MeterValue getFinalValue() {
        return finalValue == null ? null : MeterValue.of(finalValue);
    }

    @Nullable String getNote() {
        return note;
    }

    @Nullable UUID getReplacesMeterId() {
        return replacesMeterId;
    }

    boolean isActive() {
        return removedOn == null;
    }

    /** The label of the meter in the audit log: the name of a named meter, the serial number of a plot meter. */
    String auditLabel() {
        return name != null ? name : serial;
    }
}
