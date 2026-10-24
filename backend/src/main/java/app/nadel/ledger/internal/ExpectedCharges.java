package app.nadel.ledger.internal;

import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.ReadingChargePlanning.PlannedReadingCharge;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The charges 011 would book today, per {@link ChargeIdentity}, as the recalculation planner compares them with the
 * ledger (feature 012, technical considerations 2.2). Pure. Nothing here calculates money: every amount, period and
 * calculation is the one of a 011 planner's result ({@link TariffMonthPlanning}, {@link ReadingChargePlanning},
 * {@link LossesPlanning}, {@link CommonConsumerPlanning}), planned with a "charged" predicate that ignores the ledger
 * ({@code key -> false}) so every charge of the scope is returned.
 */
final class ExpectedCharges {

    private ExpectedCharges() {
    }

    /**
     * What 011 would do for one identity.
     *
     * @param queueKey the "not charged" item the identity belongs to ({@code TARIFF_MONTH} or {@code INTERVAL}); null
     *                 for run lines (their queue items are the runs')
     */
    record Expected(ChargeIdentity identity, UUID plotId, UUID chargeTypeId, Result result,
            @Nullable String queueKey) {

        Expected {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(result, "result");
        }
    }

    /** A charge, or why there is none. */
    sealed interface Result permits Charge, NotCharged {
    }

