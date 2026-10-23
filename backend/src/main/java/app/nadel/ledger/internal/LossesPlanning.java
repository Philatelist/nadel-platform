package app.nadel.ledger.internal;

import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.LossesCalculator.Outcome;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargeCalculator.NotCovered;
import app.nadel.ledger.internal.ReadingChargeCalculator.Priced;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.ledger.internal.ReadingChargeCalculator.Result;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.None;
import app.nadel.tariffs.TariffResolver.Single;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a proportional losses run books (feature 011 §2.5), from the nodes {@link LossesCalculator} worked out. Pure: no
 * Spring, no database, no clock.
 * <p>
 * Only a {@code CHARGED} node that no booked run charged for a day of the range ({@link Overlap}) charges. The kWh a plot
 * gets from every such node are added and priced once, with the plot's electricity tariff over the range as
 * {@link TariffResolver} picks it, spread evenly over the days of the range ({@link ReadingChargeCalculator}: split by
 * days when the rate changes, added exactly, rounded once); one charge per plot. A plot whose kWh cannot be priced gets
 * no charge: no per-kWh tariff for every day ({@code NO_TARIFF_FOR_ALL_DAYS}), two tariffs that would both charge it
 * ({@code CONFLICTING_TARIFFS}), outside the scope or excluded; an inactive charge type or an amount of {@code 0.00}
 * charges nothing either. The share of each node in a plot's charge is shown priced on its own (for the node's amount)
 * and kept as the charge's parts, one per node and tariff version.
 */
final class LossesPlanning {

    private LossesPlanning() {
    }

    /** A booked run that charged the node for a day of the range. */
    record Overlap(UUID runId, LocalDate from, LocalDate to) {
    }

    /**
     * The share of one plot in one node.
     *
     * @param amount the share priced on its own; null when the plot's kWh are not charged
     * @param reason why the plot is not charged, when the board can fix it
     */
    record PlannedShare(UUID plotId, Quantity kwh, @Nullable Money amount, @Nullable Reason reason) {
    }

    /** A node of the run with its shares (empty unless it charges) and their amount. */
    record PlannedNode(NetworkChain chain, NodeResult result, @Nullable Overlap overlap, List<PlannedShare> shares,
            Money amount) {

        PlannedNode {
            shares = List.copyOf(shares);
        }

        /** Whether the node charges in this run. */
        boolean charges() {
            return result.outcome() == Outcome.CHARGED && overlap == null;
        }
    }

    /** A part of a charge: the kWh of one node priced at one tariff version. */
    record NodePart(UUID nodeKey, boolean calculated, PricedPart part) {
    }

    /** The losses charge of a plot: its kWh from every charged node, priced once. */
    record PlannedCharge(UUID plotId, Quantity kwh, Money amount, TariffEntry tariff, List<NodePart> parts) {

        PlannedCharge {
            parts = List.copyOf(parts);
        }

        /** The version id of each part, in the order of the parts. */
        List<UUID> versionIds() {
            return parts.stream().map(p -> tariff.versionStartingOn(p.part().terms().validFrom()).orElseThrow())
                    .toList();
        }

