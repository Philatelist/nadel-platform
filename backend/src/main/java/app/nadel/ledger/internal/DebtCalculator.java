package app.nadel.ledger.internal;

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
 * The debt of a plot from its charges (money-rules section 2, feature 011 §2.1). Pure: no Spring, no database.
 * <p>
 * The debt of a charge type is the sum of the plot's charges of that type, reversals counting negative and adjustments
 * (feature 012) with their sign; the total is the sum over the types. Money is added exactly (every amount already has
 * two decimals), nothing is rounded. Until payments exist (phase 3) nothing is paid, so the oldest unpaid accrual is the
 * oldest {@code accruedOn} among the charges that are neither a reversal nor an adjustment, were not reversed, and whose
 * effective amount (the charge plus its adjustments) is above zero. An adjustment carries the debt age of the charge it
 * adjusts, so it never counts on its own.
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
     * @param oldestAccrual the oldest day a debt counts from; null when no charge is left with an effective amount above
     *                      zero
     */
    record Debts(Map<UUID, Money> byType, Money total, @Nullable LocalDate oldestAccrual) {

        Debts {
            byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
        }
    }

    private DebtCalculator() {
    }

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
        return new Debts(byType, total, oldest);
    }

    /** The line of a stored charge. */
    static Line line(ChargeRecord charge) {
        return new Line(charge.getId(), charge.getChargeTypeId(), charge.getAmount(), charge.getAccruedOn(),
                charge.getReversesChargeId(), charge.getAdjustsChargeId());
    }
}
