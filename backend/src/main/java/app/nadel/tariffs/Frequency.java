package app.nadel.tariffs;

/** The period a rate is meant for. {@link #ONE_OFF} goes only with {@link Basis#FIXED_ONE_TIME}. */
public enum Frequency {
    MONTHLY,
    QUARTERLY,
    YEARLY,
    ONE_OFF
}
