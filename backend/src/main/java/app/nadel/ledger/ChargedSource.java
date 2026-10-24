package app.nadel.ledger;

/**
 * What produced the charge behind a row of {@link LedgerReadModel#electricityCharged}: the source of the charge itself,
 * and for a reversal or an adjustment the source of the charge it corrects (whose own source is
 * {@code MANUAL_REVERSAL} or {@code RECALCULATION}).
 */
public enum ChargedSource {

    /** An interval of a plot meter (money rules 6.5). */
    CONSUMPTION,

    /** Losses: the fixed percentage of an interval (6.5) or a plot's share of a proportional losses run (6.6). */
    LOSSES,

    /** A plot's share of a common consumers run (6.7). */
    COMMON_CONSUMER
}
