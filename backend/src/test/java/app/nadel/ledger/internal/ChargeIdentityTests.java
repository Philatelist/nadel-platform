package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.RecalculationFixtures.COMMON_CONSUMERS;
import static app.nadel.ledger.internal.RecalculationFixtures.ELECTRICITY;
import static app.nadel.ledger.internal.RecalculationFixtures.MEMBERSHIP;
import static app.nadel.ledger.internal.RecalculationFixtures.METER;
import static app.nadel.ledger.internal.RecalculationFixtures.NOW;
import static app.nadel.ledger.internal.RecalculationFixtures.OCT;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT_2;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT_600;
import static app.nadel.ledger.internal.RecalculationFixtures.SEP;
import static app.nadel.ledger.internal.RecalculationFixtures.TENANT;
import static app.nadel.ledger.internal.RecalculationFixtures.electricityTariff;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedElectricity;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedMembership;
import static app.nadel.ledger.internal.RecalculationFixtures.interval;
import static app.nadel.ledger.internal.RecalculationFixtures.lossesPlan;
import static app.nadel.ledger.internal.RecalculationFixtures.membership;
import static app.nadel.ledger.internal.RecalculationFixtures.perKwh;
import static app.nadel.ledger.internal.RecalculationFixtures.perSotka;
import static app.nadel.ledger.internal.RecalculationFixtures.point;
import static app.nadel.ledger.internal.RecalculationFixtures.snapshot;
import static app.nadel.ledger.internal.RecalculationFixtures.tariff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.ExpectedCharges.NotCharged;
import app.nadel.ledger.internal.LossesCalculator.Measured;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.RecalculationFixtures.Ledger;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.Snapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The identity of a charge (feature 012, technical considerations 2.2): a booked charge and the charge 011 would book
 * today have the same identity when they charge the same thing — the same tariff, plot and month; the same interval
 * of a meter by the dates of its two points (not the readings' keys); the same plot (and consumer) of a run.
 */
class ChargeIdentityTests {

    private final Ledger ledger = new Ledger();

    @Test
    void aTariffChargeIsItsTariffPlotAndMonthWithTheRevisionSuffixStripped() {
        UUID tariffId = UUID.randomUUID();
        Snapshot snapshot = snapshot(membership(tariffId, perSotka(UUID.randomUUID(), "2026-01-01", "50.0000")));
        ChargeRecord booked = ledger.membership(snapshot, PLOT_600, SEP, SEP);
        ChargeRecord rebooked = ledger.membership(snapshot, PLOT_600, SEP, SEP, "#r" + UUID.randomUUID());
        Expected expected = expectedMembership(snapshot, PLOT_600, SEP).getFirst();

        ChargeIdentity identity = ChargeIdentity.tariff(tariffId, PLOT, SEP);
        assertThat(identity.value()).isEqualTo("T:" + tariffId + ":" + PLOT + ":2026-09");
        assertThat(identity.kind()).isEqualTo(ChargeIdentity.Kind.TARIFF);
        assertThat(identity.runId()).isNull();
        assertThat(ChargeIdentity.of(BookedCharge.of(booked, SEP))).contains(identity);
        assertThat(ChargeIdentity.of(BookedCharge.of(rebooked, SEP))).contains(identity);
        assertThat(expected.identity()).isEqualTo(identity);
        assertThat(ChargeIdentity.tariff(tariffId, PLOT, OCT)).isNotEqualTo(identity);
        assertThat(ChargeIdentity.tariff(tariffId, PLOT_2, SEP)).isNotEqualTo(identity);
    }

    @Test
    void anIntervalChargeIsItsSourceMeterAndTheDatesOfItsPointsNotTheReadingKeys() {
        Snapshot snapshot = snapshot(electricityTariff());
        MeterInterval interval = interval(point("2026-09-05", "8140", "R1"), point("2026-10-05", "8205", "R2"));
        List<ChargeRecord> booked = ledger.electricity(snapshot, interval, new BigDecimal("5.00"), OCT);

        // the reading of 05.10 corrected: a new reading with a new key on the same date
        MeterInterval corrected = interval(point("2026-09-05", "8140", "R1"),
                point("2026-10-05", "8215", "R2-corrected"));
        List<Expected> expected = expectedElectricity(snapshot, corrected, new BigDecimal("5.00"));

        LocalDate from = LocalDate.parse("2026-09-05");
        LocalDate to = LocalDate.parse("2026-10-05");
        ChargeIdentity consumption = ChargeIdentity.interval(ChargeSource.CONSUMPTION, METER, from, to);
        ChargeIdentity losses = ChargeIdentity.interval(ChargeSource.LOSSES, METER, from, to);
        assertThat(consumption.value()).isEqualTo("I:CONSUMPTION:" + METER + ":2026-09-05:2026-10-05");
        assertThat(ChargeIdentity.of(BookedCharge.of(booked.get(0), OCT))).contains(consumption);
        assertThat(ChargeIdentity.of(BookedCharge.of(booked.get(1), OCT))).contains(losses);
        assertThat(expected).extracting(Expected::identity).containsExactly(consumption, losses);

        // another date is another interval
        MeterInterval moved = interval(point("2026-09-05", "8140", "R1"), point("2026-10-06", "8205", "R2"));
        assertThat(expectedElectricity(snapshot, moved, null)).extracting(Expected::identity)
                .doesNotContain(consumption);
    }

    @Test
    void aLossesRunLineIsItsRunAndPlot() {
        Snapshot snapshot = snapshot(electricityTariff());
        UUID run = UUID.randomUUID();
        LossesPlanning.Plan plan = lossesPlan(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), snapshot,
                Map.of(PLOT, PLOT_600), Map.of(PLOT, "6"));
        ChargeRecord booked = ledger.lossesRun(plan, run, SEP).getFirst();

        ChargeIdentity identity = ChargeIdentity.lossesRun(run, PLOT);
        assertThat(identity.value()).isEqualTo("R:" + run + ":" + PLOT);
        assertThat(identity.runId()).isEqualTo(run);
        assertThat(ChargeIdentity.of(BookedCharge.of(booked, SEP))).contains(identity);
        assertThat(ExpectedCharges.ofLossesRun(plan, run, ELECTRICITY)).extracting(Expected::identity)
                .containsExactly(identity);
    }

    @Test
    void aCommonConsumerRunLineIsItsRunConsumerAndPlot() {
        UUID consumer = UUID.randomUUID();
        UUID unknownPlot = UUID.randomUUID();
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-30");
        Snapshot snapshot = snapshot(tariff(UUID.randomUUID(), COMMON_CONSUMERS,
                perKwh(UUID.randomUUID(), "2026-01-01", "4.3200")));
        NetworkChain chain = new NetworkChain(consumer, MeterKind.COMMON_CONSUMER, null, consumer, "Освітлення",
                List.of(), List.of(), List.of());
        Map<UUID, DaySet> eligible = new LinkedHashMap<>();
        eligible.put(PLOT, DaySet.ALL);
        eligible.put(unknownPlot, DaySet.ALL);
        CommonConsumerPlanning.Plan plan = CommonConsumerPlanning.plan(from, to, List.of(
                new CommonConsumerPlanning.ConsumerInput(chain, CommonConsumerSplit.EQUAL_PER_PLOT,
                        new Measured(Quantity.of(MeterValue.parse("20")), false, null, Set.of()), eligible, null)),
                snapshot, Map.of(PLOT, PLOT_600));
        UUID run = UUID.randomUUID();
        CommonConsumerPlanning.PlannedCharge planned = plan.charges().getFirst();
        ChargeRecord booked = ledger.add(ChargeRecord.commonConsumerRun(TENANT, PLOT, COMMON_CONSUMERS,
                UUID.randomUUID(), planned.tariff().id(), run, planned.amount(), from, to,
                "COMMON_CONSUMER_RUN:" + run + ":" + consumer + ":" + PLOT, planned.calculation(run), null, NOW), SEP);

        ChargeIdentity identity = ChargeIdentity.commonConsumerRun(run, consumer, PLOT);
        assertThat(identity.value()).isEqualTo("R:" + run + ":" + consumer + ":" + PLOT);
        assertThat(ChargeIdentity.of(BookedCharge.of(booked, SEP))).contains(identity);
        List<Expected> expected = ExpectedCharges.ofCommonConsumerRun(plan, run, COMMON_CONSUMERS);
        assertThat(expected).extracting(Expected::identity).containsExactly(identity,
                ChargeIdentity.commonConsumerRun(run, consumer, unknownPlot));
        assertThat(expected.getFirst().result()).isInstanceOfSatisfying(ExpectedCharges.Charge.class,
                charge -> assertThat(charge.amount()).isEqualTo(planned.amount()));
        assertThat(expected.get(1).result()).isEqualTo(new NotCharged(Reason.OUTSIDE_SCOPE, List.of()));
    }

    @Test
    void reversalsAdjustmentsAndOpeningBalancesHaveNoIdentityOfTheirOwn() {
        Snapshot snapshot = snapshot(membership(UUID.randomUUID(), perSotka(UUID.randomUUID(), "2026-01-01",
                "50.0000")));
        ChargeRecord september = ledger.membership(snapshot, PLOT_600, SEP, SEP);
        ChargeRecord october = ledger.membership(snapshot, PLOT_600, OCT, OCT);

        assertThat(ChargeIdentity.of(BookedCharge.of(ledger.adjust(september, "-5.00", OCT), OCT))).isEmpty();
        assertThat(ChargeIdentity.of(BookedCharge.of(ledger.reverseByHand(october, OCT), OCT))).isEmpty();
        assertThat(ChargeIdentity.of(BookedCharge.of(ChargeRecord.opening(TENANT, PLOT, MEMBERSHIP,
                UUID.randomUUID(), Money.parse("10.00"), LocalDate.parse("2026-08-31"), null, null, Map.of(), null, NOW),
                SEP))).isEmpty();
    }

    @Test
    void aRegularChargeWithoutWhatItsIdentityIsMadeOfIsRefused() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.TARIFF, null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.TARIFF, "LOSSES_RUN:a:b", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.CONSUMPTION, "CONSUMPTION:a:b", METER,
                Map.of("readings", List.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.CONSUMPTION, "CONSUMPTION:a:b", null,
                Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.CONSUMPTION, "CONSUMPTION:a:b", METER,
                Map.of("readings", List.of("x", "y"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.of(booked(id, ChargeSource.OPENING, null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeIdentity.interval(ChargeSource.TARIFF, METER, LocalDate.MIN, LocalDate.MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeIdentity("R:x", ChargeIdentity.Kind.RUN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeIdentity("T:x", ChargeIdentity.Kind.TARIFF, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBaseKeyDropsTheRevisionSuffix() {
        assertThat(ChargeIdentity.baseKey("CONSUMPTION:R1:R2#r123")).isEqualTo("CONSUMPTION:R1:R2");
        assertThat(ChargeIdentity.baseKey("CONSUMPTION:R1:R2")).isEqualTo("CONSUMPTION:R1:R2");
        ChargeIdentity identity = ChargeIdentity.lossesRun(UUID.randomUUID(), PLOT);
        assertThat(identity).hasToString(identity.value());
    }

    private static BookedCharge booked(UUID id, ChargeSource source, String sourceKey, UUID meterId,
            Map<String, Object> calculation) {
        return new BookedCharge(id, ChargeKind.REGULAR, source, PLOT, MEMBERSHIP, SEP, Money.parse("1.00"),
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01"),
                sourceKey, null, meterId, null, null, null, calculation);
    }
}
