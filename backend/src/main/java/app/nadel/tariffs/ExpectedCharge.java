package app.nadel.tariffs;

import app.nadel.money.Money;

/**
 * What a plot is expected to be charged by one tariff for one month: an amount, or the news that the amount depends on
 * meter readings and cannot be known from the tariff alone (money-rules section 4).
 */
public sealed interface ExpectedCharge {

    /**
     * The plot is charged this amount, rounded once to kopecks.
     *
     * @param money the amount; zero for a one-time fee in a month other than its due month
     */
    record Amount(Money money) implements ExpectedCharge {
        public Amount {
            java.util.Objects.requireNonNull(money, "money");
        }
    }

    /**
     * A per-kWh or per-m3 version applies in the month: the amount comes from meter readings (features 010-011).
     *
     * @param basis {@link Basis#PER_KWH} or {@link Basis#PER_M3}: the basis of the latest meter-based version that
     *              applies in the month, which names the resource whose meter the plot needs
     */
    record ByReadings(Basis basis) implements ExpectedCharge {
        public ByReadings {
            java.util.Objects.requireNonNull(basis, "basis");
            if (basis != Basis.PER_KWH && basis != Basis.PER_M3) {
                throw new IllegalArgumentException("Only a per-kWh or per-m3 basis is charged by readings");
            }
        }
    }
}
