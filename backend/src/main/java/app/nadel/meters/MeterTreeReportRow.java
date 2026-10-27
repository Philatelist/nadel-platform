package app.nadel.meters;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A node of the electricity tree under one parent over the range of a {@link MeterTreeReport}: the meter it shows, its
 * slices with their values and consumption exactly as {@code GET /meter-tree} serves them, and the figures derived from
 * them. Meter serials and names and plot numbers and streets are user content, shown as entered; there is no personal
 * data.
 *
 * @param level               the depth in the tree: 0 for a root, 1 for a node under a root, ...
 * @param parentMeterId       the meter the parent node shows; null for a root
 * @param meterId             the meter the node shows: the latest of its chain installed by the end of the range
 * @param meterIds            the meters of the node's chain of replacements, oldest installation first
 * @param kind                the kind of the meter
 * @param serial              the serial number of the meter shown
 * @param name                the name of a meter that is not a plot meter; null for a plot meter
 * @param plotId              the plot of a plot meter; null for the other kinds
 * @param plotNumber          the number of that plot; null without a plot
 * @param street              the street of that plot; null without a plot or street
 * @param installedOn         the installation day of the meter shown
 * @param removedOn           the removal day of the meter shown; null while it works
 * @param active              whether the meter shown is not removed
 * @param slices              the parts of the range the node spent under this parent, in date order
 * @param consumption         the node's consumption over its slices: their sum, unknown when any slice is
 *                            ({@link MeterTreeUnknownReason#VALUE_UNKNOWN} with the latest date after which there is no
 *                            reading)
 * @param childrenConsumption for a substation or quarter meter, the sum of its children's consumption under it (zero
 *                            without children), unknown when any child's is
 *                            ({@link MeterTreeUnknownReason#CHILD_VALUE_UNKNOWN}); null for a plot meter or common
 *                            consumer, which have no children
 * @param losses              for a substation or quarter meter, consumption minus the children's consumption in kWh and
 *                            in percent of the consumption; unknown with the reason when either side is unknown; null
 *                            for a plot meter or common consumer
 * @param fedPlotIds          the distinct plots fed through this node: the plots of the plot meters in its subtree under
 *                            this parent, itself included, in the order of the tree (a plot meter feeds its own plot; a
 *                            common consumer, which feeds no plot meter, none)
 */
public record MeterTreeReportRow(int level, @Nullable UUID parentMeterId, UUID meterId, List<UUID> meterIds,
        MeterKind kind, String serial, @Nullable String name, @Nullable UUID plotId, @Nullable String plotNumber,
        @Nullable String street, LocalDate installedOn, @Nullable LocalDate removedOn, boolean active,
        List<MeterTreeReportSlice> slices, MeterTreeQuantity consumption,
        @Nullable MeterTreeQuantity childrenConsumption, @Nullable MeterTreeLosses losses, List<UUID> fedPlotIds) {

    public MeterTreeReportRow {
        if (level < 0) {
            throw new IllegalArgumentException("A level is not negative");
        }
        Objects.requireNonNull(meterId, "meterId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(installedOn, "installedOn");
        Objects.requireNonNull(consumption, "consumption");
        meterIds = List.copyOf(meterIds);
        slices = List.copyOf(slices);
        fedPlotIds = List.copyOf(fedPlotIds);
        boolean node = kind == MeterKind.SUBSTATION || kind == MeterKind.QUARTER;
        if (node != (childrenConsumption != null) || node != (losses != null)) {
            throw new IllegalArgumentException("Only a substation or quarter meter has children and losses");
        }
    }

    /** The number of plots fed through this node ({@link #fedPlotIds}). */
    public int plotsFed() {
        return fedPlotIds.size();
    }
}
