package app.nadel.ledger.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.LossesCalculator.Outcome;
import app.nadel.ledger.internal.LossesCalculator.UnknownValue;
import app.nadel.ledger.internal.LossesPlanning.PlannedCharge;
import app.nadel.ledger.internal.LossesPlanning.PlannedNode;
import app.nadel.ledger.internal.LossesPlanning.PlannedShare;
import app.nadel.ledger.internal.LossesPlanning.Plan;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import nadel.contract.model.LossesNodeOutcome;
import nadel.contract.model.LossesRunCharge;
import nadel.contract.model.LossesRunDetails;
import nadel.contract.model.LossesRunNode;
import nadel.contract.model.LossesRunPlot;
import nadel.contract.model.LossesRunResult;
import nadel.contract.model.LossesRunStatus;
import nadel.contract.model.LossesRunSummary;
import nadel.contract.model.LossesWarning;
import nadel.contract.model.LossesWarningCode;
import nadel.contract.model.MeterKind;
import nadel.contract.model.NotChargedReason;
import org.jspecify.annotations.Nullable;

/**
 * Shapes losses runs for the API and for storage: the numbers of a node as stored (kWh rounded to three decimals for
 * display, the percentage to two; never used to calculate), its warning and plot shares as codes, ids and numbers
 * (jsonb), and the answers of the endpoints. Plot numbers and names are user content and looked up by the caller. Pure
 * apart from what the caller passes in.
 */
final class LossesRunViews {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LossesRunViews() {
    }

    // ---- numbers

    /** A quantity for display, three decimals, with a minus sign when negative. */
    static String kwh(Quantity quantity) {
        if (quantity.signum() < 0) {
            return "-" + Quantity.ZERO.minus(quantity).toMeterValue();
        }
        return quantity.toMeterValue().toString();
    }

