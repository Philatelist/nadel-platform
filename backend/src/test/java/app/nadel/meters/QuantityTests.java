package app.nadel.meters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * A quantity is an exact reduced fraction; only {@code toDecimal} (and {@code toMeterValue} through it) rounds, HALF_UP
 * to three decimals.
 */
class QuantityTests {

    private static Quantity q(long numerator, long denominator) {
        return Quantity.ofFraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    private static Quantity of(String value) {
        return Quantity.of(MeterValue.parse(value));
    }

    @Test
    void aFractionIsReducedAndKeepsItsSignOnTheNumerator() {
        assertThat(q(6, 4).numerator()).isEqualTo(BigInteger.valueOf(3));
        assertThat(q(6, 4).denominator()).isEqualTo(BigInteger.TWO);
        assertThat(q(3, -6).numerator()).isEqualTo(BigInteger.valueOf(-1));
        assertThat(q(3, -6).denominator()).isEqualTo(BigInteger.TWO);
        assertThat(q(0, 7)).isEqualTo(Quantity.ZERO);
        assertThat(q(0, 7).denominator()).isEqualTo(BigInteger.ONE);
        assertThat(q(10, 5)).isEqualTo(q(2, 1));
    }

    @Test
    void aZeroDenominatorIsRefused() {
        assertThatThrownBy(() -> q(1, 0)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Quantity.ZERO.dividedBy(0)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void aReadingBecomesAnExactQuantity() {
        assertThat(of("8205")).isEqualTo(q(8205, 1));
        assertThat(of("0.125")).isEqualTo(q(1, 8));
        assertThat(of("8205.001")).isEqualTo(q(8205001, 1000));
        assertThat(of("0")).isEqualTo(Quantity.ZERO);
    }

    @Test
    void arithmeticIsExact() {
        assertThat(q(1, 3).plus(q(1, 6))).isEqualTo(q(1, 2));
        assertThat(q(1, 3).minus(q(1, 2))).isEqualTo(q(-1, 6));
        assertThat(q(1, 3).times(3)).isEqualTo(q(1, 1));
        assertThat(q(1, 3).dividedBy(2)).isEqualTo(q(1, 6));
        assertThat(q(1, 3).times(-2)).isEqualTo(q(-2, 3));
        assertThat(q(7, 3).dividedBy(-7)).isEqualTo(q(-1, 3));
        // thirds do not drift: three thirds are one, not 0.999
        assertThat(q(1, 3).plus(q(1, 3)).plus(q(1, 3))).isEqualTo(q(1, 1));
    }

    @Test
    void quantitiesCompareByTheirNumber() {
        assertThat(q(1, 3)).isLessThan(q(1, 2)).isGreaterThan(q(-1, 2)).isEqualByComparingTo(q(2, 6));
        assertThat(q(1, 3)).hasSameHashCodeAs(q(2, 6)).isNotEqualTo(q(1, 2)).isNotEqualTo("1/3");
        assertThat(q(-1, 3).signum()).isEqualTo(-1);
        assertThat(Quantity.ZERO.signum()).isZero();
        assertThat(q(1, 3).signum()).isEqualTo(1);
    }

    @Test
    void theMeterValueIsRoundedHalfUpToThreeDecimals() {
        assertThat(q(1, 3).toMeterValue().toString()).isEqualTo("0.333");
        assertThat(q(2, 3).toMeterValue().toString()).isEqualTo("0.667");
        assertThat(q(1150, 1).toMeterValue().toString()).isEqualTo("1150.000");
        // a tie rounds up: 0.0005 -> 0.001, 1.2345 -> 1.235
        assertThat(q(1, 2000).toMeterValue().toString()).isEqualTo("0.001");
        assertThat(q(12345, 10000).toMeterValue().toString()).isEqualTo("1.235");
        // below the tie it rounds down
        assertThat(q(1, 8000).toMeterValue().toString()).isEqualTo("0.000");
        assertThat(q(12344, 10000).toMeterValue().toString()).isEqualTo("1.234");
        assertThat(Quantity.ZERO.toMeterValue()).isEqualTo(MeterValue.ZERO);
    }

    @Test
    void roundingForDisplayDoesNotChangeTheQuantity() {
        Quantity third = q(1, 3);

        assertThat(third.toMeterValue()).isEqualTo(MeterValue.parse("0.333"));
        assertThat(third).isEqualTo(q(1, 3));
        assertThat(third.times(3)).isEqualTo(q(1, 1));
        // rounding first and then multiplying is what this type exists to avoid
        assertThat(Quantity.of(third.toMeterValue()).times(3)).isNotEqualTo(q(1, 1));
    }

    @Test
    void theSignedDisplayValueRoundsTheSameWayAndKeepsTheSign() {
        assertThat(q(1, 3).toDecimal()).hasToString("0.333");
        assertThat(q(-1, 3).toDecimal()).hasToString("-0.333");
        assertThat(q(-50, 1).toDecimal()).hasToString("-50.000");
        // HALF_UP rounds a tie away from zero on both sides
        assertThat(q(1, 2000).toDecimal()).hasToString("0.001");
        assertThat(q(-1, 2000).toDecimal()).hasToString("-0.001");
        assertThat(q(-1, 8000).toDecimal().signum()).isZero();
        assertThat(q(2, 3).toDecimal()).isEqualTo(q(2, 3).toMeterValue().value());
    }

    @Test
    void aNegativeQuantityIsNotAMeterValue() {
        assertThatThrownBy(() -> q(-1, 3).toMeterValue()).isInstanceOf(IllegalStateException.class);
        // a negative that rounds to zero is zero
        assertThat(q(-1, 8000).toMeterValue()).isEqualTo(MeterValue.ZERO);
    }

    @Test
    void thePlainTextIsTheFractionOrTheWholeNumber() {
        assertThat(q(3425, 3).toString()).isEqualTo("3425/3");
        assertThat(q(150, 1).toString()).isEqualTo("150");
        assertThat(q(-1, 6).toString()).isEqualTo("-1/6");
    }
}
