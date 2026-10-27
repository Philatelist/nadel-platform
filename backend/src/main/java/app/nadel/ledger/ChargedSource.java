package app.nadel.ledger;

/**
 * What produced the charge behind a row of {@link LedgerReadModel#electricityCharged} or
 * {@link LedgerReadModel#waterCharged}: the source of the charge itself, and for a reversal or an adjustment the source
 * of the charge it corrects (whose own source is {@code MANUAL_REVERSAL} or {@code RECALCULATION}).
 */
public enum ChargedSource {

    /** An interval of a plot meter (money rules 6.5): kWh for electricity, m³ for water. */
    CONSUMPTION,

    /** Losses: the fixed percentage of an interval (6.5) or a plot's share of a proportional losses run (6.6). */
    LOSSES,

    /** A plot's share of a common consumers run (6.7); in the water rows only a well pump's share. */
    COMMON_CONSUMER,

    /**
     * A tariff charge of the month run (money rules 4.1): in the water rows the fixed water fee, for the connected days
     * only (money rules 11). Never in the electricity rows.
     */
    TARIFF
}
