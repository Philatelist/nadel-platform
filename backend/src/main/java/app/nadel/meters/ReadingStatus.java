package app.nadel.meters;

/**
 * Where a reading stands (the {@code ReadingStatus} of the API contract): {@code APPROVED} counts, {@code PENDING} waits
 * for the board's approval (a member's submission), {@code REJECTED} was not approved, {@code SUPERSEDED} was replaced
 * by a later reading of the same meter and date or by a correction.
 */
public enum ReadingStatus {
    APPROVED,
    PENDING,
    REJECTED,
    SUPERSEDED
}
