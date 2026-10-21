package app.nadel.meters.internal;

import app.nadel.audit.FieldChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a meter as strings (codes and parameters, never display text), so two states can be compared
 * and the difference written to the audit log: enum codes for kind and resource, the plot number as the cooperative
 * writes it, ISO dates, readings as plain decimals with three decimals ({@code 8205.000}), user content as typed.
 */
record MeterSnapshot(
        @Nullable String kind,
        @Nullable String resource,
        @Nullable String plot,
        @Nullable String serial,
        @Nullable String name,
        @Nullable String installedOn,
        @Nullable String initialReading,
        @Nullable String removedOn,
        @Nullable String finalReading,
        @Nullable String note) {

    /** The state before a meter exists and after it was deleted. */
    static final MeterSnapshot NONE = new MeterSnapshot(null, null, null, null, null, null, null, null, null, null);

    /**
     * @param plotNumber the number of the meter's plot; null for a meter that is not a plot meter
     */
    static MeterSnapshot of(MeterRecord meter, @Nullable String plotNumber) {
        return new MeterSnapshot(meter.getKind().name(), meter.getResource().name(), plotNumber, meter.getSerial(),
                meter.getName(), meter.getInstalledOn().toString(), meter.getInitialValue().toString(),
                meter.getRemovedOn() == null ? null : meter.getRemovedOn().toString(),
                meter.getFinalValue() == null ? null : meter.getFinalValue().toString(), meter.getNote());
    }

    /** The fields whose value differs, in a fixed order; empty when nothing changed. */
    static List<FieldChange> diff(MeterSnapshot before, MeterSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "kind", before.kind, after.kind);
        add(changes, "resource", before.resource, after.resource);
        add(changes, "plot", before.plot, after.plot);
        add(changes, "serial", before.serial, after.serial);
        add(changes, "name", before.name, after.name);
        add(changes, "installedOn", before.installedOn, after.installedOn);
        add(changes, "initialReading", before.initialReading, after.initialReading);
        add(changes, "removedOn", before.removedOn, after.removedOn);
        add(changes, "finalReading", before.finalReading, after.finalReading);
        add(changes, "note", before.note, after.note);
        return changes;
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before,
            @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
