package app.nadel.ledger.internal;

/**
 * Why a release row (a negative allocation) takes money back from a charge (column {@code allocation.release_cause},
 * V27; feature 015 §2.3). The money released becomes the plot's prepayment again, except for a reversed payment,
 * whose money is gone.
 */
enum ReleaseCause {

    /** The charge was reversed (by hand, by a run reversal or by a recalculation): everything on it is released. */
    CHARGE_REVERSED,

    /** An adjustment lowered the charge below what is allocated to it: the excess is released, latest first. */
    CHARGE_ADJUSTED,

    /** The payment was reversed: every allocation of it is released. */
    PAYMENT_REVERSED,

    /** The payment is being reallocated: its allocations are released before the new ones are written. */
    REALLOCATED
}
