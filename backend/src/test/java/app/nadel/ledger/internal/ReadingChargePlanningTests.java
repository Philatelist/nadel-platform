package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargePlanning.AlreadyCharged;
import app.nadel.ledger.internal.ReadingChargePlanning.Failed;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.ReadingChargePlanning.NewCharges;
import app.nadel.ledger.internal.ReadingChargePlanning.Nothing;
import app.nadel.ledger.internal.ReadingChargePlanning.Outcome;
import app.nadel.meters.MeterNetwork.Point;
import app.nadel.meters.MeterNetwork.PointKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.money.Rate;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Scope;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffBook.VersionRef;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * What the ledger does with one meter interval: the source keys, the tariff it is priced with, the conflict rule (only
 * tariffs that would charge the interval by the meter take part), the reasons of the "not charged" queue and the
 * fixed-percentage losses charge.
 */
class ReadingChargePlanningTests {

    private static final UUID ELECTRICITY = UUID.randomUUID();
    private static final UUID WATER = UUID.randomUUID();
    private static final UUID METER = UUID.randomUUID();
    private static final PlotFacts PLOT = new PlotFacts(UUID.randomUUID(), false, false, new BigDecimal("600"));

    // previous 22.09: 112.4, reading 04.10: 205.7 -> 93.3 kWh
    private static final MeterInterval INTERVAL = new MeterInterval(METER,
            new Point(LocalDate.parse("2026-09-22"), MeterValue.parse("112.4"), "R1", PointKind.READING),
            new Point(LocalDate.parse("2026-10-04"), MeterValue.parse("205.7"), "R2", PointKind.READING));

    @Test
    @SuppressWarnings("unchecked")
    void oneTariffChargesTheIntervalUnderItsKeyWithItsCalculation() {
        TariffEntry tariff = tariff(ELECTRICITY, "Електроенергія", Basis.PER_KWH, "4.3200");
        Outcome outcome = plan(snapshot(true, tariff), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false);

        assertThat(outcome).isInstanceOf(NewCharges.class);
        NewCharges charges = (NewCharges) outcome;
        assertThat(charges.losses()).isNull();
        assertThat(charges.consumption().sourceKey()).isEqualTo("CONSUMPTION:R1:R2");
        assertThat(charges.consumption().source()).isEqualTo(ChargeSource.CONSUMPTION);
        assertThat(charges.consumption().amount()).hasToString("403.06");
        assertThat(charges.consumption().serviceFrom()).isEqualTo(LocalDate.parse("2026-09-23"));
        assertThat(charges.consumption().serviceTo()).isEqualTo(LocalDate.parse("2026-10-04"));
        assertThat(charges.consumption().accruedOn()).isEqualTo(LocalDate.parse("2026-10-04"));
        Map<String, Object> calculation = charges.consumption().calculation();
        assertThat(calculation).containsEntry("amount", "403.06").doesNotContainKey("lossesPercent");
        assertThat((List<?>) calculation.get("parts")).singleElement().isEqualTo(Map.of(
                "tariffVersionId", tariff.versions().getFirst().id().toString(), "basis", "PER_KWH",
                "frequency", "MONTHLY", "quantity", "93.300", "unit", "KWH", "rate", "4.3200", "days", 12L,
                "from", "2026-09-23", "to", "2026-10-04"));
        assertThat((List<Object>) calculation.get("readings")).containsExactly(
                Map.of("meterId", METER.toString(), "takenOn", "2026-09-22", "value", "112.400"),
                Map.of("meterId", METER.toString(), "takenOn", "2026-10-04", "value", "205.700"));
    }

    @Test
    void anIntervalChargedBeforeIsNotChargedAgain() {
        TariffEntry tariff = tariff(ELECTRICITY, "Електроенергія", Basis.PER_KWH, "4.3200");

        assertThat(plan(snapshot(true, tariff), ELECTRICITY, MeterResource.ELECTRICITY, null,
                "CONSUMPTION:R1:R2"::equals)).isInstanceOf(AlreadyCharged.class);
    }

