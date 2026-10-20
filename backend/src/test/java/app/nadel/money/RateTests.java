package app.nadel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A rate keeps up to four decimals, is shown with exactly four and is never rounded. */
class RateTests {

    @Test
    void aRateIsNormalisedToFourDecimalsWithoutChangingItsValue() {
        assertThat(Rate.parse("4.32").toString()).isEqualTo("4.3200");
        assertThat(Rate.parse("50").toString()).isEqualTo("50.0000");
        assertThat(Rate.parse("0").toString()).isEqualTo("0.0000");
        assertThat(Rate.parse("0.0001").toString()).isEqualTo("0.0001");
        assertThat(Rate.parse("33.3333").value()).isEqualTo(new BigDecimal("33.3333"));
        assertThat(Rate.parse("4.32").value().scale()).isEqualTo(4);
    }

    @Test
    void aFifthDecimalIsRejectedNotRounded() {
        assertThatThrownBy(() -> Rate.parse("1.23456")).isInstanceOf(InvalidRateException.class);
        assertThatThrownBy(() -> Rate.parse("0.00005")).isInstanceOf(InvalidRateException.class);
        assertThatThrownBy(() -> Rate.of(new BigDecimal("1.23456"))).isInstanceOf(InvalidRateException.class);
    }

    @Test
    void anythingButANonNegativeDecimalIsRejected() {
        for (String bad : new String[] {"-1", "-0.0001", "+1", "abc", "", " ", "1,5", "1e3", "1.", ".5", "1.5 ",
                " 1.5", "NaN", "Infinity", "12345678901", "1.2.3"}) {
            assertThatThrownBy(() -> Rate.parse(bad)).as(bad).isInstanceOf(InvalidRateException.class);
        }
        assertThatThrownBy(() -> Rate.parse(null)).isInstanceOf(InvalidRateException.class);
    }

    @Test
    void tenIntegerDigitsAreTheMost() {
        assertThat(Rate.parse("9999999999.9999").toString()).isEqualTo("9999999999.9999");
        assertThatThrownBy(() -> Rate.parse("10000000000")).isInstanceOf(InvalidRateException.class);
        assertThatThrownBy(() -> Rate.of(new BigDecimal("1E+10"))).isInstanceOf(InvalidRateException.class);
        assertThat(Rate.of(new BigDecimal("1E+9")).toString()).isEqualTo("1000000000.0000");
    }

    @Test
    void aNumberWithTrailingZerosBeyondTheFourthDecimalIsAccepted() {
        assertThat(Rate.of(new BigDecimal("1.230000")).toString()).isEqualTo("1.2300");
        assertThat(Rate.of(new BigDecimal("0.00000")).toString()).isEqualTo("0.0000");
    }

    @Test
    void aNegativeNumberIsRejected() {
        assertThatThrownBy(() -> Rate.of(new BigDecimal("-0.0001"))).isInstanceOf(InvalidRateException.class);
        assertThatThrownBy(() -> Rate.of(new BigDecimal("-5"))).isInstanceOf(InvalidRateException.class);
    }

    @Test
    void equalRatesAreEqualWhateverTheirInputScale() {
        assertThat(Rate.parse("4.3")).isEqualTo(Rate.of(new BigDecimal("4.30"))).hasSameHashCodeAs(Rate.parse("4.3000"));
        assertThat(Rate.parse("4.3")).isNotEqualTo(Rate.parse("4.31")).isNotEqualTo("4.3000");
    }
}
