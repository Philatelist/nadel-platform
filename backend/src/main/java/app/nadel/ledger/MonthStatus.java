package app.nadel.ledger;

/** The status of a billing month (column {@code billing_month.status}, money rules 3.1). */
public enum MonthStatus {
    /** The month accepts new charges. */
    OPEN,
    /** The month is closed for good: nothing is booked in it any more. */
    CLOSED
}