    @Test
    void twoTariffsThatChargeByTheMeterConflict() {
        TariffEntry a = tariff(ELECTRICITY, "А", Basis.PER_KWH, "4.3200");
        TariffEntry b = tariff(ELECTRICITY, "Б", Basis.PER_KWH, "5.0000");

        assertThat(plan(snapshot(true, a, b), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false))
                .isEqualTo(new Failed(Reason.CONFLICTING_TARIFFS, List.of(a.id(), b.id())));
    }

    @Test
    void onlyTariffsThatWouldChargeTheIntervalTakePartInAConflict() {
        TariffEntry byMeter = tariff(ELECTRICITY, "А", Basis.PER_KWH, "4.3200");
        TariffEntry fixedFee = tariff(ELECTRICITY, "Б", Basis.PER_PLOT, "100.0000");
        TariffEntry free = tariff(ELECTRICITY, "В", Basis.PER_KWH, "0.0000");

        Outcome outcome = plan(snapshot(true, byMeter, fixedFee, free), ELECTRICITY, MeterResource.ELECTRICITY, null,
                key -> false);

        assertThat(outcome).isInstanceOf(NewCharges.class);
        assertThat(((NewCharges) outcome).consumption().tariff().id()).isEqualTo(byMeter.id());
    }

    @Test
    void anInactiveChargeTypeChargesAndQueuesNothing() {
        TariffEntry water = tariff(WATER, "Вода", Basis.PER_M3, "20.0000");

        assertThat(plan(snapshot(false, water), WATER, MeterResource.WATER, null, key -> false))
                .isInstanceOf(Nothing.class);
    }

    @Test
    void theReasonsOfTheQueue() {
        TariffEntry late = new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Пізній", Scope.ALL_PLOTS, Set.of(),
                Set.of(), List.of(version("2026-10-01", Basis.PER_KWH, "4.3200")));
        assertThat(plan(snapshot(true, late), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false))
                .isEqualTo(new Failed(Reason.NO_TARIFF_FOR_ALL_DAYS, List.of(late.id())));

        assertThat(plan(snapshot(true), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false))
                .isEqualTo(new Failed(Reason.NO_TARIFF_FOR_ALL_DAYS, List.of()));

