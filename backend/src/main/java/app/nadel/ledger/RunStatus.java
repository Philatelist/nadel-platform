package app.nadel.ledger;

/** The status of a losses or common consumer run (column {@code status}, money rules 6.6 and 6.7). */
public enum RunStatus {

    /** The run's charges stand. */
    BOOKED,

    /** The whole run was reversed: each of its charges has a reversal, and its days are free again. */
    REVERSED
}
