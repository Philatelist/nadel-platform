package app.nadel.ledger.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.CommonConsumerPlanning.Outcome;
import app.nadel.ledger.internal.CommonConsumerPlanning.Plan;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedLine;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedShare;
import app.nadel.ledger.internal.LossesCalculator.UnknownValue;
import app.nadel.ledger.internal.LossesRunViews.Lookups;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
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
import nadel.contract.model.CommonConsumerOutcome;
import nadel.contract.model.CommonConsumerRunDetails;
import nadel.contract.model.CommonConsumerRunLine;
import nadel.contract.model.CommonConsumerRunPlot;
import nadel.contract.model.CommonConsumerRunResult;
import nadel.contract.model.CommonConsumerRunStatus;
import nadel.contract.model.CommonConsumerRunSummary;
import nadel.contract.model.CommonConsumerWarning;
import nadel.contract.model.CommonConsumerWarningCode;
import nadel.contract.model.NotChargedReason;
import org.jspecify.annotations.Nullable;

/**
 * Shapes common consumer runs for the API and for storage: the numbers of a consumer as stored (kWh rounded to three
 * decimals for display; never used to calculate), its warning and plot shares as codes, ids and numbers (jsonb), and
 * the answers of the endpoints. Plot numbers and member names are user content and looked up by the caller. Pure apart
 * from what the caller passes in.
 */
final class CommonConsumerRunViews {

    private CommonConsumerRunViews() {
    }

    /** A charge of a stored run: its id and whether it was reversed. */
    record StoredCharge(UUID id, boolean reversed) {
    }

    /** The key of the charge of a plot for a consumer in the maps of stored charges. */
    static String chargeKey(UUID consumerKey, UUID plotId) {
        return consumerKey + ":" + plotId;
    }

    // ---- storage

    /** The warning of a planned consumer as stored (codes, ids, dates); null for none. */
    static @Nullable Map<String, Object> warning(PlannedLine line) {
        Map<String, Object> warning = new LinkedHashMap<>();
        if (line.overlap() != null) {
            warning.put("code", CommonConsumerWarningCode.ALREADY_CHARGED.getValue());
            warning.put("runId", line.overlap().runId().toString());
            warning.put("from", line.overlap().from().toString());
            warning.put("to", line.overlap().to().toString());
            return warning;
        }
        switch (line.outcome()) {
            case UNKNOWN -> {
                warning.put("code", CommonConsumerWarningCode.UNKNOWN_VALUE.getValue());
                UnknownValue unknown = line.unknown();
                if (unknown != null) {
                    warning.put("meterId", unknown.meterId().toString());
                    if (unknown.lastReadingOn() != null) {
                        warning.put("lastReadingOn", unknown.lastReadingOn().toString());
                    }
                }
            }
            case NO_PLOTS -> warning.put("code", CommonConsumerWarningCode.NO_PLOTS.getValue());
            case CHARGED, NOT_CHARGED -> {
                return null;
            }
        }
        return warning;
    }

