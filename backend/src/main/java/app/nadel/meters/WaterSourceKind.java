package app.nadel.meters;

/**
 * The kind of water source a plot is connected to (money rules 11), the same values as the contract's
 * {@code WaterSourceKind}: the cooperative's water supply, which has no meter, or a common consumer meter of a well
 * pump, named by the cooperative.
 */
public enum WaterSourceKind {

    /** The cooperative's water supply (no meter; clients show their own name for it). */
    COOPERATIVE_SUPPLY,

    /** A well: a common consumer meter of its pump ({@code plot_water_connection.source_meter_id}). */
    METER
}
