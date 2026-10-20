package app.nadel.plots;

/** What an import does with the ownership rows of one plot. */
public enum OwnershipImportOutcome {
    /** The plot has no current owner: the owners of the file are recorded as its first (or next) owners. */
    FIRST_OWNERS,
    /** The current owners are exactly the members and shares of the file (dates ignored): nothing to do. */
    SKIP
}
