package app.nadel.plots;

/** What an import row does to the plots of the cooperative. */
public enum PlotImportAction {
    /** No active plot has the number: a plot is created. */
    NEW,
    /** A plot with the number exists and at least one field differs. */
    UPDATE,
    /** A plot with the number exists and every field is already as in the file. */
    UNCHANGED
}
