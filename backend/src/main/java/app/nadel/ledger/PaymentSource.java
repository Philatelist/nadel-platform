package app.nadel.ledger;

/**
 * Where the money of a payment came from (column {@code payment.source}, V27; feature 015 §2.1). Feature 016 adds the
 * bank statement import.
 */
public enum PaymentSource {

    /** Cash taken at the cooperative's cash desk: the payment carries a receipt number. */
    CASH,

    /** A bank transfer entered by hand: no receipt number, an optional bank document number. */
    BANK
}
