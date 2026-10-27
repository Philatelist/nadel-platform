package app.nadel.tariffs;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a charge type is expected to charge one plot over a year ({@link ExpectedAmounts#yearByVersion}), one share per
 * tariff version that charges it in the year.
 *
 * @param plotId    the plot
 * @param areaM2    the plot's area in square metres, as stored (two decimals)
 * @param areaSotka the area in sotkas, exact ({@link ChargeCalculator#sotkas}: 600.00 m2 is 6.0000), the units the
 *                  calculation used for a per-sotka version; for display only
 * @param shares    one per version, oldest first (by the first day it charges); never empty
 * @param total     the expected yearly amount: the sum of the shares, which is the sum of the monthly amounts
 *                  {@link ChargeCalculator} gives for the twelve months (each rounded once); nothing is rounded again
 */
public record YearExpectation(UUID plotId, BigDecimal areaM2, BigDecimal areaSotka, List<VersionShare> shares,
        Money total) {

    public YearExpectation {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(areaM2, "areaM2");
        Objects.requireNonNull(areaSotka, "areaSotka");
        Objects.requireNonNull(total, "total");
        shares = List.copyOf(shares);
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("A plot the charge type does not charge has no expectation");
        }
    }
}
