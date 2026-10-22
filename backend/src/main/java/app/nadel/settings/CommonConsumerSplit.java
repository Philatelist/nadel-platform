package app.nadel.settings;

/** How the consumption of a common consumer is shared among plots (feature 011, functional spec 2.6). */
public enum CommonConsumerSplit {
    /** Equally among the plots connected to that water source, day by day. The default. */
    CONNECTED_PLOTS,
    /** Equally among all active plots, with or without meters. */
    EQUAL_PER_PLOT,
    /** Equally among the plots marked as using the consumer. */
    SERVICE_USERS,
    /** Covered by the membership fee: shown in a run, nothing is charged. */
    NOT_CHARGED
}
