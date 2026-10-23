package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import app.nadel.ledger.internal.ReadingChargeCalculator.NotApplicable;
import app.nadel.ledger.internal.ReadingChargeCalculator.NotCovered;
import app.nadel.ledger.internal.ReadingChargeCalculator.Priced;
import app.nadel.ledger.internal.ReadingChargeCalculator.Result;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.MeterPoints;
import app.nadel.meters.MeterNetwork.Point;
import app.nadel.meters.MeterNetwork.PointKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.money.Rate;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reading rows of the worked examples of feature 011 §3 (electricity tariff 4.3200 UAH/kWh unless stated): one
 * charge per interval, the days rule, the tariff split by days, one rounding, a replacement, the initial value, fixed
 * percentage losses and a tariff that does not cover every day.
 */
class ReadingChargeCalculatorTests {

    private static final List<VersionTerms> ELECTRICITY = List.of(kwh("2026-01-01", "4.3200"));

    @Test
    void readingChargeIsTheIntervalConsumptionTimesTheRateForItsDays() {
        // previous 22.09: 112.4, reading 04.10: 205.7 -> 93.3 kWh x 4.32 = 403.056 -> 403.06; 23.09-04.10, 12 days
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-09-22"), date("2026-10-04"),
                consumption("112.4", "205.7"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, null));

