package app.nadel.ledger.internal;

import app.nadel.meters.Quantity;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.DaySet;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * How the consumption of one common consumer over a date range is shared among plots (feature 011 §2.6, money-rules
 * 6.3). Pure: no Spring, no database, no clock; every row of the feature's worked examples is a unit test
 * ({@code CommonConsumerSplitCalculatorTests}). Money is not calculated here: the shares are exact kWh
 * ({@link Quantity}), priced later per plot.
 * <p>
 * The caller passes the plots that may share the consumer with the days each may share it on (the eligible days):
 * <ul>
 * <li>{@link CommonConsumerSplit#CONNECTED_PLOTS}: the days each plot was connected to that well. The consumption is
 * shared per plot-day: a plot gets {@code consumption x its days / the sum of the days of every plot} (60 kWh, plots
 * connected 30 + 30 + 15 days: 0.8 kWh per plot-day, 24 + 24 + 12 kWh);</li>
 * <li>{@link CommonConsumerSplit#EQUAL_PER_PLOT} and {@link CommonConsumerSplit#SERVICE_USERS}: every active plot, or
 * every plot marked as a user, with the days of the range. The consumption is shared equally: each plot gets
 * {@code consumption / the number of plots};</li>
 * <li>{@link CommonConsumerSplit#NOT_CHARGED}: nobody shares it.</li>
 * </ul>
 * A plot without a day in the range shares nothing. Nothing is shared when the consumption is zero or nobody may share
 * it. Each share keeps the plot's days in the range, so the price can spread its kWh over those days.
 */
final class CommonConsumerSplitCalculator {

    private CommonConsumerSplitCalculator() {
    }

    /**
     * The share of one plot.
     *
     * @param kwh   the exact kWh
     * @param days  the plot's eligible days within the range
     * @param count the number of those days
     */
    record Share(Quantity kwh, DaySet days, long count) {

        Share {
            Objects.requireNonNull(kwh, "kwh");
            Objects.requireNonNull(days, "days");
        }
    }

    /**
     * @param consumption the consumer's consumption over the range, not negative
     * @param split       how it is shared
     * @param from        the first day of the range
     * @param to          the last day of the range, not before {@code from}
     * @param eligible    the plots that may share it with their eligible days (any days; only those in the range
     *                    count), in the order the shares are wanted
     * @return the share of every plot with a day in the range, in the order of {@code eligible}; empty when nothing is
     *         shared
     */
    static Map<UUID, Share> split(Quantity consumption, CommonConsumerSplit split, LocalDate from, LocalDate to,
            Map<UUID, DaySet> eligible) {
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(split, "split");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A range does not end before it starts");
        }
        if (consumption.signum() < 0) {
            throw new IllegalArgumentException("The consumption of a common consumer is not negative");
        }
        if (split == CommonConsumerSplit.NOT_CHARGED || consumption.signum() == 0) {
            return Map.of();
        }
        DaySet range = DaySet.of(from, to);
        Map<UUID, DaySet> days = new LinkedHashMap<>();
        Map<UUID, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<UUID, DaySet> plot : eligible.entrySet()) {
            DaySet within = plot.getValue().intersect(range);
            long count = within.count(from, to);
            if (count > 0) {
                days.put(plot.getKey(), within);
                counts.put(plot.getKey(), count);
                total += count;
            }
        }
        if (days.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Share> shares = new LinkedHashMap<>();
        for (Map.Entry<UUID, DaySet> plot : days.entrySet()) {
            long count = counts.get(plot.getKey());
            Quantity kwh = split == CommonConsumerSplit.CONNECTED_PLOTS
                    ? consumption.times(count).dividedBy(total)
                    : consumption.dividedBy(days.size());
            shares.put(plot.getKey(), new Share(kwh, plot.getValue(), count));
        }
        return Collections.unmodifiableMap(shares);
    }
}
