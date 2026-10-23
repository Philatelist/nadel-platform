package app.nadel.ledger.internal;

/** What produced a charge (column {@code charge.source}, the {@code ChargeSource} of the API contract). */
enum ChargeSource {
    TARIFF,
    CONSUMPTION,
    LOSSES,
    COMMON_CONSUMER,
    OPENING,
    MANUAL_REVERSAL
}
