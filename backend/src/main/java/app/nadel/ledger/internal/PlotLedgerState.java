package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.util.List;
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
}
