package app.nadel.treasury;

/**
 * The reconciliation status of a bank month (column {@code bank_reconciliation.status}, V32; feature 018 §2.7). A month
 * without a reconciliation row is {@link #NOT_RECONCILED}. The transitions the database allows
 * ({@code bank_reconciliation_guard}): {@code NOT_RECONCILED → RECONCILED → NEEDS_RECHECK → RECONCILED}.
 */
public enum BankMonthStatus {

    /** «Не звірено»: not confirmed yet (also before the statement is entered). */
    NOT_RECONCILED,

    /** «Звірено»: the statement agrees with the books and the month was confirmed. */
    RECONCILED,

    /** «Потребує повторної звірки»: a bank line dated in the month was added after it was reconciled. */
    NEEDS_RECHECK
}