        assertThat(priced.amount()).hasToString("403.06");
        assertThat(priced.parts()).singleElement().satisfies(part -> {
            assertThat(part.days()).isEqualTo(12);
            assertThat(part.from()).isEqualTo(date("2026-09-23"));
            assertThat(part.to()).isEqualTo(date("2026-10-04"));
            assertThat(part.quantity().toMeterValue()).hasToString("93.300");
        });
        assertThat(priced.losses()).isNull();
    }

    @Test
    void tariffChangeInsideTheIntervalSplitsTheConsumptionByDays() {
        // 01.10: 1 000, 31.10: 1 300 (02.10-31.10, 30 days); 4.32 until 16.10, 5.00 from 17.10
        // -> 15 days each: 150 x 4.32 + 150 x 5.00 = 648.00 + 750.00 = 1 398.00
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-10-01"), date("2026-10-31"),
                consumption("1000", "1300"), List.of(kwh("2026-01-01", "4.3200"), kwh("2026-10-17", "5.0000")),
                Basis.PER_KWH, DaySet.ALL, null));

        assertThat(priced.amount()).hasToString("1398.00");
        assertThat(priced.parts()).extracting(p -> p.days(), p -> p.quantity().toMeterValue().toString())
                .containsExactly(tuple(15L, "150.000"),
                        tuple(15L, "150.000"));
    }

    @Test
    void theSumOfThePartsIsRoundedOnce() {
        // 8 kWh over 8 days: 7 days at 4.3250 and 1 day at 4.3350 -> 30.275 + 4.335 = 34.610 -> 34.61 (not 34.62)
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-10-01"), date("2026-10-09"),
                consumption("100", "108"), List.of(kwh("2026-01-01", "4.3250"), kwh("2026-10-09", "4.3350")),
                Basis.PER_KWH, DaySet.ALL, null));

        assertThat(priced.amount()).hasToString("34.61");
    }

    @Test
    void aReplacementGivesOneChargePerMeter() {
        // old 03.09: 8 140, removed 20.09 at 8 205; new installed 20.09 at 3, reads 69 on 05.10
        Priced old = priced(ReadingChargeCalculator.price(date("2026-09-03"), date("2026-09-20"),
                consumption("8140", "8205"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, null));
        Priced replacement = priced(ReadingChargeCalculator.price(date("2026-09-20"), date("2026-10-05"),
                consumption("3", "69"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, null));

        assertThat(old.amount()).hasToString("280.80");
        assertThat(replacement.amount()).hasToString("285.12");
    }

    @Test
    void theInitialValueAloneIsNoInterval() {
        UUID meter = UUID.randomUUID();
        MeterPoints installed = new MeterPoints(meter, MeterKind.PLOT, MeterResource.ELECTRICITY, UUID.randomUUID(),
                List.of(new Point(date("2026-10-01"), MeterValue.parse("0"), "INSTALL:" + meter,
                        PointKind.INSTALLATION)));

        assertThat(IntervalCharging.intervals(installed)).isEmpty();
    }

    @Test
    void fixedPercentageLossesAreASecondAmountOverTheSameParts() {
        // 65 kWh, losses 5 % -> CONSUMPTION 280.80 and LOSSES 3.25 kWh = 14.04
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-09-03"), date("2026-09-20"),
                consumption("8140", "8205"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, new BigDecimal("5.00")));

        assertThat(priced.amount()).hasToString("280.80");
        assertThat(priced.losses()).isNotNull();
        assertThat(priced.losses().amount()).hasToString("14.04");
        assertThat(priced.losses().quantity().toMeterValue()).hasToString("3.250");
        assertThat(priced.losses().parts()).singleElement()
                .satisfies(part -> assertThat(part.quantity().toMeterValue()).hasToString("3.250"));
    }

    @Test
    void zeroPercentLossesAreNoLosses() {
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-09-03"), date("2026-09-20"),
                consumption("8140", "8205"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, new BigDecimal("0.00")));

        assertThat(priced.losses()).isNull();
    }

    @Test
    void noTariffForSomeDaysOfTheIntervalChargesNothing() {
        // interval 20.09-05.10 (the day after 19.09 to 05.10), first electricity version from 01.10
        Result result = ReadingChargeCalculator.price(date("2026-09-19"), date("2026-10-05"),
                consumption("100", "160"), List.of(kwh("2026-10-01", "4.3200")), Basis.PER_KWH, DaySet.ALL, null);

        assertThat(result).isEqualTo(new NotCovered(11));
    }

    @Test
    void aTariffNotChargedByTheMeterDoesNotApply() {
        Result result = ReadingChargeCalculator.price(date("2026-09-19"), date("2026-10-05"),
                consumption("100", "160"), List.of(new VersionTerms(date("2026-01-01"), Basis.PER_PLOT,
                        Frequency.MONTHLY, Rate.parse("100"), null)),
                Basis.PER_KWH, DaySet.ALL, null);

        assertThat(result).isInstanceOf(NotApplicable.class);
    }

    @Test
    void daysThatAreNotApplicableAreDroppedWithTheirShare() {
        // water 30 m3 over 30 days (02.10-31.10), connected from 17.10 (15 days) -> 15 m3 x 20.00 = 300.00
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-10-01"), date("2026-10-31"),
                consumption("10", "40"), List.of(m3("2026-01-01", "20.0000")), Basis.PER_M3,
                DaySet.of(date("2026-10-17"), date("2026-12-31")), null));

        assertThat(priced.amount()).hasToString("300.00");
        assertThat(priced.parts()).singleElement().satisfies(part -> {
            assertThat(part.days()).isEqualTo(15);
            assertThat(part.from()).isEqualTo(date("2026-10-17"));
        });
    }

    @Test
    void anIntervalWithNoApplicableDayIsNotCharged() {
        Result result = ReadingChargeCalculator.price(date("2026-10-01"), date("2026-10-31"),
                consumption("10", "40"), List.of(m3("2026-01-01", "20.0000")), Basis.PER_M3, DaySet.NONE, null);

        assertThat(result).isInstanceOf(NotApplicable.class);
    }

    @Test
    void anIntervalWithoutConsumptionCostsNothing() {
        Priced priced = priced(ReadingChargeCalculator.price(date("2026-10-01"), date("2026-10-31"),
                consumption("10", "10"), ELECTRICITY, Basis.PER_KWH, DaySet.ALL, null));

        assertThat(priced.amount()).hasToString("0.00");
    }

    private static Priced priced(Result result) {
        assertThat(result).isInstanceOf(Priced.class);
        return (Priced) result;
    }

    private static Quantity consumption(String from, String to) {
        return Quantity.of(MeterValue.parse(to)).minus(Quantity.of(MeterValue.parse(from)));
    }

    private static VersionTerms kwh(String validFrom, String rate) {
        return new VersionTerms(date(validFrom), Basis.PER_KWH, Frequency.MONTHLY, Rate.parse(rate), null);
    }

    private static VersionTerms m3(String validFrom, String rate) {
        return new VersionTerms(date(validFrom), Basis.PER_M3, Frequency.MONTHLY, Rate.parse(rate), null);
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text);
    }
}
