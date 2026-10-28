package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The proposed split of one payment over several plots of one payer (feature 015 §2.6, technical considerations 2.3).
 * Pure: no Spring, no database. The caller gives the payer's current plots (not common land, not archived) in the
 * natural plot order with their debts.
 * <ul>
 * <li>An amount of at least the total debt: each plot its debt, the excess to the first plot (its prepayment). This
 * also covers a total debt of {@code 0.00}: the whole amount goes to the first plot.</li>
 * <li>Otherwise in proportion to the debts with {@link Money#allocate} (kopecks rounded down, the leftover kopecks to
 * the largest remainders, ties to the plot listed first): the only rounding of the split, in {@code Money}.</li>
 * </ul>
 * Every plot gets a part, in the order given, possibly {@code 0.00}; the parts add up to the amount.
 */
final class PaymentSplitter {

    /** A plot of the payer and its debt (not negative). */
    record PlotDebt(UUID plotId, Money debt) {

        PlotDebt {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(debt, "debt");
            if (debt.signum() < 0) {
                throw new IllegalArgumentException("A debt is not negative");
            }
        }
    }

    /** The part of the payment proposed for a plot. */
    record Part(UUID plotId, Money amount) {
    }

    private PaymentSplitter() {
    }

    /**
     * @throws IllegalArgumentException when there is no plot or the amount is negative
     */
    static List<Part> split(Money amount, List<PlotDebt> plots) {
        if (plots.isEmpty()) {
            throw new IllegalArgumentException("A payment is split over at least one plot");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("A negative amount is never split");
        }
        List<Money> debts = plots.stream().map(PlotDebt::debt).toList();
        Money totalDebt = debts.stream().reduce(Money.ZERO, Money::plus);
        List<Money> amounts;
        if (amount.value().compareTo(totalDebt.value()) >= 0) {
            amounts = new ArrayList<>(debts);
            amounts.set(0, debts.get(0).plus(amount.minus(totalDebt)));
        } else {
            amounts = amount.allocate(debts);
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            parts.add(new Part(plots.get(i).plotId(), amounts.get(i)));
        }
        return List.copyOf(parts);
    }
}
