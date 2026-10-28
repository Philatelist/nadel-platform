package app.nadel.ledger.internal;

/**
 * Who placed a positive allocation row on its charge (column {@code allocation.origin}, V27; feature 015 §2.2, §2.4).
 */
enum AllocationOrigin {

    /** Placed when the payment was entered (the proposal or the treasurer's lines). */
    ENTRY,

    /** Placed again when the payment was reallocated (its earlier allocations released first). */
    REALLOCATION,

    /** The plot's prepayment placed by hand ("Allocate prepayment"). */
    PREPAYMENT_MANUAL,

    /** The plot's prepayment placed by the system after new charges were booked (the setting is on). */
    PREPAYMENT_AUTO
}
