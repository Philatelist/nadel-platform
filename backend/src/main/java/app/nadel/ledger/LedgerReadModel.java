package app.nadel.ledger;

import app.nadel.money.Money;
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
 * types and plots through their own modules. Aggregates are bounded by their filters (months times charge types, or the
 * plots or meters asked for), so no row limit applies to them; {@link #corrections}, {@link #electricityCharged} and
 * {@link #waterCharged}, lists of single rows, take a limit; the runs of a range are bounded by the runs times the nodes or consumers of the tree.
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
     * above zero (payments are not counted here: see below);</li>
     * <li>the days overdue are {@code asOf} minus that date.</li>
     * </ul>
     * A reversal and an adjustment keep the debt date and the charge type of the charge they correct, so the day and
     * type filters keep or drop a charge together with its corrections. The day is the debt date, not the booking time:
     * an adjustment booked later for a charge dated before {@code asOf} counts, as on the plot account. This is the
     * charged debt: the plot account and the {@code plot_status} cache subtract what payments pay on the charges
     * (feature 015 §2.5), so they equal these totals and this oldest date only for a plot without payments (with
     * {@code asOf} today or later and every type).
     *
     * @param asOf          the last debt date counted
     * @param chargeTypeIds the charge types to count; empty means every type
     * @param plotIds       the plots; every distinct id is a key of the result (a plot without counted charges, or an
     *                      id unknown in the cooperative, gets {@link PlotDebts#none}), in the order of first
     *                      appearance
     */
    Map<UUID, PlotDebts> debtsAsOf(LocalDate asOf, Set<UUID> chargeTypeIds, Collection<UUID> plotIds);

    /**
     * Every reversal and adjustment booked in the billing months from {@code fromMonth} to {@code toMonth} (the
     * corrections report, feature 013 §3.8), one {@link CorrectionRow} per row, with the service period and the booked
     * amount of the charge it corrects, its source ({@link CorrectionSource}: manual reversal, recalculation or run
     * reversal), the cause of its recalculation and the member who booked it. Charges of other kinds (regular charges,
     * opening balances) are never listed.
     * <p>
     * Rows come ordered by billing month, then by the time they were booked, then by id; the caller puts plots in their
     * natural order if it wants another order. At most {@code limit} rows are returned: a caller with a row cap passes
     * the cap plus one, so that one row more than the cap tells it the result is too large.
     *
     * @param fromMonth     the first billing month, inclusive
     * @param toMonth       the last billing month, inclusive; not before {@code fromMonth}
     * @param chargeTypeIds the charge types; empty means every type
     * @param kinds         the kinds; empty means both
     * @param limit         the most rows returned; at least 1
     * @throws IllegalArgumentException when {@code toMonth} is before {@code fromMonth} or {@code limit} is below 1
     */
    List<CorrectionRow> corrections(YearMonth fromMonth, YearMonth toMonth, Set<UUID> chargeTypeIds,
            Set<CorrectionKind> kinds, int limit);

    /**
     * The effective amount charged to each plot for a year by the charge type with the code (the land tax report,
     * feature 013 §3.7: "charges of type {@code LAND_TAX} with a service period in the year"): the signed sum (money
     * rules 5.4) of the regular charges, reversals and adjustments of that type whose service period starts in the year
     * ({@code service_from} from 1 January to 31 December). A reversal and an adjustment keep the service period of the
     * charge they correct, so they count in the year of that charge, whatever month they were booked in. Opening
     * balances are left out: they carry a debt from before the ledger, not a charge for the year.
     *
     * @param chargeTypeCode the code of a seeded charge type (for example {@code LAND_TAX}); a code the cooperative has no
     *                       type for gives {@code 0.00} for every plot
     * @param year           the calendar year
     * @param plotIds        the plots; every distinct id is a key of the result, in the order of first appearance
     *                       ({@code 0.00} for a plot without such charges or unknown in the cooperative); asked 500 per
     *                       query
     */
    Map<UUID, Money> chargedInYear(String chargeTypeCode, int year, Collection<UUID> plotIds);

    /**
     * The effective amount charged per charge type for a range of service days (the totals
     * {@code totals.charged.<type>} of report templates, feature 013 §4.1), the rule of {@link #chargedInYear} over a
     * range and every plot: the signed sum (money rules 5.4) of the regular charges, reversals and adjustments of each
     * charge type whose service period starts in the range ({@code service_from} from {@code from} to {@code to}), on
     * every plot of the cooperative (archived plots and common land included: what was charged stays charged). A
     * reversal and an adjustment keep the service period of the charge they correct, so they count with it, whatever
     * month they were booked in. Opening balances are left out.
     *
     * @param from the first day, inclusive
     * @param to   the last day, inclusive; not before {@code from}
     * @return the charge types with at least one such row, keyed by id (a type without one is absent: {@code 0.00});
     *         bounded by the charge types of the cooperative
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    Map<UUID, Money> chargedByType(LocalDate from, LocalDate to);

    /**
     * The electricity charges whose service period overlaps a range of days (the "Charged" sheet of the electricity
     * report, feature 013 §3.4), one {@link ChargedRow} per ledger row: the charges of source {@code CONSUMPTION} and
     * {@code LOSSES} of the charge type {@code ELECTRICITY} (resolved by code through {@code tariffs.ChargeTypeCatalog})
     * and of source {@code COMMON_CONSUMER} (their type is the common consumers one), and every reversal and adjustment
     * of those charges, each its own row with the service period of the charge it corrects, whatever month it was
     * booked in. Water charges, tariff charges and opening balances are never listed. A run's charges are listed like
     * any other: a reversed run comes with its charges and their reversals, which add up to zero.
     * <p>
     * The amounts of the rows of a plot add up to the effective electricity charges of the range on its account (money
     * rules 5.4), and their {@link ChargedRow#quantity quantities} to the effective kWh. Rows come ordered by the
     * charge's service period, plot, source, booking time and id, each regular charge followed by its corrections in
     * booking order. At most
     * {@code limit} rows are returned: a caller with a row cap passes the cap plus one.
     *
     * @param from  the first day of the range
     * @param to    the last day of the range; not before {@code from}
     * @param limit the most rows returned; at least 1
     * @throws IllegalArgumentException when {@code to} is before {@code from} or {@code limit} is below 1
     */
    List<ChargedRow> electricityCharged(LocalDate from, LocalDate to, int limit);

    /**
     * The water charges of plots whose service period overlaps a range of days (the water report, feature 013 §3.5),
     * one {@link ChargedRow} per ledger row, as {@link #electricityCharged} lists the electricity ones: the charges of
     * source {@code CONSUMPTION} (by meter, m³) and {@code TARIFF} (the fixed water fee) of the charge type
     * {@code WATER} (resolved by code through {@code tariffs.ChargeTypeCatalog}), and a well pump's share: the
     * {@code COMMON_CONSUMER} charges (their type is the common consumers one) whose calculation names the split
     * {@code CONNECTED_PLOTS}, the split that shares a consumer among the plots connected to it as their water source
     * (money rules 6.7, 11; the ledger stores no other link between a common consumer and water). Every reversal and
     * adjustment of those charges is its own row with the service period of the charge it corrects, whatever month it
     * was booked in. Opening balances and other types are never listed. A well pump's share is also a row of
     * {@link #electricityCharged} (it is electricity priced at the common consumers tariff); a report that shows both
     * counts it in each.
     * <p>
     * The amounts of the rows of a plot add up to its effective water charges and well pump shares of the range (money
     * rules 5.4). {@link ChargedRow#quantity} is m³ for a consumption row, kWh for a pump share and null for a fixed fee.
     * Rows come ordered by the charge's service period, plot, source, booking time and id, each regular charge followed
     * by its corrections in booking order. At most {@code limit} rows are returned: a caller with a row cap passes the
     * cap plus one.
     *
     * @param from    the first day of the range
     * @param to      the last day of the range; not before {@code from}
     * @param plotIds the plots (the plots of the water report's rows); an empty collection gives no rows. Asked 500
     *                per query
     * @param limit   the most rows returned; at least 1
     * @throws IllegalArgumentException when {@code to} is before {@code from} or {@code limit} is below 1
     */
    List<ChargedRow> waterCharged(LocalDate from, LocalDate to, Collection<UUID> plotIds, int limit);

    /**
     * The number of open "not charged" items of kind {@code INTERVAL} (a reading interval of a plot meter that could not
     * be charged, money rules 3.3) per meter: the items of the queue's list ({@code GET /not-charged}, status
     * {@code OPEN}) whose meter is one of {@code meterIds} (the missing readings report, feature 013 §3.6).
     *
     * @param meterIds the meters; every distinct id is a key of the result, in the order of first appearance ({@code 0}
     *                 for a meter without such items or unknown in the cooperative). Asked 500 per query
     */
    Map<UUID, Integer> openIntervalItems(Collection<UUID> meterIds);

    /**
     * The nodes of every proportional losses run whose range overlaps a range of days (money rules 6.6), booked or
     * reversed, as they were booked: one {@link LossesRunNodeRow} per node of a run. A reversed run is listed with
     * {@link RunStatus#REVERSED}, consistently with {@link #electricityCharged}, which lists its charges together with
     * their reversals; the caller shows or leaves it out. Rows come ordered by the run's range, booking time and id,
     * then by the node's position. Bounded by the runs of the range times the nodes of the tree, so no limit applies.
     *
     * @param from the first day of the range
     * @param to   the last day of the range; not before {@code from}
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    List<LossesRunNodeRow> lossesRunsInRange(LocalDate from, LocalDate to);

    /**
     * The consumers of every common consumers run whose range overlaps a range of days (money rules 6.7), booked or
     * reversed, as they were booked: one {@link CommonConsumerRunLineRow} per consumer of a run. Reversed runs are
     * listed with {@link RunStatus#REVERSED}, as by {@link #lossesRunsInRange}. Rows come ordered by the run's range,
     * booking time and id, then by the consumer's position. No limit applies.
     *
     * @param from the first day of the range
     * @param to   the last day of the range; not before {@code from}
     * @throws IllegalArgumentException when {@code to} is before {@code from}
     */
    List<CommonConsumerRunLineRow> commonConsumerRunsInRange(LocalDate from, LocalDate to);
}
