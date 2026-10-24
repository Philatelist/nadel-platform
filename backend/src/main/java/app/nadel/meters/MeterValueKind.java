package app.nadel.meters;

/** How the value of a meter on a day is known ({@link MeterTreeValue}, money rules 6.4). */
public enum MeterValueKind {

    /** A counted reading, or the initial or final value, on that very day. */
    REAL,

    /** Interpolated by days between the nearest counted points on both sides; shown as calculated. */
    CALCULATED,

    /** No counted point after the day (or none before it); nothing is guessed. */
    UNKNOWN
}
