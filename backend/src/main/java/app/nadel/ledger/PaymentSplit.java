package app.nadel.ledger;

import app.nadel.money.Money;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * How a payment was placed on the charges at a moment ({@link LedgerReadModel#paymentSplits}, feature 018 §2.12, the
 * income and expense report): its net allocations per charge type and what was on no charge then. Ids, codes and
 * {@code Money} only. {@code byChargeType} plus {@code unallocated} is always {@code amount}: the unallocated part is
 * worked out as the amount minus the allocations, so nothing is rounded.
 *
 * @param paymentId    the payment (kind {@code PAYMENT})
 * @param amount       the amount received (above zero)
 * @param byChargeType the net allocations (allocations minus releases) per charge type id; a type that nets to
 *                     {@code 0.00} is absent
 * @param unallocated  {@code amount} minus the net allocations: the advance («Аванси»)
 */
public record PaymentSplit(UUID paymentId, Money amount, Map<UUID, Money> byChargeType, Money unallocated) {

    public PaymentSplit {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(unallocated, "unallocated");
        byChargeType = Map.copyOf(byChargeType);
    }
}
