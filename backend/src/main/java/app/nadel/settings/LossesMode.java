package app.nadel.settings;

/** How losses in the electricity network are charged (feature 011, functional spec 2.5). */
public enum LossesMode {
    /** Every electricity interval of a plot also charges a fixed percentage of its kWh. The default. */
    FIXED_PERCENT,
    /** Losses are charged by runs over a date range, shared among the plots under each node of the meter tree. */
    PROPORTIONAL
}
