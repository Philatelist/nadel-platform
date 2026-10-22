package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
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
 * The debt of a charge type is the sum of the plot's charges of that type, reversals counting negative; the total is
 * the sum over the types. Money is added exactly (every amount already has two decimals), nothing is rounded. Until
 * payments exist (phase 3) nothing is paid, so the oldest unpaid accrual is the oldest {@code accruedOn} among the
 * positive charges that were not reversed.
 */
final class DebtCalculator {

    /**
     * One charge of the plot.
     *
     * @param id           the charge
     * @param chargeTypeId its charge type
     * @param amount       its amount, negative for a reversal
     * @param accruedOn    the day its debt counts from
     * @param reverses     the charge a reversal cancels; null for every other charge
     */
    record Line(UUID id, UUID chargeTypeId, Money amount, LocalDate accruedOn, @Nullable UUID reverses) {

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
     * @param oldestAccrual the oldest day a debt counts from; null when no positive charge is left unreversed
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
        for (Line line : lines) {
            if (line.reverses() != null) {
                reversed.add(line.reverses());
            }
        }
        Map<UUID, Money> byType = new LinkedHashMap<>();
        Money total = Money.ZERO;
        LocalDate oldest = null;
        for (Line line : lines) {
            byType.merge(line.chargeTypeId(), line.amount(), Money::plus);
            total = total.plus(line.amount());
            boolean open = line.amount().signum() > 0 && !reversed.contains(line.id());
            if (open && (oldest == null || line.accruedOn().isBefore(oldest))) {
                oldest = line.accruedOn();
            }
        }
        return new Debts(byType, total, oldest);
    }
}