        /**
         * The calculation stored with the charge: codes and numbers only (the {@code ChargeCalculation} of the
         * contract). One part per node and version with its kWh (three decimals for display), rate, days, the first
         * and last day, the node and whether a value behind it was calculated; the run; the node when there is one.
         */
        Map<String, Object> calculation(UUID runId) {
            List<UUID> ids = versionIds();
            List<Map<String, Object>> lines = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                NodePart nodePart = parts.get(i);
                PricedPart part = nodePart.part();
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
                line.put("nodeMeterId", nodePart.nodeKey().toString());
                line.put("calculated", nodePart.calculated());
                lines.add(line);
            }
            Map<String, Object> calculation = new LinkedHashMap<>();
            calculation.put("amount", amount.toString());
            calculation.put("parts", lines);
            calculation.put("readings", List.of());
            calculation.put("runId", runId.toString());
            List<UUID> nodes = parts.stream().map(NodePart::nodeKey).distinct().toList();
            if (nodes.size() == 1) {
                calculation.put("nodeMeterId", nodes.getFirst().toString());
            }
            return calculation;
        }
    }

    /** What the run books: the selected nodes in their order and one charge per plot. */
    record Plan(LocalDate from, LocalDate to, List<PlannedNode> nodes, List<PlannedCharge> charges) {

        Plan {
            nodes = List.copyOf(nodes);
            charges = List.copyOf(charges);
        }

        Money total() {
            return charges.stream().map(PlannedCharge::amount).reduce(Money.ZERO, Money::plus);
        }

        /** The nodes that a booked run charged already. */
        List<PlannedNode> overlapping() {
            return nodes.stream().filter(node -> node.overlap() != null).toList();
        }

        /**
         * A SHA-256 over what a booking writes: the outcome and numbers of every node that is not charged already, and
         * every charge (plot, exact kWh, amount). A preview of every node therefore matches a booking of the nodes that
         * are not charged already.
         */
        String digest() {
            List<String> lines = new ArrayList<>();
            for (PlannedNode node : nodes) {
                if (node.overlap() == null) {
                    lines.add("N|" + node.chain().key() + "|" + node.result().outcome() + "|"
                            + node.result().consumption() + "|" + node.result().children());
                }
            }
            for (PlannedCharge charge : charges) {
                lines.add("C|" + charge.plotId() + "|" + charge.kwh() + "|" + charge.amount());
            }
            String text = lines.stream().sorted().collect(Collectors.joining("\n"));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is always available", e);
            }
        }
    }

    /** The price of a plot's kWh: the tariff and the amount, or why it is not charged. */
    private record PlotPrice(@Nullable TariffEntry tariff, @Nullable Priced priced, @Nullable Reason reason) {

        boolean charges() {
            return priced != null && priced.amount().signum() > 0;
        }
    }

    /**
     * @param from         the first day of the range
     * @param to           the last day of the range
     * @param nodes        the selected nodes in the order of the run, each with its chain and the booked run that
     *                     charged it already (null when none)
     * @param snapshot     the charge types and tariffs
     * @param electricity  the electricity charge type; null when the cooperative has none
     * @param plots        the facts of the plots the nodes share among; a plot missing here is not charged
     */
    static Plan plan(LocalDate from, LocalDate to, List<PlannedInput> nodes, Snapshot snapshot,
            @Nullable UUID electricity, Map<UUID, TariffResolver.PlotFacts> plots) {
        List<NodeResult> charging = nodes.stream().filter(PlannedInput::charges).map(PlannedInput::result).toList();
        Map<UUID, Quantity> totals = LossesCalculator.plotTotals(charging);
        Map<UUID, PlotPrice> prices = new LinkedHashMap<>();
        totals.forEach((plot, kwh) -> prices.put(plot, price(snapshot, electricity, plots.get(plot), kwh, from, to)));

        List<PlannedCharge> charges = new ArrayList<>();
        for (Map.Entry<UUID, Quantity> total : totals.entrySet()) {
            PlotPrice price = prices.get(total.getKey());
            if (!price.charges()) {
                continue;
            }
            TariffEntry tariff = Objects.requireNonNull(price.tariff());
            List<NodePart> parts = new ArrayList<>();
            for (PlannedInput node : nodes) {
                Quantity share = node.charges() ? node.result().shares().get(total.getKey()) : null;
                if (share != null) {
                    for (PricedPart part : priceOf(tariff, share, from, to).parts()) {
                        parts.add(new NodePart(node.chain().key(), node.result().calculated(), part));
                    }
                }
            }
            charges.add(new PlannedCharge(total.getKey(), total.getValue(), Objects.requireNonNull(price.priced())
                    .amount(), tariff, parts));
        }

        List<PlannedNode> planned = new ArrayList<>();
        for (PlannedInput node : nodes) {
            List<PlannedShare> shares = new ArrayList<>();
            Money amount = Money.ZERO;
            if (node.charges()) {
                for (Map.Entry<UUID, Quantity> share : node.result().shares().entrySet()) {
                    PlotPrice price = prices.get(share.getKey());
                    Money priced = null;
                    if (price.charges()) {
                        priced = priceOf(Objects.requireNonNull(price.tariff()), share.getValue(), from, to).amount();
                        amount = amount.plus(priced);
                    }
                    shares.add(new PlannedShare(share.getKey(), share.getValue(), priced, price.reason()));
                }
            }
            planned.add(new PlannedNode(node.chain(), node.result(), node.overlap(), shares, amount));
        }
        return new Plan(from, to, planned, charges);
    }

    /** A selected node as the plan needs it: its chain, its result and a booked run that charged it already. */
    record PlannedInput(NetworkChain chain, NodeResult result, @Nullable Overlap overlap) {

        boolean charges() {
            return result.outcome() == Outcome.CHARGED && overlap == null;
        }
    }

    /** The price of a plot's kWh over the range, as {@link ReadingChargePlanning} prices a meter interval. */
    private static PlotPrice price(Snapshot snapshot, @Nullable UUID electricity,
            TariffResolver.@Nullable PlotFacts plot, Quantity kwh, LocalDate from, LocalDate to) {
        if (electricity == null) {
            return new PlotPrice(null, null, null);
        }
        if (plot == null) {
            return new PlotPrice(null, null, Reason.OUTSIDE_SCOPE);
        }
        return switch (TariffResolver.resolve(snapshot, electricity, plot, from, to)) {
            case Single single -> priced(single.tariff(), kwh, from, to);
            case Conflict conflict -> {
                List<TariffEntry> charging = conflict.tariffs().stream().filter(t -> wouldCharge(t, kwh, from, to))
                        .toList();
                if (charging.size() == 1) {
                    yield priced(charging.getFirst(), kwh, from, to);
                }
                yield new PlotPrice(null, null, charging.isEmpty() ? null : Reason.CONFLICTING_TARIFFS);
            }
            case None none -> new PlotPrice(null, null, switch (none.reason()) {
                case TYPE_INACTIVE -> null;
                case NO_TARIFF -> Reason.NO_TARIFF_FOR_ALL_DAYS;
                case EXCLUDED -> Reason.EXCLUDED;
                case OUTSIDE_SCOPE -> Reason.OUTSIDE_SCOPE;
            });
        };
    }

    private static PlotPrice priced(TariffEntry tariff, Quantity kwh, LocalDate from, LocalDate to) {
        Result result = price(tariff, kwh, from, to);
        if (result instanceof Priced priced) {
            return new PlotPrice(tariff, priced, null);
        }
        // not covered on every day, or no per-kWh version on any day of the range
        return new PlotPrice(tariff, null, Reason.NO_TARIFF_FOR_ALL_DAYS);
    }

    private static boolean wouldCharge(TariffEntry tariff, Quantity kwh, LocalDate from, LocalDate to) {
        Result result = price(tariff, kwh, from, to);
        return result instanceof NotCovered || (result instanceof Priced priced && priced.amount().signum() > 0);
    }

    /** The kWh of the days of the range, spread evenly over them and priced at the versions of the tariff. */
    private static Result price(TariffEntry tariff, Quantity kwh, LocalDate from, LocalDate to) {
        return ReadingChargeCalculator.price(from.minusDays(1), to, kwh, tariff.terms(), Basis.PER_KWH, DaySet.ALL,
                null);
    }

    private static Priced priceOf(TariffEntry tariff, Quantity kwh, LocalDate from, LocalDate to) {
        if (price(tariff, kwh, from, to) instanceof Priced priced) {
            return priced;
        }
        throw new IllegalStateException("A share is priced like the plot's whole kWh");
    }
}
