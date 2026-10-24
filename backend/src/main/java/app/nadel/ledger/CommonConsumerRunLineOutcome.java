package app.nadel.ledger;

/**
 * What a common consumer run did with a consumer (column {@code common_consumer_run_line.outcome}, money rules 6.7).
 */
public enum CommonConsumerRunLineOutcome {

    /** Its consumption was split among plots and charged. */
    CHARGED,

    /** Its split is {@code NOT_CHARGED} (covered by the membership fee): listed with its consumption only. */
    NOT_CHARGED,

    /** No plot shares it in the range. */
    NO_PLOTS,

    /** Its consumption is not known: skipped and queued. */
    UNKNOWN
}
