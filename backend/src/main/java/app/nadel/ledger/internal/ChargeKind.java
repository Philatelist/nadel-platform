package app.nadel.ledger.internal;

/**
 * The kind of a charge (column {@code charge.kind}, the {@code ChargeKind} of the API contract). {@code REGULAR} is an
 * accrual, {@code REVERSAL} cancels one charge (negative, with a reason, pointing at it), {@code OPENING} an opening
 * balance. {@code ADJUSTMENT} is reserved for feature 012.
 */
enum ChargeKind {
    REGULAR,
    REVERSAL,
    OPENING
}
