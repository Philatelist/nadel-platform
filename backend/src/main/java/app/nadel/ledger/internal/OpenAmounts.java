package app.nadel.ledger.internal;

import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The open amounts of a plot's charges and what is left of its payments and allocations (feature 015 §2.2,
 * technical considerations 2.3), from the ledger rows as stored. Pure: no Spring, no database.
 * <ul>
 * <li>Only {@code REGULAR} and {@code OPENING} charges receive allocations. Their <b>effective amount</b> is the charge
 * plus its adjustments, {@code 0.00} once reversed; the <b>open amount</b> is the effective amount minus the net
 * allocations to the charge (allocations minus releases).</li>
 * <li>What is <b>left of an allocation</b> is its amount plus the releases that point at it.</li>
 * <li>A payment is reversed when a {@code PAYMENT_REVERSAL} points at it; its allocated amount is the net allocations
 * of it.</li>
 * </ul>
 * Money is added exactly (every amount has two decimals), nothing is rounded.
 */
final class OpenAmounts {

    /** The position of a charge type the order does not know: after every known one. */
    static final int UNKNOWN_POSITION = Integer.MAX_VALUE;

    /**
     * One row of table {@code charge} as far as allocation is concerned.
     *
     * @param reverses the charge a reversal cancels; null for every other kind
     * @param adjusts  the charge an adjustment corrects; null for every other kind
     */
    record ChargeEntry(UUID id, UUID plotId, ChargeKind kind, UUID chargeTypeId, Money amount, LocalDate accruedOn,
            @Nullable LocalDate serviceFrom, Instant createdAt, @Nullable UUID reverses, @Nullable UUID adjusts) {

        ChargeEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(accruedOn, "accruedOn");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * One row of table {@code allocation}: an allocation (amount above zero) or a release (below zero, pointing at the
     * allocation it takes back).
     */
    record AllocationEntry(UUID id, UUID paymentId, UUID chargeId, UUID plotId, Money amount, @Nullable UUID releases,
            Instant createdAt) {

        AllocationEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * One row of table {@code payment}: a payment (amount above zero) or a reversal (below zero, pointing at the
     * payment it cancels).
     */
    record PaymentEntry(UUID id, UUID plotId, PaymentKind kind, Money amount, LocalDate receivedOn, Instant createdAt,
            @Nullable UUID reverses) {

        PaymentEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(receivedOn, "receivedOn");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private OpenAmounts() {
    }

    /**
     * The allocation view of the rows of one plot. {@code typePositions} is the cooperative's order of charge types
     * ({@link #positions}); a type it does not contain comes last ({@link #UNKNOWN_POSITION}). The lists of the result
     * keep the order of the rows given.
     */
    static PlotLedgerState of(Collection<ChargeEntry> charges, Collection<AllocationEntry> allocations,
            Collection<PaymentEntry> payments, Map<UUID, Integer> typePositions) {
        Set<UUID> reversedCharges = new HashSet<>();
        Map<UUID, Money> adjustments = new HashMap<>();
        for (ChargeEntry charge : charges) {
            if (charge.reverses() != null) {
                reversedCharges.add(charge.reverses());
            }
            if (charge.adjusts() != null) {
                adjustments.merge(charge.adjusts(), charge.amount(), Money::plus);
            }
        }
        Map<UUID, Money> toCharge = new HashMap<>();
        Map<UUID, Money> ofPayment = new HashMap<>();
        Map<UUID, Money> released = new HashMap<>();
        for (AllocationEntry allocation : allocations) {
            toCharge.merge(allocation.chargeId(), allocation.amount(), Money::plus);
            ofPayment.merge(allocation.paymentId(), allocation.amount(), Money::plus);
            if (allocation.releases() != null) {
                released.merge(allocation.releases(), allocation.amount(), Money::plus);
            }
        }
        Set<UUID> reversedPayments = new HashSet<>();
        for (PaymentEntry payment : payments) {
            if (payment.reverses() != null) {
                reversedPayments.add(payment.reverses());
            }
        }

        List<ChargeState> chargeStates = new ArrayList<>();
        for (ChargeEntry charge : charges) {
            if (charge.kind() != ChargeKind.REGULAR && charge.kind() != ChargeKind.OPENING) {
                continue;
            }
            boolean reversed = reversedCharges.contains(charge.id());
            Money effective = reversed ? Money.ZERO
                    : charge.amount().plus(adjustments.getOrDefault(charge.id(), Money.ZERO));
            chargeStates.add(new ChargeState(charge.id(), charge.plotId(), charge.kind(), charge.chargeTypeId(),
                    typePositions.getOrDefault(charge.chargeTypeId(), UNKNOWN_POSITION), charge.accruedOn(),
                    charge.serviceFrom(), charge.createdAt(), effective,
                    toCharge.getOrDefault(charge.id(), Money.ZERO), reversed));
        }
        List<AllocationState> allocationStates = new ArrayList<>();
        for (AllocationEntry allocation : allocations) {
            if (allocation.amount().signum() > 0) {
                allocationStates.add(new AllocationState(allocation.id(), allocation.paymentId(),
                        allocation.chargeId(), allocation.plotId(),
                        allocation.amount().plus(released.getOrDefault(allocation.id(), Money.ZERO)),
                        allocation.createdAt()));
            }
        }
        List<PaymentState> paymentStates = new ArrayList<>();
        for (PaymentEntry payment : payments) {
            if (payment.kind() == PaymentKind.PAYMENT) {
                paymentStates.add(new PaymentState(payment.id(), payment.plotId(), payment.amount(),
                        payment.receivedOn(), payment.createdAt(), reversedPayments.contains(payment.id()),
                        ofPayment.getOrDefault(payment.id(), Money.ZERO)));
            }
        }
        return new PlotLedgerState(chargeStates, allocationStates, paymentStates);
    }

    /**
     * The cooperative's order of charge types as positions (0 first): the index of each type in
     * {@code ChargeTypeCatalog.all()}, which lists them in their order (position, then id).
     */
    static Map<UUID, Integer> positions(List<ChargeTypeFacts> typesInOrder) {
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < typesInOrder.size(); i++) {
            positions.put(typesInOrder.get(i).id(), i);
        }
        return positions;
    }

    /** The entry of a stored charge. */
    static ChargeEntry entry(ChargeRecord charge) {
        return new ChargeEntry(charge.getId(), charge.getPlotId(), charge.getKind(), charge.getChargeTypeId(),
                charge.getAmount(), charge.getAccruedOn(), charge.getServiceFrom(), charge.getCreatedAt(),
                charge.getReversesChargeId(), charge.getAdjustsChargeId());
    }

    /** The entry of a stored allocation or release. */
    static AllocationEntry entry(AllocationRecord allocation) {
        return new AllocationEntry(allocation.getId(), allocation.getPaymentId(), allocation.getChargeId(),
                allocation.getPlotId(), allocation.getAmount(), allocation.getReleasesAllocationId(),
                allocation.getCreatedAt());
    }

    /** The entry of a stored payment or payment reversal. */
    static PaymentEntry entry(PaymentRecord payment) {
        return new PaymentEntry(payment.getId(), payment.getPlotId(), payment.getKind(), payment.getAmount(),
                payment.getReceivedOn(), payment.getCreatedAt(), payment.getReversesPaymentId());
    }
}
