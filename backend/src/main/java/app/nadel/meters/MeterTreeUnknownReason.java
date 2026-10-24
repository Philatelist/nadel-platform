package app.nadel.meters;

/** Why a figure of a {@link MeterTreeReport} row is empty ({@link MeterTreeQuantity}): it is never shown as zero. */
public enum MeterTreeUnknownReason {

    /** A value of the node's own meter at an end of a slice is not known (no reading after it). */
    VALUE_UNKNOWN,

    /** The consumption of a child of the node is not known, so the children's sum and the losses are not either. */
    CHILD_VALUE_UNKNOWN
}
