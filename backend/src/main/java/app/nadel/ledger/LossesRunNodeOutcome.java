package app.nadel.ledger;

/** What a proportional losses run did with a node (column {@code losses_run_node.outcome}, money rules 6.6). */
public enum LossesRunNodeOutcome {

    /** Its losses were shared among the plots under it and charged. */
    CHARGED,

    /** Its children used more than it shows: nothing charged, a warning with the numbers. */
    NEGATIVE,

    /** A value of the node or of a child is not known at the end of the range: skipped and queued. */
    UNKNOWN,

    /** Skipped because a node under it is unknown. */
    SKIPPED_ABOVE
}
