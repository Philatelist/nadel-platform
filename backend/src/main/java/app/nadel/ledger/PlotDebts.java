package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The debt of one plot on a day after payments ({@link LedgerReadModel#debtsAsOf}), with the rules of the plot account
 * and the plot status (money rules 2, 5.4 and 9, feature 015 §2.5): the charges whose debt date ({@code accrued_on})
 * is on or before the day, reversals counting negative and adjustments with their sign, minus what payments pay on
 * them as of the day. An allocation counts from its value date, the later of the day its payment was received and the
 * debt date of its charge; a release shares the payment and the charge of the allocation it takes back, hence its
 * date. Every amount is the exact database sum of {@code numeric(14,2)} rows; nothing is rounded. Ids and
 * {@code Money} only, no personal data.
 * <p>
 * The three maps have the same keys: every charge type the plot has a counted charge of (also when it sums to zero),
 * in charge type id order; the caller orders and names the types through {@code tariffs.ChargeTypeCatalog}. For each
 * type, debt = charged − paid; the totals are the sums over the types.
 *
 * @param plotId        the plot
 * @param chargedByType the charged amount per charge type (the signed sum of the counted charges)
 * @param paidByType    the net allocations as of the day to the counted charges of each type
 * @param byChargeType  the debt per charge type: charged minus paid
 * @param charged       the sum of the charged amounts per type; zero for a plot without counted charges
 * @param paid          the sum of the paid amounts per type
 * @param total         the debt: the sum of the debts per type ({@code charged - paid})
 * @param prepayment    the payments received on or before the day (reversals by the day of their original) minus every
 *                      allocation of the plot counted as of the day, never below zero; it does not depend on the charge
 *                      type filter
 * @param oldestUnpaid  the oldest debt date among the counted charges that are neither a reversal nor an adjustment,
 *                      were not reversed (by a counted reversal) and whose effective amount (the charge plus its
 *                      counted adjustments) minus what is paid on it as of the day is above zero; null when there is
 *                      none
 * @param daysOverdue   the as-of day minus {@code oldestUnpaid}, in days (the debt age of money rules 9 on that day);
 *                      null when {@code oldestUnpaid} is null
 */
public record PlotDebts(UUID plotId, Map<UUID, Money> chargedByType, Map<UUID, Money> paidByType,
        Map<UUID, Money> byChargeType, Money charged, Money paid, Money total, Money prepayment,
        @Nullable LocalDate oldestUnpaid, @Nullable Integer daysOverdue) {

    public PlotDebts {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(charged, "charged");
        Objects.requireNonNull(paid, "paid");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(prepayment, "prepayment");
        chargedByType = Collections.unmodifiableMap(new LinkedHashMap<>(chargedByType));
        paidByType = Collections.unmodifiableMap(new LinkedHashMap<>(paidByType));
        byChargeType = Collections.unmodifiableMap(new LinkedHashMap<>(byChargeType));
        if ((oldestUnpaid == null) != (daysOverdue == null)) {
            throw new IllegalArgumentException("oldestUnpaid and daysOverdue are both set or both null");
        }
        if (prepayment.signum() < 0) {
            throw new IllegalArgumentException("prepayment is never below zero");
        }
    }

    /**
     * The debts of a plot without payments: what is charged is owed, nothing is paid, no prepayment.
     *
     * @param byChargeType the charged amount, which is the debt, per charge type
     * @param total        their sum
     */
    public PlotDebts(UUID plotId, Map<UUID, Money> byChargeType, Money total, @Nullable LocalDate oldestUnpaid,
            @Nullable Integer daysOverdue) {
        this(plotId, byChargeType, zeros(byChargeType), byChargeType, total, Money.ZERO, total, Money.ZERO,
                oldestUnpaid, daysOverdue);
    }

    /** A plot without counted charges and without prepayment: no types, zero totals, nothing unpaid. */
    public static PlotDebts none(UUID plotId) {
        return new PlotDebts(plotId, Map.of(), Money.ZERO, null, null);
    }

    private static Map<UUID, Money> zeros(Map<UUID, Money> byType) {
        Map<UUID, Money> zeros = new LinkedHashMap<>();
        byType.keySet().forEach(type -> zeros.put(type, Money.ZERO));
        return zeros;
    }
}
