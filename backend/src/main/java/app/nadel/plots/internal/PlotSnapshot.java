package app.nadel.plots.internal;

import app.nadel.audit.FieldChange;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The audited fields of a plot as strings, so two states can be compared and the difference written to the audit
 * log. The area is its plain decimal text with two places ({@code 600.00}); an empty value is null.
 */
record PlotSnapshot(
        @Nullable String number,
        @Nullable String street,
        @Nullable String kind,
        @Nullable String areaM2,
        @Nullable String cadastralNumber,
        boolean hasBuilding,
        @Nullable String notes,
        boolean archived) {

    /** The state before a plot exists: every field empty, no building, not archived. */
    static final PlotSnapshot NONE = new PlotSnapshot(null, null, null, null, null, false, null, false);

    static PlotSnapshot of(Plot plot) {
        return new PlotSnapshot(
                plot.getNumber(),
                plot.getStreet(),
                plot.getKind().name(),
                areaText(plot.getAreaM2()),
                plot.getCadastralNumber(),
                plot.hasBuilding(),
                plot.getNotes(),
                plot.getArchivedAt() != null);
    }

    /** The state a plot would have with this data: not archived, the area as its plain two-place text. */
    static PlotSnapshot of(PlotData data) {
        return new PlotSnapshot(data.number(), data.street(), data.kind().name(), areaText(data.areaM2()),
                data.cadastralNumber(), data.hasBuilding(), data.notes(), false);
    }

    /** The fields whose value differs, in a fixed order; empty when nothing changed. */
    static List<FieldChange> diff(PlotSnapshot before, PlotSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "number", before.number, after.number);
        add(changes, "street", before.street, after.street);
        add(changes, "kind", before.kind, after.kind);
        add(changes, "areaM2", before.areaM2, after.areaM2);
        add(changes, "cadastralNumber", before.cadastralNumber, after.cadastralNumber);
        add(changes, "hasBuilding", String.valueOf(before.hasBuilding), String.valueOf(after.hasBuilding));
        add(changes, "notes", before.notes, after.notes);
        add(changes, "archived", String.valueOf(before.archived), String.valueOf(after.archived));
        return changes;
    }

    /** Presentation only: the stored area already has two places, so nothing is rounded here. */
    private static String areaText(BigDecimal area) {
        return area.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static void add(List<FieldChange> changes, String field, @Nullable String before,
            @Nullable String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }
}
