package app.nadel.ledger;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One ledger row of an electricity charge ({@link LedgerReadModel#electricityCharged}, the "Charged" sheet of the
 * electricity report, feature 013 §3.4): a regular charge, a reversal or an adjustment, each its own row, with the
 * service period of the charge it belongs to. One row per charge, never per part: a charge's parts are added exactly
 * and rounded once (money rules 6.5), so an amount per part would round twice. The parts behind the row and their
 * rates come with it; {@link #rate()} is the price per kWh when they all share one.
 *
 * @param chargeId         the row itself
 * @param kind             regular, reversal or adjustment
 * @param source           what produced the charge: its own source, for a reversal or an adjustment that of the charge
 *                         it corrects
 * @param plotId           the plot
 * @param chargeTypeId     the charge type (electricity for consumption and losses, the common consumers type for a
 *                         common consumer share)
 * @param billingMonth     the billing month the row is booked in
 * @param serviceFrom      the first service day of the charge (for a reversal or an adjustment, of the charge it
 *                         corrects: the original service period)
 * @param serviceTo        the last service day of that charge
 * @param accruedOn        the debt date of the row (that of the corrected charge for a correction)
 * @param originalChargeId the charge a reversal reverses or an adjustment adjusts; null for a regular charge
 * @param runId            the losses or common consumer run of the charge; null for an interval charge
 * @param meterId          the plot meter of an interval charge (consumption, fixed-percentage losses); null for a run
 * @param quantity         the kWh this row adds to the ledger, exact and signed: a regular charge its parts' sum, a
 *                         reversal minus the quantity it cancels, an adjustment the change it makes (its parts, which
 *                         are the corrected charge's parts as recalculated, minus those of the charge's state before
 *                         it: the previous adjustment's, else the charge's own), so the rows of a charge add up to its
 *                         effective quantity as they add up to its effective amount
 * @param parts            the parts behind the row: a regular charge's and an adjustment's own, a reversal's those of
 *                         the state it cancels with their quantities negated; empty when none were stored (an
 *                         adjustment to nothing)
 * @param amount           the amount, signed as booked
 */
public record ChargedRow(UUID chargeId, ChargedRowKind kind, ChargedSource source, UUID plotId, UUID chargeTypeId,
        YearMonth billingMonth, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn,
        @Nullable UUID originalChargeId, @Nullable UUID runId, @Nullable UUID meterId, Quantity quantity,
        List<ChargedPart> parts, Money amount) {

    public ChargedRow {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(chargeTypeId, "chargeTypeId");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(serviceFrom, "serviceFrom");
        Objects.requireNonNull(serviceTo, "serviceTo");
        Objects.requireNonNull(accruedOn, "accruedOn");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(amount, "amount");
        parts = List.copyOf(parts);
        if ((kind == ChargedRowKind.REGULAR) != (originalChargeId == null)) {
            throw new IllegalArgumentException("A reversal or an adjustment points at its charge, a regular one not");
        }
    }

    /**
     * The price per kWh of the row: the rate of its parts when they all share one; null when they have different
     * rates (a tariff version changed within the charge's days: see {@link #parts}) or there are none.
     */
    public @Nullable BigDecimal rate() {
        BigDecimal rate = null;
        for (ChargedPart part : parts) {
            if (rate == null) {
                rate = part.rate();
            } else if (rate.compareTo(part.rate()) != 0) {
                return null;
            }
        }
        return rate;
    }
}
