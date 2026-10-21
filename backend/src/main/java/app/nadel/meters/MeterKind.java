package app.nadel.meters;

/**
 * The role of a meter in the cooperative: a plot meter belongs to one plot; the others are named and form the
 * electricity tree (a substation at the root, quarter meters under it, common consumers such as a well pump anywhere
 * under a substation or a quarter).
 */
public enum MeterKind {
    PLOT,
    SUBSTATION,
    QUARTER,
    COMMON_CONSUMER
}
