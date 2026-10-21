package app.nadel.meters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A meter value keeps up to three decimals, is written with exactly three and is never rounded. */
class MeterValueTests {

    @Test
    void aValueIsNormalisedToThreeDecimalsWithoutChangingItsMeaning() {
        assertThat(MeterValue.parse("8205").toString()).isEqualTo("8205.000");
        assertThat(MeterValue.parse("8205.5").toString()).isEqualTo("8205.500");
        assertThat(MeterValue.parse("0.125").toString()).isEqualTo("0.125");
        assertThat(MeterValue.parse("0").toString()).isEqualTo("0.000");
        assertThat(MeterValue.parse("00012").toString()).isEqualTo("12.000");
        assertThat(MeterValue.parse("8205.120").value()).isEqualTo(new BigDecimal("8205.120"));
        assertThat(MeterValue.parse("8205").value().scale()).isEqualTo(MeterValue.SCALE);
    }

    @Test
    void aFourthDecimalIsRejectedNotRounded() {
        assertThatThrownBy(() -> MeterValue.parse("1.2345")).isInstanceOf(InvalidMeterValueException.class);
        assertThatThrownBy(() -> MeterValue.parse("0.0005")).isInstanceOf(InvalidMeterValueException.class);
        assertThatThrownBy(() -> MeterValue.of(new BigDecimal("1.2345"))).isInstanceOf(InvalidMeterValueException.class);
        assertThatThrownBy(() -> MeterValue.of(new BigDecimal("0.0001"))).isInstanceOf(InvalidMeterValueException.class);
    }

    @Test
    void zerosBeyondThreeDecimalsAreNotADecimal() {
        // a number (not the wire text) may carry them: BigDecimal("1.230000") is 1.23
        assertThat(MeterValue.of(new BigDecimal("1.230000")).toString()).isEqualTo("1.230");
        assertThat(MeterValue.of(new BigDecimal("0.0000")).toString()).isEqualTo("0.000");
        assertThat(MeterValue.of(new BigDecimal("1E+3")).toString()).isEqualTo("1000.000");
    }

    @Test
    void anythingButANonNegativeDecimalIsRejected() {
        for (String bad : new String[] {"-1", "-0.001", "+1", "abc", "", " ", "1,5", "1e3", "1.", ".5", "1.5 ", " 1.5",
                "NaN", "Infinity", "100000000000", "1.2.3", "8205\n", "١٢"}) {
            assertThatThrownBy(() -> MeterValue.parse(bad)).as(bad).isInstanceOf(InvalidMeterValueException.class);
        }
        assertThatThrownBy(() -> MeterValue.parse(null)).isInstanceOf(InvalidMeterValueException.class);
        assertThatThrownBy(() -> MeterValue.of(new BigDecimal("-0.001"))).isInstanceOf(InvalidMeterValueException.class);
    }

    @Test
    void elevenIntegerDigitsAreTheMost() {
        assertThat(MeterValue.parse("99999999999.999").toString()).isEqualTo("99999999999.999");
        assertThatThrownBy(() -> MeterValue.parse("100000000000.000")).isInstanceOf(InvalidMeterValueException.class);
        assertThatThrownBy(() -> MeterValue.of(new BigDecimal("1E+11"))).isInstanceOf(InvalidMeterValueException.class);
    }

    @Test
    void valuesCompareByTheirNumber() {
        assertThat(MeterValue.parse("8205")).isEqualTo(MeterValue.parse("8205.000")).hasSameHashCodeAs(
                MeterValue.parse("8205.000"));
        assertThat(MeterValue.parse("8205")).isNotEqualTo(MeterValue.parse("8205.001")).isNotEqualTo("8205.000");
        assertThat(MeterValue.parse("8100")).isLessThan(MeterValue.parse("8140"));
        assertThat(MeterValue.parse("8140.001")).isGreaterThan(MeterValue.parse("8140"));
        assertThat(MeterValue.ZERO.toString()).isEqualTo("0.000");
    }

    @Test
    void theTextIsPlainNeverScientific() {
        assertThat(MeterValue.of(new BigDecimal("1E+8")).toString()).isEqualTo("100000000.000");
    }
}
