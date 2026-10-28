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
 * The charged debt of one plot on a day ({@link LedgerReadModel#debtsAsOf}), with the charge rules of the plot account
 * and the plot status (money rules 2, 5.4 and 9): the charges whose debt date ({@code accrued_on}) is on or before the
 * day, reversals counting negative and adjustments with their sign. Payments are not subtracted here: the plot account
 * and the plot status subtract what payments pay on the charges (feature 015 §2.5), the reports read this sum until
 * they gain their paid columns (feature 015 §6). Every amount is the exact database sum of {@code numeric(14,2)} rows;
 * nothing is rounded. Ids and {@code Money} only, no personal data.
 *
 * @param plotId       the plot
 * @param byChargeType the debt per charge type, for every type the plot has a counted charge of (also when it sums to
 *                     zero), in charge type id order; the caller orders and names the types through
 *                     {@code tariffs.ChargeTypeCatalog}
 * @param total        the sum of the debts per type; zero for a plot without counted charges
 * @param oldestUnpaid the oldest debt date among the counted charges that are neither a reversal nor an adjustment,
 *                     were not reversed (by a counted reversal) and whose effective amount (the charge plus its counted
 *                     adjustments) is above zero; null when there is none. Payments are not counted here, so this is
 *                     the oldest charged date, not the oldest unpaid one of the plot account
 * @param daysOverdue  the as-of day minus {@code oldestUnpaid}, in days (the debt age of money rules 9 on that day);
 *                     null when {@code oldestUnpaid} is null
 */
public record PlotDebts(UUID plotId, Map<UUID, Money> byChargeType, Money total, @Nullable LocalDate oldestUnpaid,
        @Nullable Integer daysOverdue) {

    public PlotDebts {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(total, "total");
        byChargeType = Collections.unmodifiableMap(new LinkedHashMap<>(byChargeType));
        if ((oldestUnpaid == null) != (daysOverdue == null)) {
            throw new IllegalArgumentException("oldestUnpaid and daysOverdue are both set or both null");
        }
    }

    /** A plot without counted charges: no types, zero total, nothing unpaid. */
    public static PlotDebts none(UUID plotId) {
        return new PlotDebts(plotId, Map.of(), Money.ZERO, null, null);
    }
}