    /**
     * The charge 011 would book, above {@code 0.00}, with the planned charge the booking writes.
     *
     * @param calculation the calculation it would be stored with (for a run line, with the original run's id)
     */
    record Charge(Money amount, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn,
            Map<String, Object> calculation, Planned planned) implements Result {

        Charge {
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("An expected charge is above 0.00");
            }
        }
    }

    /**
     * The charge cannot be calculated (a {@code Failed} or {@code Conflicting} outcome): it counts as absent and becomes
     * a "not charged" item.
     */
    record NotCharged(Reason reason, List<UUID> tariffIds) implements Result {

        NotCharged {
            tariffIds = List.copyOf(tariffIds);
        }
    }

    /** The 011 planner's charge behind an expected one. */
    sealed interface Planned permits OfTariff, OfInterval, OfLossesRun, OfCommonConsumerRun {
    }

    record OfTariff(TariffMonthPlanning.PlannedCharge charge) implements Planned {
    }

    record OfInterval(PlannedReadingCharge charge) implements Planned {
    }

    record OfLossesRun(LossesPlanning.Plan plan, LossesPlanning.PlannedCharge charge) implements Planned {
    }

    record OfCommonConsumerRun(CommonConsumerPlanning.Plan plan, CommonConsumerPlanning.PlannedCharge charge)
            implements Planned {
    }

    /**
     * The month run's outcome for a plot, a charge type and a month: a new charge; a conflict (one not charged entry
     * per tariff that would charge, each the identity of its tariff); nothing.
     *
     * @throws IllegalArgumentException for {@code AlreadyCharged}: plan with a predicate that ignores the ledger
     */
    static List<Expected> ofTariffMonth(TariffMonthPlanning.Outcome outcome, UUID plotId, UUID chargeTypeId,
            YearMonth month) {
        String queueKey = NotChargedItemRecord.tariffMonthKey(plotId, chargeTypeId, month);
        return switch (outcome) {
            case TariffMonthPlanning.NewCharge newCharge -> {
                TariffMonthPlanning.PlannedCharge charge = newCharge.charge();
                yield List.of(new Expected(ChargeIdentity.of(charge), plotId, chargeTypeId,
                        new Charge(charge.amount(), charge.serviceFrom(), charge.serviceTo(), charge.accruedOn(),
                                charge.calculation(), new OfTariff(charge)), queueKey));
            }
            case TariffMonthPlanning.Conflicting conflicting -> {
                List<UUID> tariffIds = conflicting.tariffs().stream().map(t -> t.id()).toList();
                yield tariffIds.stream().map(tariffId -> new Expected(ChargeIdentity.tariff(tariffId, plotId, month),
                        plotId, chargeTypeId, new NotCharged(Reason.CONFLICTING_TARIFFS, tariffIds), queueKey))
                        .toList();
            }
            case TariffMonthPlanning.Nothing nothing -> List.of();
            case TariffMonthPlanning.AlreadyCharged already -> throw alreadyCharged();
        };
    }

    /**
     * The outcome of a meter interval: its consumption charge and its losses charge; when it fails, both identities
     * are not charged under the interval's item.
     *
     * @throws IllegalArgumentException for {@code AlreadyCharged}: plan with a predicate that ignores the ledger
     */
    static List<Expected> ofInterval(ReadingChargePlanning.Outcome outcome, MeterInterval interval, UUID plotId,
            UUID chargeTypeId) {
        String queueKey = NotChargedItemRecord.intervalKey(interval.meterId(), interval.to().key());
        return switch (outcome) {
            case ReadingChargePlanning.NewCharges charges -> {
                List<Expected> expected = new ArrayList<>();
                expected.add(ofReading(charges.consumption(), queueKey));
                if (charges.losses() != null) {
                    expected.add(ofReading(charges.losses(), queueKey));
                }
                yield expected;
            }
            case ReadingChargePlanning.Failed failed -> List.of(ChargeSource.CONSUMPTION, ChargeSource.LOSSES).stream()
                    .map(source -> new Expected(ChargeIdentity.interval(source, interval.meterId(),
                            interval.from().on(), interval.to().on()), plotId, chargeTypeId,
                            new NotCharged(failed.reason(), failed.tariffIds()), queueKey))
                    .toList();
            case ReadingChargePlanning.Nothing nothing -> List.of();
            case ReadingChargePlanning.AlreadyCharged already -> throw alreadyCharged();
        };
    }

    /**
     * The lines a booked proportional losses run would have today: the plan of the run's range and nodes (the run
     * itself ignored), each plot's charge under the identity of the original run; a plot whose share cannot be priced
     * is not charged with its reason.
     */
    static List<Expected> ofLossesRun(LossesPlanning.Plan plan, UUID runId, UUID chargeTypeId) {
        Map<UUID, Expected> expected = new LinkedHashMap<>();
        for (LossesPlanning.PlannedCharge charge : plan.charges()) {
            expected.put(charge.plotId(), new Expected(ChargeIdentity.lossesRun(runId, charge.plotId()),
                    charge.plotId(), chargeTypeId, new Charge(charge.amount(), plan.from(), plan.to(), plan.to(),
                            charge.calculation(runId), new OfLossesRun(plan, charge)), null));
        }
        for (LossesPlanning.PlannedNode node : plan.nodes()) {
            for (LossesPlanning.PlannedShare share : node.shares()) {
                if (share.reason() != null && !expected.containsKey(share.plotId())) {
                    expected.put(share.plotId(), new Expected(ChargeIdentity.lossesRun(runId, share.plotId()),
                            share.plotId(), chargeTypeId, new NotCharged(share.reason(), List.of()), null));
                }
            }
        }
        return List.copyOf(expected.values());
    }

    /**
     * The lines a booked common consumer run would have today, each plot's charge per consumer under the identity of
     * the original run; a share that cannot be priced is not charged with its reason.
     */
    static List<Expected> ofCommonConsumerRun(CommonConsumerPlanning.Plan plan, UUID runId, UUID chargeTypeId) {
        Map<ChargeIdentity, Expected> expected = new LinkedHashMap<>();
        for (CommonConsumerPlanning.PlannedCharge charge : plan.charges()) {
            ChargeIdentity identity = ChargeIdentity.commonConsumerRun(runId, charge.consumerKey(), charge.plotId());
            expected.put(identity, new Expected(identity, charge.plotId(), chargeTypeId, new Charge(charge.amount(),
                    plan.from(), plan.to(), plan.to(), charge.calculation(runId),
                    new OfCommonConsumerRun(plan, charge)), null));
        }
        for (CommonConsumerPlanning.PlannedLine line : plan.lines()) {
            for (CommonConsumerPlanning.PlannedShare share : line.shares()) {
                ChargeIdentity identity = ChargeIdentity.commonConsumerRun(runId, line.chain().key(),
                        share.plotId());
                if (share.reason() != null && !expected.containsKey(identity)) {
                    expected.put(identity, new Expected(identity, share.plotId(), chargeTypeId,
                            new NotCharged(share.reason(), List.of()), null));
                }
            }
        }
        return List.copyOf(expected.values());
    }

    private static Expected ofReading(PlannedReadingCharge charge, String queueKey) {
        return new Expected(ChargeIdentity.of(charge), charge.plotId(), charge.chargeTypeId(),
                new Charge(charge.amount(), charge.serviceFrom(), charge.serviceTo(), charge.accruedOn(),
                        charge.calculation(), new OfInterval(charge)), queueKey);
    }

    private static IllegalArgumentException alreadyCharged() {
        return new IllegalArgumentException("A recalculation plans with a predicate that ignores the ledger");
    }
}
