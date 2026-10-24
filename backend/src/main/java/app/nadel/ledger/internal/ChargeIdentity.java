package app.nadel.ledger.internal;

import app.nadel.ledger.internal.ReadingChargePlanning.PlannedReadingCharge;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a charge charges, independent of how it was booked (feature 012, technical considerations 2.2): a booked charge
 * and an expected one "correspond" when they have the same identity. Pure: no Spring, no database.
 * <ul>
 * <li>a tariff charge (month run, one-time fee): {@code T:{tariff}:{plot}:{YYYY-MM}}, from the base source key (any
 * {@code #…} suffix of a charge booked again by a recalculation stripped);</li>
 * <li>an interval charge (consumption or fixed-percentage losses of a plot meter, electricity or water):
 * {@code I:{source}:{meter}:{fromDate}:{toDate}}, the dates of the interval's two points (the previous point and the
 * reading that closes it). Not the source key: a corrected reading is a new reading with a new key, but the same
 * interval while its date stays;</li>
 * <li>a line of a proportional losses run {@code R:{run}:{plot}}, of a common consumer run
 * {@code R:{run}:{consumer}:{plot}}.</li>
 * </ul>
 * A booked charge has an identity only when it is {@code REGULAR}: reversals and adjustments belong to the chain of the
 * charge they point at, and opening balances are never recalculated.
 *
 * @param value the identity as text (sorts and hashes the same everywhere, used by the digest)
 * @param kind  what it charges
 * @param runId the run of a run line; null otherwise
 */
record ChargeIdentity(String value, Kind kind, @Nullable UUID runId) implements Comparable<ChargeIdentity> {

    /** What a charge of the identity charges. */
    enum Kind {
        TARIFF, INTERVAL, RUN
    }

    ChargeIdentity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.RUN) != (runId != null)) {
            throw new IllegalArgumentException("A run line, and only a run line, has a run");
        }
    }

    /** One tariff for one plot and one month. */
    static ChargeIdentity tariff(UUID tariffId, UUID plotId, YearMonth month) {
        return new ChargeIdentity("T:" + tariffId + ":" + plotId + ":" + month, Kind.TARIFF, null);
    }

    /**
     * One interval of a plot meter.
     *
     * @param source {@code CONSUMPTION} or {@code LOSSES}
     * @param from   the date of the previous point
     * @param to     the date of the reading that closes the interval
     */
    static ChargeIdentity interval(ChargeSource source, UUID meterId, LocalDate from, LocalDate to) {
        if (source != ChargeSource.CONSUMPTION && source != ChargeSource.LOSSES) {
            throw new IllegalArgumentException("An interval charge is a consumption or a losses charge");
        }
        return new ChargeIdentity("I:" + source + ":" + meterId + ":" + from + ":" + to, Kind.INTERVAL, null);
    }

    /** A plot's line of a proportional losses run. */
    static ChargeIdentity lossesRun(UUID runId, UUID plotId) {
        return new ChargeIdentity("R:" + runId + ":" + plotId, Kind.RUN, runId);
    }

    /** A plot's line of one consumer of a common consumer run. */
    static ChargeIdentity commonConsumerRun(UUID runId, UUID consumerKey, UUID plotId) {
        return new ChargeIdentity("R:" + runId + ":" + consumerKey + ":" + plotId, Kind.RUN, runId);
    }

    /** The charge the month run would book. */
    static ChargeIdentity of(TariffMonthPlanning.PlannedCharge charge) {
        return tariff(charge.tariff().id(), charge.plotId(), charge.month());
    }

    /** A charge of a meter interval (consumption or losses) as the 011 planner would book it. */
    static ChargeIdentity of(PlannedReadingCharge charge) {
        return interval(charge.source(), charge.interval().meterId(), charge.interval().from().on(),
                charge.interval().to().on());
    }

    /**
     * The identity of a booked charge; empty for a reversal, an adjustment or an opening balance (they are not charges
     * of their own: a reversal and an adjustment join the chain of the charge they point at).
     *
     * @throws IllegalArgumentException when a regular charge lacks what its identity is made of (a tariff charge
     *                                  without its source key, an interval charge without the two readings of its
     *                                  calculation, a run charge without its run)
     */
    static Optional<ChargeIdentity> of(BookedCharge charge) {
        if (charge.kind() != ChargeKind.REGULAR) {
            return Optional.empty();
        }
        return Optional.of(switch (charge.source()) {
            case TARIFF -> {
                String[] key = keyParts(charge, "TARIFF", 4);
                yield tariff(UUID.fromString(key[1]), UUID.fromString(key[2]), YearMonth.parse(key[3]));
            }
            case CONSUMPTION -> intervalOf(charge);
            case LOSSES -> charge.runId() == null ? intervalOf(charge)
                    : lossesRun(charge.runId(), charge.plotId());
            case COMMON_CONSUMER -> {
                String[] key = keyParts(charge, "COMMON_CONSUMER_RUN", 4);
                yield commonConsumerRun(UUID.fromString(key[1]), UUID.fromString(key[2]), UUID.fromString(key[3]));
            }
            case OPENING, MANUAL_REVERSAL, RECALCULATION -> throw new IllegalArgumentException(
                    "A regular charge has a charging source");
        });
    }

    /** A source key without the revision suffix of a charge booked again ({@code base#r{recalculation}}). */
    static String baseKey(String sourceKey) {
        int hash = sourceKey.indexOf('#');
        return hash < 0 ? sourceKey : sourceKey.substring(0, hash);
    }

    private static String[] keyParts(BookedCharge charge, String prefix, int parts) {
        String key = charge.sourceKey();
        if (key == null) {
            throw new IllegalArgumentException("A " + charge.source() + " charge has a source key");
        }
        String[] split = baseKey(key).split(":");
        if (split.length != parts || !prefix.equals(split[0])) {
            throw new IllegalArgumentException("Not a " + prefix + " source key");
        }
        return split;
    }

    private static ChargeIdentity intervalOf(BookedCharge charge) {
        UUID meterId = charge.meterId();
        Object readings = charge.calculation().get("readings");
        if (meterId == null || !(readings instanceof List<?> points) || points.size() != 2) {
            throw new IllegalArgumentException("An interval charge has a meter and the two readings of its interval");
        }
        return interval(charge.source(), meterId, takenOn(points.get(0)), takenOn(points.get(1)));
    }

    private static LocalDate takenOn(Object reading) {
        if (reading instanceof Map<?, ?> map && map.get("takenOn") instanceof String date) {
            return LocalDate.parse(date);
        }
        throw new IllegalArgumentException("A reading of a calculation has its date");
    }

    @Override
    public int compareTo(ChargeIdentity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
