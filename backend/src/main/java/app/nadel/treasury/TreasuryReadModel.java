package app.nadel.treasury;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only figures of the treasury for the cash book reports (feature 018 §2.12, technical considerations 2.7), so
 * the reports module never reads the treasury tables. Every method runs in the caller's transaction
 * ({@code Propagation.MANDATORY}), which the tenant-aware transaction manager has bound to the cooperative (RLS hides
 * other cooperatives), and needs {@code CASHBOOK_VIEW} ({@code @PreAuthorize}): the lines carry the counterparty of
 * expenses, personal data the board sees with that permission. Amounts are exact {@code Money}; the reader only lists
 * and adds them up.
 * <p>
 * The book of an account and the income and expense need its opening balance and a period on or after its opening
 * date (a balance is known only from then on).
 */
public interface TreasuryReadModel {

    /**
     * The cash book of the days {@code from} to {@code to}: the balance at the start of {@code from}, every day with
     * cash lines (each line, the day's receipts and spendings, its opening and closing balance) and the balance at the
     * end of {@code to}. Lines oldest first (date, time entered, id). At most {@code limit} lines are read: a caller
     * with a row cap passes the cap plus one, and a longer period gives a book cut after {@code limit} lines.
     *
     * @throws app.nadel.problem.ApiProblemException 409 {@code OPENING_BALANCE_MISSING} without the cash opening
     *                                               balance; 400 {@code BEFORE_OPENING_BALANCE} ({@code from}) when
     *                                               {@code from} is before its date
     * @throws IllegalArgumentException              when {@code to} is before {@code from} or {@code limit} below 1
     */
    AccountBook cashBook(LocalDate from, LocalDate to, int limit);

    /** As {@link #cashBook} for the bank account, with the reconciliation status of every month of the period. */
    AccountBook bankMovements(LocalDate from, LocalDate to, int limit);

    /**
     * The original expenses dated {@code from} to {@code to} (reversed ones included and marked), oldest first (date,
     * time entered, id), at most {@code limit}.
     *
     * @param account     only this account; null means both
     * @param categoryIds only these categories; empty means every category
     */
    List<ExpenseLine> expenses(LocalDate from, LocalDate to, @Nullable MoneyAccountKind account,
            Collection<UUID> categoryIds, int limit);

    /**
     * The income and expense of the days {@code from} to {@code to} ({@link IncomeExpense}). Member payments are split
     * by {@code ledger.LedgerReadModel.paymentSplits} as of the end of {@code to} in Europe/Kyiv.
     *
     * @throws app.nadel.problem.ApiProblemException 409 {@code OPENING_BALANCE_MISSING} without both opening
     *                                               balances; 400 {@code BEFORE_OPENING_BALANCE} ({@code from}) when
     *                                               {@code from} is before either opening date
     */
    IncomeExpense incomeExpense(LocalDate from, LocalDate to);

    /** Every expense and income category of the cooperative, archived ones included: expense first, each in order. */
    List<CategoryRef> categories();
}
