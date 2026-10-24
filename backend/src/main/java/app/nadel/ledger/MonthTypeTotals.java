package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The charges of one month and one charge type ({@link LedgerReadModel#monthTotals}), split by kind. Every amount is
 * the exact database sum of the rows ({@code numeric(14,2)}), signed as booked: reversals are negative, adjustments
 * carry their own sign. Nothing is rounded.
 *
 * @param month        the billing month or the service month, as the grouping says
 * @param status       the status of the billing month; {@code null} when grouped by service month
 * @param chargeTypeId the charge type (order and names through {@code tariffs.ChargeTypeCatalog})
 * @param plotCount    the number of distinct plots with any row (of any kind) in the month and type
 * @param regular      the sum of the {@code REGULAR} charges
 * @param opening      the sum of the {@code OPENING} balances
 * @param reversals    the sum of the {@code REVERSAL} rows (zero or negative)
 * @param adjustments  the sum of the {@code ADJUSTMENT} rows (either sign)
 * @param effective    the effective total (money rules 5.4): the sum of every row, charges plus adjustments minus
 *                     reversals; the amount the billing months screen shows for the type and month
 */
public record MonthTypeTotals(YearMonth month, @Nullable MonthStatus status, UUID chargeTypeId, int plotCount,
        Money regular, Money opening, Money reversals, Money adjustments, Money effective) {

    public MonthTypeTotals {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(chargeTypeId, "chargeTypeId");
        Objects.requireNonNull(regular, "regular");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(reversals, "reversals");
        Objects.requireNonNull(adjustments, "adjustments");
        Objects.requireNonNull(effective, "effective");
    }
}
