package app.nadel.treasury;

/**
 * What moved the money of a journal line (column {@code account_movement.kind}, V32; feature 018 §2.2). A reversal has
 * the kind of the line it reverses.
 */
public enum MovementKind {

    /** A member payment of feature 015, or its reversal. */
    MEMBER_PAYMENT,

    /** Money spent from an account. */
    EXPENSE,

    /** Money received that is not a member payment. */
    OTHER_INCOME,

    /** The cash line of a handover to the bank. */
    HANDOVER_OUT,

    /** The bank line of a handover to the bank. */
    HANDOVER_IN,

    /** A booked difference of a cash count. */
    COUNT_DIFFERENCE,

    /** A booked difference of a bank reconciliation. */
    BANK_DIFFERENCE
}
