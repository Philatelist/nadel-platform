package app.nadel.ledger.internal;

/**
 * The kind of a payment row (column {@code payment.kind}, V27). {@code PAYMENT} is money received for a plot (above
 * zero); {@code PAYMENT_REVERSAL} cancels one payment of the same plot (the negated amount, with a reason, pointing at
 * it). A payment is reversed at most once.
 */
enum PaymentKind {
    PAYMENT,
    PAYMENT_REVERSAL
}
