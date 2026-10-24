package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of the ledger as the recalculation planner reads it (feature 012): the facts of a {@link ChargeRecord} plus the
 * billing month it is booked in, detached from the persistence context. Pure data.
 *
 * @param billingMonth the month the row is booked in (its {@code billing_month_id})
 * @param calculation  the stored calculation (codes and numbers only); empty when none was stored
 */
record BookedCharge(UUID id, ChargeKind kind, ChargeSource source, UUID plotId, UUID chargeTypeId,
        YearMonth billingMonth, Money amount, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn,
        @Nullable String sourceKey, @Nullable UUID tariffId, @Nullable UUID meterId, @Nullable UUID runId,
        @Nullable UUID reversesChargeId, @Nullable UUID adjustsChargeId, Map<String, Object> calculation) {

    BookedCharge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(amount, "amount");
        calculation = calculation == null ? Map.of() : calculation;
    }

    /** The row {@code charge} booked in {@code billingMonth}. */
    static BookedCharge of(ChargeRecord charge, YearMonth billingMonth) {
        Map<String, Object> calculation = charge.getCalculation();
        return new BookedCharge(charge.getId(), charge.getKind(), charge.getSource(), charge.getPlotId(),
                charge.getChargeTypeId(), billingMonth, charge.getAmount(), charge.getServiceFrom(),
                charge.getServiceTo(), charge.getAccruedOn(), charge.getSourceKey(), charge.getTariffId(),
                charge.getMeterId(), charge.getRunId(), charge.getReversesChargeId(), charge.getAdjustsChargeId(),
                calculation == null ? Map.of() : calculation);
    }
}
