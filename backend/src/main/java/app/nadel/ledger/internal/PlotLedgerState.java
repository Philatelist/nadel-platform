package app.nadel.ledger.internal;

import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.money.Money;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The allocation view of the ledger of one plot (feature 015 §2.2-§2.4), computed by {@link OpenAmounts} from its
 * charges, allocations and payments: the input of {@link AllocationPlanner}, {@link ReleasePlanner},
 * {@link PaymentDigest} and {@link DebtCalculator}. Pure value; the lists keep the order of the rows given.
 *
 * @param charges     every {@code REGULAR} and {@code OPENING} charge, reversed ones included
 * @param allocations every allocation row (above zero) with what is left of it
 * @param payments    every payment (kind {@code PAYMENT}), reversed ones included
 */
record PlotLedgerState(List<ChargeState> charges, List<AllocationState> allocations, List<PaymentState> payments) {

    PlotLedgerState {
        charges = List.copyOf(charges);
        allocations = List.copyOf(allocations);
        payments = List.copyOf(payments);
    }

    /**
     * The prepayment of the plot: its payments (reversals negative) minus its allocations (releases negative), never
     * below {@code 0.00} (feature 015 §2.4).
     */
    Money prepayment() {
        Money prepayment = Money.ZERO;
        for (PaymentState payment : payments) {
            if (!payment.reversed()) {
                prepayment = prepayment.plus(payment.amount());
            }
            prepayment = prepayment.minus(payment.allocated());
        }
        return prepayment.signum() < 0 ? Money.ZERO : prepayment;
    }

    /** The state of the charge, when it is a regular or opening charge of the plot. */
    Optional<ChargeState> charge(UUID chargeId) {
        return charges.stream().filter(charge -> charge.id().equals(chargeId)).findFirst();
    }

    /**
     * The state as if the releases were written (a preview of a reallocation, or the check of new lines after the
     * releases of the same transaction): each release lowers what is allocated to its charge and of its payment, and
     * what is left of its allocation. Pure; the releases are of this plot ({@link ReleasePlanner}).
     */
    PlotLedgerState afterReleases(Collection<Release> releases) {
        if (releases.isEmpty()) {
            return this;
        }
        Map<UUID, Money> byCharge = new HashMap<>();
        Map<UUID, Money> byPayment = new HashMap<>();
        Map<UUID, Money> byAllocation = new HashMap<>();
        for (Release release : releases) {
            byCharge.merge(release.chargeId(), release.amount(), Money::plus);
            byPayment.merge(release.paymentId(), release.amount(), Money::plus);
            byAllocation.merge(release.allocationId(), release.amount(), Money::plus);
        }
        List<ChargeState> chargesAfter = charges.stream().map(charge -> new ChargeState(charge.id(), charge.plotId(),
                charge.kind(), charge.chargeTypeId(), charge.typePosition(), charge.accruedOn(), charge.serviceFrom(),
                charge.createdAt(), charge.effective(),
                charge.allocated().minus(byCharge.getOrDefault(charge.id(), Money.ZERO)), charge.reversed()))
                .toList();
        List<AllocationState> allocationsAfter = allocations.stream()
                .map(allocation -> new AllocationState(allocation.id(), allocation.paymentId(), allocation.chargeId(),
                        allocation.plotId(),
                        allocation.remaining().minus(byAllocation.getOrDefault(allocation.id(), Money.ZERO)),
                        allocation.createdAt()))
                .toList();
        List<PaymentState> paymentsAfter = payments.stream().map(payment -> new PaymentState(payment.id(),
                payment.plotId(), payment.amount(), payment.receivedOn(), payment.createdAt(), payment.reversed(),
                payment.allocated().minus(byPayment.getOrDefault(payment.id(), Money.ZERO)))).toList();
        return new PlotLedgerState(chargesAfter, allocationsAfter, paymentsAfter);
    }

    /** The state of the payment (kind {@code PAYMENT}), when it is a payment of the plot. */
    Optional<PaymentState> payment(UUID paymentId) {
        return payments.stream().filter(payment -> payment.id().equals(paymentId)).findFirst();
    }
}
