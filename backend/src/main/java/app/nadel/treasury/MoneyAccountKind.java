package app.nadel.treasury;

/** The two money accounts of a cooperative (column {@code money_account.kind}, V32; feature 018 §2.1). */
public enum MoneyAccountKind {

    /** The cash the treasurer holds («Каса»): it never goes negative by an entry of the board. */
    CASH,

    /** The cooperative's bank account («Банківський рахунок»): it may go negative (an overdraft). */
    BANK
}