    /** The losses in percent of the consumption, two decimals; null when the consumption is zero. */
    static @Nullable BigDecimal pct(Quantity losses, Quantity consumption) {
        if (consumption.signum() <= 0) {
            return null;
        }
        BigDecimal numerator = new BigDecimal(losses.numerator().multiply(consumption.denominator()))
                .multiply(HUNDRED);
        BigDecimal denominator = new BigDecimal(losses.denominator().multiply(consumption.numerator()));
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP); // a ratio for display, not money
    }

    // ---- storage

    /** The warning of a planned node as stored (codes, ids, dates; the meter name is user content); null for none. */
    static @Nullable Map<String, Object> warning(PlannedNode node, Map<UUID, NetworkChain> chains) {
        NodeResult result = node.result();
        Map<String, Object> warning = new LinkedHashMap<>();
        if (node.overlap() != null) {
            warning.put("code", LossesWarningCode.ALREADY_CHARGED.getValue());
            warning.put("runId", node.overlap().runId().toString());
            warning.put("from", node.overlap().from().toString());
            warning.put("to", node.overlap().to().toString());
            return warning;
        }
        switch (result.outcome()) {
            case NEGATIVE -> warning.put("code", LossesWarningCode.NEGATIVE_LOSSES.getValue());
            case SKIPPED_ABOVE -> {
                warning.put("code", LossesWarningCode.SKIPPED_ABOVE.getValue());
                NetworkChain below = chains.get(result.below());
                if (below != null) {
                    meter(warning, below, below.key());
                }
            }
            case UNKNOWN -> {
                warning.put("code", LossesWarningCode.UNKNOWN_VALUE.getValue());
                UnknownValue unknown = result.unknown();
                if (unknown != null) {
                    NetworkChain chain = chains.get(unknown.chainKey());
                    if (chain != null) {
                        meter(warning, chain, unknown.meterId());
                    }
                    if (unknown.lastReadingOn() != null) {
                        warning.put("lastReadingOn", unknown.lastReadingOn().toString());
                    }
                }
            }
            case CHARGED -> {
                if (!result.noPlotConsumption()) {
                    return null;
                }
                warning.put("code", LossesWarningCode.NO_PLOT_CONSUMPTION.getValue());
            }
        }
        return warning;
    }

    private static void meter(Map<String, Object> warning, NetworkChain chain, UUID meterId) {
        warning.put("meterId", meterId.toString());
        warning.put("meterKind", chain.kind().name());
        if (chain.name() != null) {
            warning.put("meterName", chain.name());
        }
        if (chain.plotId() != null) {
            warning.put("plotId", chain.plotId().toString());
        }
    }

    /** The plot shares of a planned node as stored. */
    static List<Map<String, Object>> plots(PlannedNode node) {
        List<Map<String, Object>> plots = new ArrayList<>();
        for (PlannedShare share : node.shares()) {
            Map<String, Object> plot = new LinkedHashMap<>();
            plot.put("plotId", share.plotId().toString());
            plot.put("kwh", kwh(share.kwh()));
            if (share.amount() != null) {
                plot.put("amount", share.amount().toString());
            }
            if (share.reason() != null) {
                plot.put("reason", share.reason().name());
            }
            plots.add(plot);
        }
        return plots;
    }

    // ---- the answers

    /** What a viewer needs besides the run: plot numbers and their order, and the names of members. */
    record Lookups(Map<UUID, String> plotNumbers, Map<UUID, Integer> plotRank, Map<UUID, String> memberNames) {
    }

    /** A preview or a booking: the plan's nodes and charges; {@code chargeIds} the charges written, by plot. */
    static LossesRunResult result(Plan plan, boolean preview, @Nullable UUID runId, Map<UUID, UUID> chargeIds,
            YearMonth billingMonth, Map<UUID, NetworkChain> chains, Lookups lookups) {
        List<LossesRunNode> nodes = new ArrayList<>();
        for (PlannedNode node : plan.nodes()) {
            NodeResult result = node.result();
            Quantity losses = result.losses();
            nodes.add(node(node.chain().key(), node.chain().kind(), node.chain().name(),
                    result.consumption() == null ? null : result.consumption().toMeterValue().toString(),
                    result.children() == null ? null : result.children().toMeterValue().toString(),
                    losses == null ? null : kwh(losses),
                    losses == null || result.consumption() == null ? null : pct(losses, result.consumption()),
                    result.calculated(), node.overlap() != null ? LossesNodeOutcome.ALREADY_CHARGED
                            : LossesNodeOutcome.fromValue(result.outcome().name()),
                    warning(node, chains), plots(node), node.amount(), lookups));
        }
        List<LossesRunCharge> charges = new ArrayList<>();
        for (PlannedCharge charge : sortedCharges(plan.charges(), lookups)) {
            LossesRunCharge dto = new LossesRunCharge(charge.plotId(), kwh(charge.kwh()), charge.amount().toString());
            dto.setPlotNumber(lookups.plotNumbers().get(charge.plotId()));
            UUID chargeId = chargeIds.get(charge.plotId());
            if (chargeId != null) {
                dto.setChargeId(chargeId);
                dto.setReversed(false);
            }
            charges.add(dto);
        }
        LossesRunResult dto = new LossesRunResult(plan.from(), plan.to(), preview, nodes, charges,
                plan.charges().size(), plan.total().toString(), preview ? 0 : plan.charges().size(), plan.digest());
        dto.setRunId(runId);
        dto.setBillingMonth(billingMonth.toString());
        return dto;
    }

    /** A stored node. */
    static LossesRunNode node(LossesRunNodeRecord row, Lookups lookups) {
        return node(row.getNodeMeterId(), row.getNodeKind(), row.getNodeName(), scaled(row.getConsumption(), 3),
                scaled(row.getChildren(), 3), scaled(row.getLosses(), 3), row.getPct(), row.isCalculated(),
                LossesNodeOutcome.fromValue(row.getOutcome().name()), row.getWarning(), row.getPlots(),
                Money.of(row.getAmount()), lookups);
    }

    private static LossesRunNode node(UUID meterId, app.nadel.meters.MeterKind kind, @Nullable String name,
            @Nullable String consumption, @Nullable String children, @Nullable String losses,
            @Nullable BigDecimal pct, boolean calculated, LossesNodeOutcome outcome,
            @Nullable Map<String, Object> warning, List<Map<String, Object>> plots, Money amount, Lookups lookups) {
        List<Map<String, Object>> ordered = new ArrayList<>(plots);
        ordered.sort(Comparator.comparing(plot -> lookups.plotRank().getOrDefault(uuid(plot.get("plotId")),
                Integer.MAX_VALUE)));
        List<LossesRunPlot> plotDtos = new ArrayList<>();
        for (Map<String, Object> plot : ordered) {
            UUID plotId = uuid(plot.get("plotId"));
            LossesRunPlot dto = new LossesRunPlot(plotId, (String) plot.get("kwh"));
            dto.setPlotNumber(lookups.plotNumbers().get(plotId));
            dto.setAmount((String) plot.get("amount"));
            if (plot.get("reason") != null) {
                dto.setReason(NotChargedReason.fromValue((String) plot.get("reason")));
            }
            plotDtos.add(dto);
        }
        LossesRunNode dto = new LossesRunNode(meterId, MeterKind.fromValue(kind.name()), calculated, outcome, plotDtos,
                amount.toString());
        dto.setName(name);
        dto.setConsumption(consumption);
        dto.setChildren(children);
        dto.setLosses(losses);
        dto.setPct(pct == null ? null : pct.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        dto.setWarning(warning == null ? null : warning(warning, lookups));
        return dto;
    }

    private static LossesWarning warning(Map<String, Object> stored, Lookups lookups) {
        LossesWarning dto = new LossesWarning(LossesWarningCode.fromValue((String) stored.get("code")));
        if (stored.get("meterId") != null) {
            dto.setMeterId(uuid(stored.get("meterId")));
        }
        if (stored.get("meterKind") != null) {
            dto.setMeterKind(MeterKind.fromValue((String) stored.get("meterKind")));
        }
        dto.setMeterName((String) stored.get("meterName"));
        if (stored.get("plotId") != null) {
            dto.setPlotNumber(lookups.plotNumbers().get(uuid(stored.get("plotId"))));
        }
        dto.setLastReadingOn(date(stored.get("lastReadingOn")));
        if (stored.get("runId") != null) {
            dto.setRunId(uuid(stored.get("runId")));
        }
        dto.setFrom(date(stored.get("from")));
        dto.setTo(date(stored.get("to")));
        return dto;
    }

    /** A stored run in the history. */
    static LossesRunSummary summary(LossesRunRecord run, List<LossesRunNodeRecord> nodes, int plots,
            BillingMonthRecord month, AccessProfile viewer, Lookups lookups) {
        LossesRunSummary dto = new LossesRunSummary(run.getId(), run.getFromDate(), run.getToDate(),
                LossesRunStatus.fromValue(run.getStatus().name()), month.yearMonth().toString(),
                run.getCreatedAt().atOffset(ZoneOffset.UTC), run.getTotal().toString(), plots, charged(nodes),
                nodes.size() - charged(nodes), reversible(run, month, viewer));
        dto.setCreatedByName(run.getCreatedBy() == null ? null : lookups.memberNames().get(run.getCreatedBy()));
        reversal(run, lookups, dto::setReversedAt, dto::setReversedByName, dto::setReverseReason);
        return dto;
    }

    /**
     * A stored run with its nodes in their order and its charges ({@code kwh} of each charge from its parts; whether
     * it was reversed).
     */
    static LossesRunDetails details(LossesRunRecord run, List<LossesRunNodeRecord> nodes,
            List<StoredCharge> charges, BillingMonthRecord month, AccessProfile viewer, Lookups lookups) {
        List<LossesRunNode> nodeDtos = nodes.stream().sorted(Comparator.comparingInt(LossesRunNodeRecord::getPosition))
                .map(node -> node(node, lookups)).toList();
        List<LossesRunCharge> chargeDtos = new ArrayList<>();
        for (StoredCharge charge : charges.stream().sorted(Comparator.comparing(
                (StoredCharge c) -> lookups.plotRank().getOrDefault(c.plotId(), Integer.MAX_VALUE))).toList()) {
            LossesRunCharge dto = new LossesRunCharge(charge.plotId(), kwh(charge.kwh()), charge.amount().toString());
            dto.setPlotNumber(lookups.plotNumbers().get(charge.plotId()));
            dto.setChargeId(charge.id());
            dto.setReversed(charge.reversed());
            chargeDtos.add(dto);
        }
        LossesRunDetails dto = new LossesRunDetails(run.getId(), run.getFromDate(), run.getToDate(),
                LossesRunStatus.fromValue(run.getStatus().name()), month.yearMonth().toString(),
                run.getCreatedAt().atOffset(ZoneOffset.UTC), run.getTotal().toString(), charges.size(),
                charged(nodes), nodes.size() - charged(nodes), reversible(run, month, viewer), nodeDtos, chargeDtos);
        dto.setCreatedByName(run.getCreatedBy() == null ? null : lookups.memberNames().get(run.getCreatedBy()));
        reversal(run, lookups, dto::setReversedAt, dto::setReversedByName, dto::setReverseReason);
        return dto;
    }

    /** A charge of a stored run: its plot, exact kWh, amount and whether it was reversed. */
    record StoredCharge(UUID id, UUID plotId, Quantity kwh, Money amount, boolean reversed) {
    }

    private static void reversal(LossesRunRecord run, Lookups lookups,
            Consumer<OffsetDateTime> at, Consumer<String> by, Consumer<String> reason) {
        if (run.getReversedAt() != null) {
            at.accept(run.getReversedAt().atOffset(ZoneOffset.UTC));
        }
        if (run.getReversedBy() != null) {
            by.accept(lookups.memberNames().get(run.getReversedBy()));
        }
        reason.accept(run.getReverseReason());
    }

    /** The viewer may reverse the run now: the permission, a booked run, an open month. */
    private static boolean reversible(LossesRunRecord run, BillingMonthRecord month, AccessProfile viewer) {
        return viewer.can(Permission.CHARGES_REVERSE) && run.getStatus() == LossesRunRecord.Status.BOOKED
                && !month.isClosed();
    }

    private static int charged(List<LossesRunNodeRecord> nodes) {
        return (int) nodes.stream().filter(node -> node.getOutcome() == Outcome.CHARGED).count();
    }

    private static List<PlannedCharge> sortedCharges(List<PlannedCharge> charges, Lookups lookups) {
        return charges.stream().sorted(Comparator.comparing(
                (PlannedCharge c) -> lookups.plotRank().getOrDefault(c.plotId(), Integer.MAX_VALUE))).toList();
    }

    private static @Nullable String scaled(@Nullable BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static UUID uuid(Object value) {
        return UUID.fromString(value.toString());
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
