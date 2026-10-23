package app.nadel.ledger.internal;

import app.nadel.ledger.internal.CommonConsumerSplitCalculator.Share;
import app.nadel.ledger.internal.LossesCalculator.Measured;
import app.nadel.ledger.internal.LossesCalculator.UnknownValue;
import app.nadel.ledger.internal.LossesCalculator.ValueUse;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargeCalculator.NotCovered;
import app.nadel.ledger.internal.ReadingChargeCalculator.Priced;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.ledger.internal.ReadingChargeCalculator.Result;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.None;
import app.nadel.tariffs.TariffResolver.Single;
import app.nadel.tariffs.VersionTerms;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a common consumer run books (feature 011 §2.6, money-rules 6.3), from the consumption of every consumer and the
 * plots that may share it. Pure: no Spring, no database, no clock.
 * <p>
 * Per consumer, in this order: its split is {@code NOT_CHARGED} -> {@link Outcome#NOT_CHARGED} (listed with its
 * consumption, nothing charged); its consumption is unknown -> {@link Outcome#UNKNOWN}; it used something but nobody may
 * share it -> {@link Outcome#NO_PLOTS}; otherwise {@link Outcome#CHARGED}: the consumption is shared by
 * {@link CommonConsumerSplitCalculator}. Only a {@code CHARGED} consumer that no booked run charged for a day of the
 * range ({@link Overlap}) charges, and only while the cooperative has a {@code COMMON_CONSUMERS} tariff for the range
 * ({@link Plan#noTariff}: the type is inactive or archived, or none of its tariffs has a {@code PER_KWH} version valid on
 * a day of the range; then the plots are listed without amounts and nothing is booked).
 * <p>
 * Each plot's kWh of a consumer are priced with the plot's {@code COMMON_CONSUMERS} tariff as {@link TariffResolver}
 * picks it, spread evenly over the plot's days in the split (its connected days for {@code CONNECTED_PLOTS}, else the
 * days of the range) and split by days when the rate changes ({@link ReadingChargeCalculator}: added exactly, rounded
 * once): one charge per plot per consumer. A share that cannot be priced gets no charge: no per-kWh tariff for every one
 * of its days ({@code NO_TARIFF_FOR_ALL_DAYS}), two tariffs that would both charge it ({@code CONFLICTING_TARIFFS}),
 * outside the scope or excluded; an amount of {@code 0.00} charges nothing either.
 */
final class CommonConsumerPlanning {

    static final String COMMON_CONSUMERS = "COMMON_CONSUMERS";

    private CommonConsumerPlanning() {
    }

    /** What a run does with a consumer (the stored outcome; {@code ALREADY_CHARGED} is an {@link Overlap}). */
    enum Outcome {
        CHARGED, NOT_CHARGED, NO_PLOTS, UNKNOWN
    }

    /** A booked run that charged the consumer for a day of the range. */
    record Overlap(UUID runId, LocalDate from, LocalDate to) {
    }

    /**
     * A consumer as the plan needs it.
     *
     * @param chain    its chain of meters (the key is the consumer)
     * @param split    how it is shared
     * @param measured its consumption over the range and the values read
     * @param eligible the plots that may share it with their days, in the natural order of the plot numbers
     * @param overlap  a booked run that charged it for a day of the range; null when none
     */
    record ConsumerInput(NetworkChain chain, CommonConsumerSplit split, Measured measured, Map<UUID, DaySet> eligible,
            @Nullable Overlap overlap) {

        ConsumerInput {
            Objects.requireNonNull(chain, "chain");
            Objects.requireNonNull(split, "split");
            Objects.requireNonNull(measured, "measured");
            eligible = new LinkedHashMap<>(eligible);
        }
    }

    /**
     * The share of one plot in one consumer.
     *
     * @param amount the share priced; null when it is not charged
     * @param reason why it is not charged, when the board can fix it
     */
    record PlannedShare(UUID plotId, Quantity kwh, long days, @Nullable Money amount, @Nullable Reason reason) {
    }

    /** A consumer of the run with its shares and their amount. */
    record PlannedLine(NetworkChain chain, CommonConsumerSplit split, Outcome outcome, @Nullable Quantity consumption,
            boolean calculated, @Nullable UnknownValue unknown, @Nullable Overlap overlap, List<PlannedShare> shares,
            long plotDays, Money amount, Set<ValueUse> uses) {

        PlannedLine {
            shares = List.copyOf(shares);
            uses = Set.copyOf(uses);
        }

        /** Whether the consumer is charged in this run (also when its shares come to nothing). */
        boolean charges() {
            return outcome == Outcome.CHARGED && overlap == null;
        }
    }

    /** The charge of one plot for one consumer: its kWh priced once. */
    record PlannedCharge(UUID consumerKey, @Nullable String consumerName, CommonConsumerSplit split, UUID plotId,
            Quantity kwh, Money amount, TariffEntry tariff, boolean calculated, List<PricedPart> parts) {

        PlannedCharge {
            parts = List.copyOf(parts);
        }

        /** The version id of each part, in the order of the parts. */
        List<UUID> versionIds() {
            return parts.stream().map(p -> tariff.versionStartingOn(p.terms().validFrom()).orElseThrow()).toList();
        }

        /**
         * The calculation stored with the charge: codes and numbers only (the {@code ChargeCalculation} of the
         * contract) and the consumer's name as the run showed it (user content). One part per tariff version with its
         * kWh (three decimals for display), rate, days and first and last day; the run, the consumer and the split.
         */
        Map<String, Object> calculation(UUID runId) {
            List<UUID> ids = versionIds();
            List<Map<String, Object>> lines = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                PricedPart part = parts.get(i);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("tariffVersionId", ids.get(i).toString());
                line.put("basis", part.terms().basis().name());
                line.put("frequency", part.terms().frequency().name());
                line.put("quantity", part.quantity().toMeterValue().toString());
                line.put("unit", "KWH");
                line.put("rate", part.terms().rate().toString());
                line.put("days", part.days());
                line.put("from", part.from().toString());
                line.put("to", part.to().toString());
                line.put("calculated", calculated);
                lines.add(line);
            }
            Map<String, Object> calculation = new LinkedHashMap<>();
            calculation.put("amount", amount.toString());
            calculation.put("parts", lines);
            calculation.put("readings", List.of());
            calculation.put("runId", runId.toString());
            calculation.put("consumerMeterId", consumerKey.toString());
            if (consumerName != null) {
                calculation.put("consumerName", consumerName);
            }
            calculation.put("split", split.name());
            return calculation;
        }
    }

    /** What the run books: the selected consumers in their order and one charge per plot per consumer. */
    record Plan(LocalDate from, LocalDate to, boolean noTariff, List<PlannedLine> lines, List<PlannedCharge> charges) {

        Plan {
            lines = List.copyOf(lines);
            charges = List.copyOf(charges);
        }

        Money total() {
            return charges.stream().map(PlannedCharge::amount).reduce(Money.ZERO, Money::plus);
        }

        /** The consumers that a booked run charged already. */
        List<PlannedLine> overlapping() {
            return lines.stream().filter(line -> line.overlap() != null).toList();
        }

        /** The number of plots charged. */
        int plots() {
            return (int) charges.stream().map(PlannedCharge::plotId).distinct().count();
        }

        /**
         * A SHA-256 over what a booking writes: whether there is a tariff, the outcome, split and consumption of every
         * consumer that is not charged already, and every charge (consumer, plot, exact kWh, amount). A preview of
         * every consumer therefore matches a booking of the consumers that are not charged already.
         */
        String digest() {
            List<String> parts = new ArrayList<>();
            parts.add("T|" + noTariff);
            for (PlannedLine line : lines) {
                if (line.overlap() == null) {
                    parts.add("L|" + line.chain().key() + "|" + line.outcome() + "|" + line.split() + "|"
                            + line.consumption());
                }
            }
            for (PlannedCharge charge : charges) {
                parts.add("C|" + charge.consumerKey() + "|" + charge.plotId() + "|" + charge.kwh() + "|"
                        + charge.amount());
            }
            String text = parts.stream().sorted().collect(Collectors.joining("\n"));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is always available", e);
            }
        }
    }

    /** The price of a plot's share: the tariff and the amount, or why it is not charged. */
    private record SharePrice(@Nullable TariffEntry tariff, @Nullable Priced priced, @Nullable Reason reason) {

        boolean charges() {
            return priced != null && priced.amount().signum() > 0;
        }
    }

    /**
     * @param from      the first day of the range
     * @param to        the last day of the range
     * @param consumers the selected consumers in the order of the run
     * @param snapshot  the charge types and tariffs
     * @param plots     the facts of the plots that may share a consumer; a plot missing here is not charged
     */
    static Plan plan(LocalDate from, LocalDate to, List<ConsumerInput> consumers, Snapshot snapshot,
            Map<UUID, TariffResolver.PlotFacts> plots) {
        UUID type = commonConsumersType(snapshot);
        boolean noTariff = noTariff(snapshot, type, from, to);
        List<PlannedLine> lines = new ArrayList<>();
        List<PlannedCharge> charges = new ArrayList<>();
        for (ConsumerInput consumer : consumers) {
            Measured measured = consumer.measured();
            Quantity consumption = measured.known();
            Outcome outcome;
            Map<UUID, Share> split = Map.of();
            if (consumer.split() == CommonConsumerSplit.NOT_CHARGED) {
                outcome = Outcome.NOT_CHARGED;
            } else if (consumption == null) {
                outcome = Outcome.UNKNOWN;
            } else {
                split = CommonConsumerSplitCalculator.split(consumption, consumer.split(), from, to,
                        consumer.eligible());
                outcome = consumption.signum() > 0 && split.isEmpty() ? Outcome.NO_PLOTS : Outcome.CHARGED;
            }
            boolean charging = outcome == Outcome.CHARGED && consumer.overlap() == null;
            List<PlannedShare> shares = new ArrayList<>();
            Money amount = Money.ZERO;
            long plotDays = 0;
            if (charging) {
                for (Map.Entry<UUID, Share> entry : split.entrySet()) {
                    Share share = entry.getValue();
                    plotDays += share.count();
                    if (noTariff) {
                        shares.add(new PlannedShare(entry.getKey(), share.kwh(), share.count(), null, null));
                        continue;
                    }
                    SharePrice price = price(snapshot, Objects.requireNonNull(type), plots.get(entry.getKey()), share,
                            from, to);
                    Money priced = null;
                    if (price.charges()) {
                        Priced result = Objects.requireNonNull(price.priced());
                        priced = result.amount();
                        amount = amount.plus(priced);
                        charges.add(new PlannedCharge(consumer.chain().key(), consumer.chain().name(),
                                consumer.split(), entry.getKey(), share.kwh(), priced,
                                Objects.requireNonNull(price.tariff()), measured.calculated(), result.parts()));
                    }
                    shares.add(new PlannedShare(entry.getKey(), share.kwh(), share.count(), priced, price.reason()));
                }
            }
            lines.add(new PlannedLine(consumer.chain(), consumer.split(), outcome, consumption, measured.calculated(),
                    outcome == Outcome.UNKNOWN ? measured.unknown() : null, consumer.overlap(), shares, plotDays,
                    amount, charging ? measured.uses() : Set.of()));
        }
        return new Plan(from, to, noTariff, lines, charges);
    }

    /** The {@code COMMON_CONSUMERS} charge type of the cooperative; null when it has none. */
    static @Nullable UUID commonConsumersType(Snapshot snapshot) {
        return snapshot.chargeTypes().stream().filter(type -> COMMON_CONSUMERS.equals(type.code()))
                .map(ChargeTypeEntry::id).findFirst().orElse(null);
    }

    /**
     * Whether the run has no tariff to price with: no {@code COMMON_CONSUMERS} type, the type is inactive or archived,
     * or none of its tariffs has a {@code PER_KWH} version valid on a day of the range.
     */
    static boolean noTariff(Snapshot snapshot, @Nullable UUID type, LocalDate from, LocalDate to) {
        if (type == null || !snapshot.chargeType(type).map(ChargeTypeEntry::charges).orElse(false)) {
            return true;
        }
        for (TariffEntry tariff : snapshot.tariffsOf(type)) {
            List<VersionTerms> versions = tariff.terms();
            for (int i = 0; i < versions.size(); i++) {
                VersionTerms version = versions.get(i);
                LocalDate validTo = i + 1 < versions.size() ? versions.get(i + 1).validFrom().minusDays(1) : null;
                if (version.basis() == Basis.PER_KWH && !version.validFrom().isAfter(to)
                        && (validTo == null || !validTo.isBefore(from))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The price of a plot's share, as {@link LossesPlanning} prices a plot's losses. */
    private static SharePrice price(Snapshot snapshot, UUID type, TariffResolver.@Nullable PlotFacts plot, Share share,
            LocalDate from, LocalDate to) {
        if (plot == null) {
            return new SharePrice(null, null, Reason.OUTSIDE_SCOPE);
        }
        return switch (TariffResolver.resolve(snapshot, type, plot, from, to)) {
            case Single single -> priced(single.tariff(), share, from, to);
            case Conflict conflict -> {
                List<TariffEntry> charging = conflict.tariffs().stream().filter(t -> wouldCharge(t, share, from, to))
                        .toList();
                if (charging.size() == 1) {
                    yield priced(charging.getFirst(), share, from, to);
                }
                yield new SharePrice(null, null, charging.isEmpty() ? null : Reason.CONFLICTING_TARIFFS);
            }
            case None none -> new SharePrice(null, null, switch (none.reason()) {
                case TYPE_INACTIVE -> null;
                case NO_TARIFF -> Reason.NO_TARIFF_FOR_ALL_DAYS;
                case EXCLUDED -> Reason.EXCLUDED;
                case OUTSIDE_SCOPE -> Reason.OUTSIDE_SCOPE;
            });
        };
    }

    private static SharePrice priced(TariffEntry tariff, Share share, LocalDate from, LocalDate to) {
        if (price(tariff, share, from, to) instanceof Priced priced) {
            return new SharePrice(tariff, priced, null);
        }
        // not covered on every day of the share, or no per-kWh version on any of them
        return new SharePrice(tariff, null, Reason.NO_TARIFF_FOR_ALL_DAYS);
    }

    private static boolean wouldCharge(TariffEntry tariff, Share share, LocalDate from, LocalDate to) {
        Result result = price(tariff, share, from, to);
        return result instanceof NotCovered || (result instanceof Priced priced && priced.amount().signum() > 0);
    }

    /**
     * The kWh of a share spread evenly over the plot's days of the split and priced at the versions of the tariff: the
     * calculator spreads a quantity over all the days of the range and drops the days that are not the plot's, so it
     * is given the share scaled up by {@code days of the range / days of the plot}. Each part then carries
     * {@code kWh x its days / the plot's days}, priced at its rate, and the sum is rounded once.
     */
    private static Result price(TariffEntry tariff, Share share, LocalDate from, LocalDate to) {
        long range = ChronoUnit.DAYS.between(from.minusDays(1), to);
        Quantity spread = share.kwh().times(range).dividedBy(share.count());
        return ReadingChargeCalculator.price(from.minusDays(1), to, spread, tariff.terms(), Basis.PER_KWH,
                share.days(), null);
    }
}
