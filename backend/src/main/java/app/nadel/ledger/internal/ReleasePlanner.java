package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Which allocations are taken back, and how much of each (feature 015 §2.3, technical considerations 2.3). Pure: no
 * Spring, no database.
 * <ul>
 * <li>{@link #forCharges}: a reversed charge releases what is left of every live allocation on it
 * ({@code CHARGE_REVERSED}); a charge whose net allocations exceed its effective amount (a negative adjustment)
 * releases the excess from its latest allocations first ({@code created_at} descending, then the id descending, as
 * text), part of an allocation when that is enough ({@code CHARGE_ADJUSTED}).</li>
 * <li>{@link #forPayment}: a reversed or reallocated payment releases what is left of every live allocation of
 * it.</li>
 * </ul>
 * The released money becomes the plot's prepayment again (for a reversed payment the money is gone with it). Only
 * exact subtraction; nothing is rounded.
 */
final class ReleasePlanner {

    /** Latest allocation first. */
    static final Comparator<AllocationState> LATEST_FIRST = Comparator.comparing(AllocationState::createdAt)
            .thenComparing(allocation -> allocation.id().toString()).reversed();

    /**
     * Take {@code amount} (above zero, at most what is left of it) back from the allocation.
     *
     * @param allocationId the allocation released
     * @param paymentId    its payment
     * @param chargeId     its charge
     * @param plotId       its plot
     * @param amount       how much is released, above zero (the release row stores it negated)
     * @param cause        why
     */
    record Release(UUID allocationId, UUID paymentId, UUID chargeId, UUID plotId, Money amount, ReleaseCause cause) {

        Release {
            Objects.requireNonNull(allocationId, "allocationId");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(cause, "cause");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("A release takes back an amount above zero");
            }
        }
    }

    private ReleasePlanner() {
    }

    /**
     * The releases the charges of the plot need after a charge was reversed or lowered: none when every charge holds
     * at most its effective amount. Charges in the order of the state, allocations of a charge latest first.
     */
    static List<Release> forCharges(PlotLedgerState state) {
        List<Release> releases = new ArrayList<>();
        for (ChargeState charge : state.charges()) {
            Money excess = charge.allocated().minus(charge.effective());
            if (excess.signum() <= 0) {
                continue;
            }
            ReleaseCause cause = charge.reversed() ? ReleaseCause.CHARGE_REVERSED : ReleaseCause.CHARGE_ADJUSTED;
            List<AllocationState> live = state.allocations().stream()
                    .filter(allocation -> allocation.chargeId().equals(charge.id()) && allocation.live())
                    .sorted(LATEST_FIRST).toList();
            for (AllocationState allocation : live) {
                if (excess.signum() == 0) {
                    break;
                }
                Money part = AllocationPlanner.min(excess, allocation.remaining());
                releases.add(release(allocation, part, cause));
                excess = excess.minus(part);
            }
        }
        return List.copyOf(releases);
    }

    /**
     * The releases of every live allocation of the payment, latest first, in full.
     *
     * @param cause {@code PAYMENT_REVERSED} or {@code REALLOCATED}
     * @throws IllegalArgumentException for another cause
     */
    static List<Release> forPayment(UUID paymentId, Collection<AllocationState> allocations, ReleaseCause cause) {
        if (cause != ReleaseCause.PAYMENT_REVERSED && cause != ReleaseCause.REALLOCATED) {
            throw new IllegalArgumentException("A payment is released because it is reversed or reallocated");
        }
        return allocations.stream()
                .filter(allocation -> allocation.paymentId().equals(paymentId) && allocation.live())
                .sorted(LATEST_FIRST).map(allocation -> release(allocation, allocation.remaining(), cause)).toList();
    }

    private static Release release(AllocationState allocation, Money amount, ReleaseCause cause) {
        return new Release(allocation.id(), allocation.paymentId(), allocation.chargeId(), allocation.plotId(), amount,
                cause);
    }
}
