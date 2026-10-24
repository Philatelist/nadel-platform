package app.nadel.ledger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only figures of the ledger for other modules (the reports of feature 013), so they never read the ledger tables.
 * Every method runs in the caller's transaction ({@code Propagation.MANDATORY}: a call without one fails), which the
 * tenant-aware transaction manager has bound to the cooperative, so the rows of other cooperatives are invisible (RLS).
 * The answers carry ids, codes and {@code Money} only, never personal data; the caller orders and names the charge
 * types and plots through their own modules. Results are bounded by their filters (months times charge types, or the
 * plots asked for), so no row limit applies.
 */
public interface LedgerReadModel {

    /**
     * The charges per month and charge type, split by kind, aggregated in the database. With
     * {@link MonthGrouping#BILLING_MONTH} the months are the billing months from {@code fromMonth} to {@code toMonth}
     * and the effective total of a month and type is exactly the total the billing months screen shows for it (the same
     * predicate: every row booked in the month, of every kind, summed with its sign). With
     * {@link MonthGrouping#SERVICE_MONTH} the months are those of each row's first service day ({@code service_from}),
     * from the first day of {@code fromMonth} to the last day of {@code toMonth}.
     * <p>
     * A month and type without any row is absent (a month without charges has no row at all). Rows come ordered by
     * month, then by charge type id; the caller puts the types in their configured order.
     *
     * @param fromMonth     the first month, inclusive
     * @param toMonth       the last month, inclusive; not before {@code fromMonth}
     * @param chargeTypeIds the charge types to count; empty means every type
     * @param plotIds       the plots to count (a street filter resolved by the caller); {@code null} means every plot,
     *                      an empty set gives no rows
     * @param grouping      by billing month or by service month
     * @throws IllegalArgumentException when {@code toMonth} is before {@code fromMonth}
     */
    List<MonthTypeTotals> monthTotals(YearMonth fromMonth, YearMonth toMonth, Set<UUID> chargeTypeIds,
            @Nullable Set<UUID> plotIds, MonthGrouping grouping);

    /**
     * The debt of each plot as of a day (plot balances and debtors, feature 013 §3.2 and §3.3), aggregated in the
     * database with the rule of the plot account and the plot status cache ({@code DebtCalculator}, money rules 2, 5.4
     * and 9), applied to the charges whose debt date ({@code accrued_on}) is on or before {@code asOf} and whose charge
     * type is among {@code chargeTypeIds}:
     * <ul>
     * <li>the debt of a type is the signed sum of those charges: reversals negative, adjustments with their sign, opening
     * balances included;</li>
     * <li>the oldest unpaid date is the oldest debt date among those charges that are neither a reversal nor an
     * adjustment, were not reversed by one of those charges, and whose amount plus its adjustments among those charges is
     * above zero (until payments exist nothing is paid);</li>
     * <li>the days overdue are {@code asOf} minus that date.</li>
     * </ul>
     * A reversal and an adjustment keep the debt date and the charge type of the charge they correct, so the day and
     * type filters keep or drop a charge together with its corrections. The day is the debt date, not the booking time:
     * an adjustment booked later for a charge dated before {@code asOf} counts, as on the plot account. With
     * {@code asOf} today or later and every type, the totals and the oldest date are those of the plot account and of
     * the {@code plot_status} cache.
     *
     * @param asOf          the last debt date counted
     * @param chargeTypeIds the charge types to count; empty means every type
     * @param plotIds       the plots; every distinct id is a key of the result (a plot without counted charges, or an
     *                      id unknown in the cooperative, gets {@link PlotDebts#none}), in the order of first
     *                      appearance
     */
    Map<UUID, PlotDebts> debtsAsOf(LocalDate asOf, Set<UUID> chargeTypeIds, Collection<UUID> plotIds);
}
