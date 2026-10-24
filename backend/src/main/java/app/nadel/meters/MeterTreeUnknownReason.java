package app.nadel.meters;

/**
 * Why a figure of a {@link MeterTreeReport} row ({@link MeterTreeQuantity}) or the cubic metres of a {@link WaterUseRow}
 * are empty: they are never shown as zero.
 */
public enum MeterTreeUnknownReason {

    /**
     * A value of the node's own meter (of the plot's water meter, for water use) at an end of a slice is not known (no
     * reading after it).
     */
    VALUE_UNKNOWN,

    /** The consumption of a child of the node is not known, so the children's sum and the losses are not either. */
    CHILD_VALUE_UNKNOWN
}
