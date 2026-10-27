package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.meters.ConsumptionCalculator.RangeConsumption;
import app.nadel.meters.ConsumptionCalculator.ValueAt;
import app.nadel.meters.MeterTreeLosses;
import app.nadel.meters.MeterTreeQuantity;
import app.nadel.meters.MeterTreeUnknownReason;
import app.nadel.meters.MeterTreeValue;
import app.nadel.meters.MeterValue;
import app.nadel.meters.MeterValueKind;
import app.nadel.meters.Quantity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The derived figures of the electricity report's tree (money rules 6.3), without a database. */
class TreeFiguresTests {

    private static final LocalDate SEPTEMBER = LocalDate.parse("2025-09-30");
    private static final LocalDate OCTOBER = LocalDate.parse("2025-10-05");

    @Test
    void theChildrenAddUpExactlyAndAreCalculatedWhenAnyChildIs() {
        MeterTreeQuantity sum = TreeFigures.childrenSum(List.of(known("600", false), known("400", false),
                new RangeConsumption.Known(Quantity.ofFraction(BigInteger.valueOf(100), BigInteger.valueOf(3)), true)));

        assertThat(sum.kwh()).isEqualTo(Quantity.ofFraction(BigInteger.valueOf(3100), BigInteger.valueOf(3)));
        assertThat(sum.calculated()).isTrue();
        assertThat(sum.reason()).isNull();
    }

    @Test
    void aNodeWithoutChildrenHasAChildrenSumOfZero() {
        assertThat(TreeFigures.childrenSum(List.of())).isEqualTo(MeterTreeQuantity.known(Quantity.ZERO, false));
    }

    @Test
    void anUnknownChildMakesTheSumUnknownWithTheLatestLastReading() {
        MeterTreeQuantity sum = TreeFigures.childrenSum(List.of(known("600", false),
                new RangeConsumption.Unknown(SEPTEMBER), new RangeConsumption.Unknown(OCTOBER),
                new RangeConsumption.Unknown(null)));

        assertThat(sum).isEqualTo(MeterTreeQuantity.unknown(MeterTreeUnknownReason.CHILD_VALUE_UNKNOWN, OCTOBER));
    }

    @Test
    void theLossesAreTheConsumptionMinusTheChildrenWithTheirPercent() {
        MeterTreeLosses losses = TreeFigures.losses(MeterTreeQuantity.known(kwh("1100"), false),
                MeterTreeQuantity.known(kwh("1000"), true));

        assertThat(losses.kwh()).isEqualTo(MeterTreeQuantity.known(kwh("100"), true));
        assertThat(losses.percent()).isEqualByComparingTo("9.09");
    }

    @Test
    void negativeLossesAreShownWithTheirSign() {
        MeterTreeLosses losses = TreeFigures.losses(MeterTreeQuantity.known(kwh("950"), false),
                MeterTreeQuantity.known(kwh("1000"), false));

        assertThat(losses.kwh().kwh()).isEqualTo(Quantity.ZERO.minus(kwh("50")));
        assertThat(losses.percent()).isEqualByComparingTo("-5.26");
    }

    @Test
    void lossesOfAnUnknownNodeOrChildAreUnknownWithTheReasonAndNeverZero() {
        MeterTreeQuantity ownUnknown = MeterTreeQuantity.unknown(MeterTreeUnknownReason.VALUE_UNKNOWN, SEPTEMBER);
        MeterTreeQuantity childUnknown = MeterTreeQuantity.unknown(MeterTreeUnknownReason.CHILD_VALUE_UNKNOWN,
                OCTOBER);

        assertThat(TreeFigures.losses(ownUnknown, childUnknown)).isEqualTo(new MeterTreeLosses(ownUnknown, null));
        assertThat(TreeFigures.losses(MeterTreeQuantity.known(kwh("10"), false), childUnknown))
                .isEqualTo(new MeterTreeLosses(childUnknown, null));
    }

    @Test
    void thePercentOfAZeroConsumptionIsNotShown() {
        MeterTreeLosses losses = TreeFigures.losses(MeterTreeQuantity.known(Quantity.ZERO, false),
                MeterTreeQuantity.known(Quantity.ZERO, false));

        assertThat(losses.kwh().kwh()).isEqualTo(Quantity.ZERO);
        assertThat(losses.percent()).isNull();
    }

    @Test
    void valuesKeepTheirKindAndExactNumber() {
        UUID meter = UUID.randomUUID();
        Quantity third = Quantity.ofFraction(BigInteger.ONE, BigInteger.valueOf(3));

        assertThat(TreeFigures.value(meter, new ValueAt.Real(MeterValue.of(new BigDecimal("8140.5")))))
                .isEqualTo(new MeterTreeValue(MeterValueKind.REAL, meter, kwh("8140.5"), null));
        assertThat(TreeFigures.value(meter, new ValueAt.Calculated(third)))
                .isEqualTo(new MeterTreeValue(MeterValueKind.CALCULATED, meter, third, null));
        assertThat(TreeFigures.value(meter, new ValueAt.Unknown(OCTOBER)))
                .isEqualTo(new MeterTreeValue(MeterValueKind.UNKNOWN, meter, null, OCTOBER));
        assertThat(TreeFigures.consumption(new RangeConsumption.Unknown(OCTOBER)))
                .isEqualTo(MeterTreeQuantity.unknown(MeterTreeUnknownReason.VALUE_UNKNOWN, OCTOBER));
    }

    private static RangeConsumption known(String kwh, boolean calculated) {
        return new RangeConsumption.Known(kwh(kwh), calculated);
    }

    private static Quantity kwh(String value) {
        return Quantity.of(MeterValue.of(new BigDecimal(value)));
    }
}
