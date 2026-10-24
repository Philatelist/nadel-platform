package app.nadel.ledger;

/** The kind of a row of {@link LedgerReadModel#electricityCharged} (column {@code charge.kind}; never an opening). */
public enum ChargedRowKind {

    /** An accrual: a charge of an interval or of a run. */
    REGULAR,

    /** Cancels one charge with the opposite amount (money rules 3), by hand, by a run reversal or a recalculation. */
    REVERSAL,

    /** Corrects a charge of a closed month in an open month, either sign (money rules 5.4). */
    ADJUSTMENT
}
