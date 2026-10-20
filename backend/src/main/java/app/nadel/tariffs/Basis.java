package app.nadel.tariffs;

/** What a tariff rate applies to (money-rules section 4). */
public enum Basis {
    /** The rate per plot. */
    PER_PLOT,
    /** The rate per sotka (100 m2) of the plot's area. */
    PER_AREA_SOTKA,
    /** The rate per kWh of a meter reading. */
    PER_KWH,
    /** The rate per cubic metre of water of a meter reading. */
    PER_M3,
    /** A fixed amount charged once, on its due date; always {@link Frequency#ONE_OFF}. */
    FIXED_ONE_TIME
}