    /** The plot shares of a planned consumer as stored. */
    static List<Map<String, Object>> plots(PlannedLine line) {
        List<Map<String, Object>> plots = new ArrayList<>();
        for (PlannedShare share : line.shares()) {
            Map<String, Object> plot = new LinkedHashMap<>();
            plot.put("plotId", share.plotId().toString());
            plot.put("days", share.days());
            plot.put("kwh", LossesRunViews.kwh(share.kwh()));
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

    /** A preview or a booking: the plan's consumers; {@code chargeIds} the charges written, by {@link #chargeKey}. */
    static CommonConsumerRunResult result(Plan plan, boolean preview, @Nullable UUID runId,
            Map<String, UUID> chargeIds, YearMonth billingMonth, Lookups lookups) {
        Map<String, StoredCharge> stored = new LinkedHashMap<>();
        chargeIds.forEach((key, id) -> stored.put(key, new StoredCharge(id, false)));
        List<CommonConsumerRunLine> lines = new ArrayList<>();
        for (PlannedLine line : plan.lines()) {
            Quantity consumption = line.consumption();
            lines.add(line(line.chain().key(), line.chain().name(), line.split(),
                    consumption == null ? null : consumption.toMeterValue().toString(), line.calculated(),
                    line.overlap() != null ? CommonConsumerOutcome.ALREADY_CHARGED
                            : CommonConsumerOutcome.fromValue(line.outcome().name()),
                    warning(line), line.plotDays(), plots(line), line.amount(), stored, lookups));
        }
        CommonConsumerRunResult dto = new CommonConsumerRunResult(plan.from(), plan.to(), preview, plan.noTariff(),
                lines, plan.plots(), plan.charges().size(), plan.total().toString(),
                preview ? 0 : plan.charges().size(), plan.digest());
        dto.setRunId(runId);
        dto.setBillingMonth(billingMonth.toString());
        return dto;
    }

    /** A stored consumer; {@code charges} the run's charges by {@link #chargeKey}. */
    static CommonConsumerRunLine line(CommonConsumerRunLineRecord row, Map<String, StoredCharge> charges,
            Lookups lookups) {
        return line(row.getConsumerMeterId(), row.getConsumerName(), row.getSplit(),
                row.getConsumption() == null ? null
                        : row.getConsumption().setScale(3, RoundingMode.UNNECESSARY).toPlainString(),
                row.isCalculated(), CommonConsumerOutcome.fromValue(row.getOutcome().name()), row.getWarning(),
                row.getPlotDays(), row.getPlots(), Money.of(row.getAmount()), charges, lookups);
    }

    private static CommonConsumerRunLine line(UUID consumer, @Nullable String name, CommonConsumerSplit split,
            @Nullable String consumption, boolean calculated, CommonConsumerOutcome outcome,
            @Nullable Map<String, Object> warning, long plotDays, List<Map<String, Object>> plots, Money amount,
            Map<String, StoredCharge> charges, Lookups lookups) {
        List<Map<String, Object>> ordered = new ArrayList<>(plots);
        ordered.sort(Comparator.comparing(plot -> lookups.plotRank().getOrDefault(uuid(plot.get("plotId")),
                Integer.MAX_VALUE)));
        List<CommonConsumerRunPlot> plotDtos = new ArrayList<>();
        for (Map<String, Object> plot : ordered) {
            UUID plotId = uuid(plot.get("plotId"));
            CommonConsumerRunPlot dto = new CommonConsumerRunPlot(plotId, ((Number) plot.get("days")).intValue(),
                    (String) plot.get("kwh"));
            dto.setPlotNumber(lookups.plotNumbers().get(plotId));
            dto.setAmount((String) plot.get("amount"));
            if (plot.get("reason") != null) {
                dto.setReason(NotChargedReason.fromValue((String) plot.get("reason")));
            }
            StoredCharge charge = charges.get(chargeKey(consumer, plotId));
            if (charge != null) {
                dto.setChargeId(charge.id());
                dto.setReversed(charge.reversed());
            }
            plotDtos.add(dto);
        }
        CommonConsumerRunLine dto = new CommonConsumerRunLine(consumer,
                nadel.contract.model.CommonConsumerSplit.fromValue(split.name()), calculated, outcome, plotDays,
                plotDtos, amount.toString());
        dto.setName(name);
        dto.setConsumption(consumption);
        dto.setWarning(warning == null ? null : warning(warning));
        return dto;
    }

    private static CommonConsumerWarning warning(Map<String, Object> stored) {
        CommonConsumerWarning dto = new CommonConsumerWarning(
                CommonConsumerWarningCode.fromValue((String) stored.get("code")));
        if (stored.get("meterId") != null) {
            dto.setMeterId(uuid(stored.get("meterId")));
        }
        dto.setLastReadingOn(date(stored.get("lastReadingOn")));
        if (stored.get("runId") != null) {
            dto.setRunId(uuid(stored.get("runId")));
        }
        dto.setFrom(date(stored.get("from")));
        dto.setTo(date(stored.get("to")));
        return dto;
    }

    /** The counts of a stored run: plots charged, charges, consumers charged and not charged. */
    private record Counts(int plots, int charges, int charged, int skipped) {
    }

    private static Counts counts(List<CommonConsumerRunLineRecord> lines) {
        List<UUID> plots = new ArrayList<>();
        int charges = 0;
        int charged = 0;
        for (CommonConsumerRunLineRecord line : lines) {
            if (line.getOutcome() != Outcome.CHARGED) {
                continue;
            }
            charged++;
            for (Map<String, Object> plot : line.getPlots()) {
                if (plot.get("amount") != null) {
                    charges++;
                    UUID plotId = uuid(plot.get("plotId"));
                    if (!plots.contains(plotId)) {
                        plots.add(plotId);
                    }
                }
            }
        }
        return new Counts(plots.size(), charges, charged, lines.size() - charged);
    }

    /** A stored run in the history. */
    static CommonConsumerRunSummary summary(CommonConsumerRunRecord run, List<CommonConsumerRunLineRecord> lines,
            BillingMonthRecord month, AccessProfile viewer, Lookups lookups) {
        Counts counts = counts(lines);
        CommonConsumerRunSummary dto = new CommonConsumerRunSummary(run.getId(), run.getFromDate(), run.getToDate(),
                CommonConsumerRunStatus.fromValue(run.getStatus().name()), month.yearMonth().toString(),
                run.getCreatedAt().atOffset(ZoneOffset.UTC), run.getTotal().toString(), counts.plots(),
                counts.charges(), counts.charged(), counts.skipped(), reversible(run, month, viewer));
        dto.setCreatedByName(run.getCreatedBy() == null ? null : lookups.memberNames().get(run.getCreatedBy()));
        reversal(run, lookups, dto::setReversedAt, dto::setReversedByName, dto::setReverseReason);
        return dto;
    }

    /** A stored run with its consumers in their order, each plot share with its charge. */
    static CommonConsumerRunDetails details(CommonConsumerRunRecord run, List<CommonConsumerRunLineRecord> lines,
            Map<String, StoredCharge> charges, BillingMonthRecord month, AccessProfile viewer, Lookups lookups) {
        Counts counts = counts(lines);
        List<CommonConsumerRunLine> lineDtos = lines.stream()
                .sorted(Comparator.comparingInt(CommonConsumerRunLineRecord::getPosition))
                .map(line -> line(line, charges, lookups)).toList();
        CommonConsumerRunDetails dto = new CommonConsumerRunDetails(run.getId(), run.getFromDate(), run.getToDate(),
                CommonConsumerRunStatus.fromValue(run.getStatus().name()), month.yearMonth().toString(),
                run.getCreatedAt().atOffset(ZoneOffset.UTC), run.getTotal().toString(), counts.plots(),
                counts.charges(), counts.charged(), counts.skipped(), reversible(run, month, viewer), lineDtos);
        dto.setCreatedByName(run.getCreatedBy() == null ? null : lookups.memberNames().get(run.getCreatedBy()));
        reversal(run, lookups, dto::setReversedAt, dto::setReversedByName, dto::setReverseReason);
        return dto;
    }

    private static void reversal(CommonConsumerRunRecord run, Lookups lookups,
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
    private static boolean reversible(CommonConsumerRunRecord run, BillingMonthRecord month, AccessProfile viewer) {
        return viewer.can(Permission.CHARGES_REVERSE) && run.getStatus() == CommonConsumerRunRecord.Status.BOOKED
                && !month.isClosed();
    }

    /** A stored consumption for storage: the display value of the exact quantity. */
    static @Nullable BigDecimal consumption(@Nullable Quantity consumption) {
        return consumption == null ? null : consumption.toMeterValue().value();
    }

    private static UUID uuid(Object value) {
        return UUID.fromString(value.toString());
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }
}
