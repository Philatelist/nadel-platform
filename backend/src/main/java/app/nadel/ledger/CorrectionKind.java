package app.nadel.ledger;

/** The kind of a correction ({@link LedgerReadModel#corrections}): the two kinds of rows that change the past. */
public enum CorrectionKind {

    /** A {@code REVERSAL} row: cancels one charge with the opposite amount (money rules 3). */
    REVERSAL,

    /** An {@code ADJUSTMENT} row: corrects a charge of a closed month in an open month (money rules 5.4). */
    ADJUSTMENT
}