        TariffEntry excluding = new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Е", Scope.ALL_PLOTS, Set.of(),
                Set.of(PLOT.plotId()), List.of(version("2026-01-01", Basis.PER_KWH, "4.3200")));
        assertThat(plan(snapshot(true, excluding), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false))
                .isEqualTo(new Failed(Reason.EXCLUDED, List.of(excluding.id())));

        TariffEntry others = new TariffEntry(UUID.randomUUID(), ELECTRICITY, "Е", Scope.SELECTED_PLOTS,
                Set.of(UUID.randomUUID()), Set.of(), List.of(version("2026-01-01", Basis.PER_KWH, "4.3200")));
        assertThat(plan(snapshot(true, others), ELECTRICITY, MeterResource.ELECTRICITY, null, key -> false))
                .isEqualTo(new Failed(Reason.OUTSIDE_SCOPE, List.of(others.id())));
    }

    @Test
    void fixedPercentageLossesAddALossesChargeForElectricityOnly() {
        TariffEntry tariff = tariff(ELECTRICITY, "Електроенергія", Basis.PER_KWH, "4.3200");
        Outcome outcome = plan(snapshot(true, tariff), ELECTRICITY, MeterResource.ELECTRICITY,
                new BigDecimal("5.00"), key -> false);

        NewCharges charges = (NewCharges) outcome;
        assertThat(charges.losses()).isNotNull();
        assertThat(charges.losses().sourceKey()).isEqualTo("LOSSES:R1:R2");
        assertThat(charges.losses().source()).isEqualTo(ChargeSource.LOSSES);
        // 93.3 x 5 % = 4.665 kWh x 4.32 = 20.1528 -> 20.15
        assertThat(charges.losses().amount()).hasToString("20.15");
        assertThat(charges.losses().calculation()).containsEntry("lossesPercent", "5.00")
                .containsEntry("consumption", "93.300");

        TariffEntry water = tariff(WATER, "Вода", Basis.PER_M3, "20.0000");
        NewCharges waterCharges = (NewCharges) plan(snapshot(true, water), WATER, MeterResource.WATER,
                new BigDecimal("5.00"), key -> false);
        assertThat(waterCharges.losses()).isNull();
        assertThat((List<?>) waterCharges.consumption().calculation().get("parts")).singleElement()
                .satisfies(part -> assertThat(((Map<?, ?>) part).get("unit")).isEqualTo("M3"));
    }

    @Test
    void aWaterIntervalWithoutAConnectedDayIsNothingAndNeverQueuedWhateverTheTariffs() {
        TariffEntry water = tariff(WATER, "Вода", Basis.PER_M3, "20.0000");
        // connected only before and after the interval 23.09-04.10
        DaySet connected = DaySet.of(List.of(new DaySet.Range(LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-09-22")), new DaySet.Range(LocalDate.parse("2026-10-05"), LocalDate.MAX)));

        // no water tariff at all would otherwise be NO_TARIFF_FOR_ALL_DAYS
        assertThat(ReadingChargePlanning.plan(snapshot(true), WATER, PLOT, MeterResource.WATER, INTERVAL, connected,
                null, key -> false)).isInstanceOf(Nothing.class);
        assertThat(ReadingChargePlanning.plan(snapshot(true, water), WATER, PLOT, MeterResource.WATER, INTERVAL,
                DaySet.NONE, null, key -> false)).isInstanceOf(Nothing.class);
    }

    @Test
    void aWaterIntervalIsChargedForItsConnectedDaysOnly() {
        TariffEntry water = tariff(WATER, "Вода", Basis.PER_M3, "20.0000");
        // 93.3 m3 over 12 days, connected 29.09-04.10 (6 days): 46.65 m3 x 20 = 933.00
        DaySet connected = DaySet.of(LocalDate.parse("2026-09-29"), LocalDate.MAX);

        NewCharges charges = (NewCharges) ReadingChargePlanning.plan(snapshot(true, water), WATER, PLOT,
                MeterResource.WATER, INTERVAL, connected, null, key -> false);

        assertThat(charges.consumption().amount()).hasToString("933.00");
        assertThat(charges.consumption().serviceFrom()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(charges.consumption().serviceTo()).isEqualTo(LocalDate.parse("2026-10-04"));
    }

    private static Outcome plan(Snapshot snapshot, UUID type, MeterResource resource, BigDecimal percent,
            Predicate<String> charged) {
        return ReadingChargePlanning.plan(snapshot, type, PLOT, resource, INTERVAL, DaySet.ALL, percent, charged);
    }

    private static Snapshot snapshot(boolean active, TariffEntry... tariffs) {
        return new Snapshot(List.of(new ChargeTypeEntry(ELECTRICITY, "ELECTRICITY", true, false),
                new ChargeTypeEntry(WATER, "WATER", active, false)), List.of(tariffs));
    }

    private static TariffEntry tariff(UUID type, String name, Basis basis, String rate) {
        return new TariffEntry(UUID.randomUUID(), type, name, Scope.ALL_PLOTS, Set.of(), Set.of(),
                List.of(version("2026-01-01", basis, rate)));
    }

    private static VersionRef version(String validFrom, Basis basis, String rate) {
        return new VersionRef(UUID.randomUUID(), new VersionTerms(LocalDate.parse(validFrom), basis,
                Frequency.MONTHLY, Rate.parse(rate), null));
    }
}
