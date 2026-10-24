package app.nadel.ledger;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only figures of the ledger for other modules (the reports of feature 013), so they never read the ledger tables.
 * Every method runs in the caller's transaction ({@code Propagation.MANDATORY}: a call without one fails), which the
 * tenant-aware transaction manager has bound to the cooperative, so the rows of other cooperatives are invisible (RLS).
 * The answers carry ids, codes and {@code Money} only, never personal data; the caller orders and names the charge
 * types and plots through their own modules. Results are bounded by their filters (months times charge types), so no
 * row limit applies.
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
}
