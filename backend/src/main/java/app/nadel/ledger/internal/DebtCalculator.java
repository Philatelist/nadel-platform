package app.nadel.ledger.internal;

import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The debt of a plot (money-rules sections 2 and 9, feature 011 §2.1, feature 015 §2.5). Pure: no Spring, no database.
 * <p>
 * {@link #of(Collection, Collection, Collection)} is the rule after payments: the debt of a charge type is the sum of
 * the plot's charges of that type (reversals negative, adjustments of feature 012 with their sign) minus the net
 * allocations to them (allocations minus releases); the total is the sum over the types; the prepayment is the
 * payments (reversals negative) minus the net allocations, never below zero; the oldest unpaid accrual is the oldest
 * {@code accruedOn} among the {@code REGULAR} and {@code OPENING} charges that were not reversed and still have an open
 * amount above zero ({@link OpenAmounts}). An adjustment carries the debt age of the charge it adjusts, so it never
 * counts on its own.
 * <p>
 * {@link #of(Collection)} is the same rule for a plot without payments (the charges only): nothing is allocated, so
 * the open amount of a charge is its effective amount (the charge plus its adjustments) and the prepayment is zero.
 * Money is added exactly (every amount already has two decimals), nothing is rounded.
 */
final class DebtCalculator {

    /**
     * One charge of the plot.
     *
     * @param id           the charge
     * @param chargeTypeId its charge type
     * @param amount       its amount, negative for a reversal, either sign for an adjustment
     * @param accruedOn    the day its debt counts from
     * @param reverses     the charge a reversal cancels; null for every other charge
     * @param adjusts      the charge an adjustment corrects; null for every other charge
     */
    record Line(UUID id, UUID chargeTypeId, Money amount, LocalDate accruedOn, @Nullable UUID reverses,
            @Nullable UUID adjusts) {

        Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(accruedOn, "accruedOn");
        }
    }

    /**
     * @param byType        the debt per charge type, for every type the plot has a charge of (also when it is zero),
     *                      in the order the types first appear among the lines
     * @param total         the sum of the debts
     * @param oldestAccrual the oldest day a debt counts from; null when no charge is left with an open amount above
     *                      zero
     * @param prepayment    the money paid for the plot that is on no charge, never below zero
     */
    record Debts(Map<UUID, Money> byType, Money total, @Nullable LocalDate oldestAccrual, Money prepayment) {

        Debts {
            byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
            Objects.requireNonNull(total, "total");
            Objects.requireNonNull(prepayment, "prepayment");
        }
    }

    private DebtCalculator() {
    }

    /** The debts of a plot without payments, from its charges only; the prepayment is zero. */
    static Debts of(Collection<Line> lines) {
        Set<UUID> reversed = new HashSet<>();
        Map<UUID, Money> adjustments = new HashMap<>();
        for (Line line : lines) {
            if (line.reverses() != null) {
                reversed.add(line.reverses());
            }
            if (line.adjusts() != null) {
                adjustments.merge(line.adjusts(), line.amount(), Money::plus);
            }
        }
        Map<UUID, Money> byType = new LinkedHashMap<>();
        Money total = Money.ZERO;
        LocalDate oldest = null;
        for (Line line : lines) {
            byType.merge(line.chargeTypeId(), line.amount(), Money::plus);
            total = total.plus(line.amount());
            if (line.reverses() != null || line.adjusts() != null || reversed.contains(line.id())) {
                continue;
            }
            Money effective = line.amount().plus(adjustments.getOrDefault(line.id(), Money.ZERO));
            if (effective.signum() > 0 && (oldest == null || line.accruedOn().isBefore(oldest))) {
                oldest = line.accruedOn();
            }
        }
        return new Debts(byType, total, oldest, Money.ZERO);
    }

    /**
     * The debts of a plot after payments, from all its ledger rows (the charges, allocations and payments of the one
     * plot), in the order the types first appear among the charges.
     *
     * @throws IllegalArgumentException when an allocation names a charge that is not among the charges
     */
    static Debts of(Collection<ChargeEntry> charges, Collection<AllocationEntry> allocations,
            Collection<PaymentEntry> payments) {
        Map<UUID, UUID> typeOfCharge = new HashMap<>();
        Map<UUID, Money> byType = new LinkedHashMap<>();
        for (ChargeEntry charge : charges) {
            typeOfCharge.put(charge.id(), charge.chargeTypeId());
            byType.merge(charge.chargeTypeId(), charge.amount(), Money::plus);
        }
        for (AllocationEntry allocation : allocations) {
            UUID type = typeOfCharge.get(allocation.chargeId());
            if (type == null) {
                throw new IllegalArgumentException("An allocation names a charge of the plot");
            }
            byType.merge(type, allocation.amount().negate(), Money::plus);
        }
        Money total = byType.values().stream().reduce(Money.ZERO, Money::plus);
        PlotLedgerState state = OpenAmounts.of(charges, allocations, payments, Map.of());
        LocalDate oldest = null;
        for (ChargeState charge : state.charges()) {
            if (charge.allocatable() && charge.open().signum() > 0
                    && (oldest == null || charge.accruedOn().isBefore(oldest))) {
                oldest = charge.accruedOn();
            }
        }
        return new Debts(byType, total, oldest, state.prepayment());
    }

    /** The line of a stored charge. */
    static Line line(ChargeRecord charge) {
        return new Line(charge.getId(), charge.getChargeTypeId(), charge.getAmount(), charge.getAccruedOn(),
                charge.getReversesChargeId(), charge.getAdjustsChargeId());
    }
}
