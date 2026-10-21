package app.nadel.meters;

/** What an import row does to the meters of the cooperative. */
public enum MeterImportAction {
    /** The plot has no active meter of the resource: a meter is registered. */
    NEW,
    /** The plot's active meter of the resource has the row's serial number: nothing to do. */
    UNCHANGED
}
