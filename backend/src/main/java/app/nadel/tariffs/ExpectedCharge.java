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

    /** A per-kWh or per-m3 version applies in the month: the amount comes from meter readings (features 010-011). */
    record ByReadings() implements ExpectedCharge {
    }
}
