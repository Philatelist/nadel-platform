package app.nadel.plots;

/**
 * The status (map colour) of a plot as other modules see it ({@link PlotStatuses}). Rules: docs/domain/money-rules.md,
 * section 9.
 */
public enum PlotStatus {
    /** A debt above the red amount, or a debt older than the red days. */
    RED,
    /** Any other debt. */
    YELLOW,
    /** No debt (zero or less). */
    GREEN,
    /** Common land, or a plot without a current owner. */
    GREY
}
