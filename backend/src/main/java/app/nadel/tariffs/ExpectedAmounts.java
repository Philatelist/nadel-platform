package app.nadel.tariffs;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The amounts the tariffs of a charge type are expected to charge plots over a year, per tariff version (the land tax
 * report, feature 013 §3.7), so a report compares them with what the ledger charged without knowing a money rule. Runs
 * in the caller's transaction ({@code Propagation.MANDATORY}: a call without one fails), which the tenant-aware
 * transaction manager has bound to the cooperative, so the tariffs and plots of other cooperatives are invisible (RLS).
 * Ids, codes, rates and {@code Money} only.
 */
public interface ExpectedAmounts {

    /**
     * For each plot, what the tariffs of the charge type with the code are expected to charge it in the year, one
     * {@link VersionShare} per tariff version, worked out month by month exactly as the ledger's month run charges it
     * (money rules 4.1 and 4.2):
     * <ul>
     * <li>which tariff charges the plot in a month is {@link TariffResolver#resolve} over the month (the scope, the
     * exclusions, an inactive or archived type, an archived plot or common land); of the tariffs it names, the ones
     * whose amount for the month is above {@code 0.00} take part, and exactly one of them charges the month (none, or
     * a conflict of two or more, charges nothing, as in the month run);</li>
     * <li>the month's amount is {@link ChargeCalculator#breakdown} over every day of the month ({@link DaySet#ALL}),
     * rounded once; for a per-sotka yearly version that is {@code rate x sotkas / 12} (35.00 per sotka, 600 m2:
     * 17.50 a month, 210.00 a year);</li>
     * <li>the days of a version are the days of its parts in every month; its amount is the sum of the months it
     * charges alone, plus its part of a month it shares with another version: every version of such a month but the
     * last gets {@link ChargeCalculator#breakdown} over its own days of the month (rounded once) and the last gets the
     * rest of the month's amount, so the shares add up exactly to the months' amounts.</li>
     * </ul>
     * Every day counts: the result is meant for charge types charged on every day, such as land tax, not for water
     * (whose month run counts only the days a plot is connected) nor for tariffs charged by meter readings (which give
     * no amount and so no share).
     *
     * @param chargeTypeCode the code of a seeded charge type (for example {@code LAND_TAX}); a code the cooperative has no
     *                       type for gives an empty map
     * @param year           the calendar year
     * @param plotIds        the plots; ids unknown in the cooperative are ignored
     * @return per plot, in the order of first appearance in {@code plotIds}, the expectation of the year; a plot the
     *         charge type charges on no day of the year (out of every tariff's scope, excluded, archived, common land,
     *         no version valid in the year, a conflict all year) is absent
     */
    Map<UUID, YearExpectation> yearByVersion(String chargeTypeCode, int year, Collection<UUID> plotIds);
}
