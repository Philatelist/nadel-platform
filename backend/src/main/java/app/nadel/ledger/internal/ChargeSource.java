package app.nadel.ledger.internal;

/**
 * What produced a charge (column {@code charge.source}, the {@code ChargeSource} of the API contract).
 * {@code RECALCULATION} marks the adjustments and reversals a recalculation books (feature 012), so a reversal made by
 * hand ({@code MANUAL_REVERSAL}) stays recognisable.
 */
enum ChargeSource {
    TARIFF,
    CONSUMPTION,
    LOSSES,
    COMMON_CONSUMER,
    OPENING,
    MANUAL_REVERSAL,
    RECALCULATION
}
