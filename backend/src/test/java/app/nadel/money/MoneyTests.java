package app.nadel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Money has two decimals, rounds HALF_UP in exactly two places and reads and writes the plain text of the API. */
class MoneyTests {

    @Test
    void anAmountIsRoundedHalfUpToTwoDecimals() {
        assertThat(Money.of(new BigDecimal("19.444425")).toString()).isEqualTo("19.44");
        assertThat(Money.of(new BigDecimal("0.005")).toString()).isEqualTo("0.01");
        assertThat(Money.of(new BigDecimal("0.004")).toString()).isEqualTo("0.00");
        assertThat(Money.of(new BigDecimal("2.675")).toString()).isEqualTo("2.68");
        assertThat(Money.of(new BigDecimal("-0.005")).toString()).isEqualTo("-0.01");
    }

    @Test
    void anAmountAlwaysHasScaleTwo() {
        assertThat(Money.of(BigDecimal.TEN).value().scale()).isEqualTo(2);
        assertThat(Money.of(new BigDecimal("1E+3")).toString()).isEqualTo("1000.00");
        assertThat(Money.ZERO.value()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void aQuotientIsOneExactDivisionRoundedOnce() {
        // 33.3333 x 7 x 30 / 360 = 19.444425 -> 19.44 (functional spec 2.4)
        BigDecimal numerator = new BigDecimal("33.3333").multiply(new BigDecimal("7")).multiply(new BigDecimal("30"));
        assertThat(Money.ofQuotient(numerator, new BigDecimal("360")).toString()).isEqualTo("19.44");
        assertThat(Money.ofQuotient(new BigDecimal("1"), new BigDecimal("8")).toString()).isEqualTo("0.13");
        assertThat(Money.ofQuotient(new BigDecimal("1"), new BigDecimal("3")).toString()).isEqualTo("0.33");
        assertThat(Money.ofQuotient(new BigDecimal("2"), new BigDecimal("3")).toString()).isEqualTo("0.67");
    }

    @Test
    void aQuotientWithAZeroDenominatorFails() {
        assertThatThrownBy(() -> Money.ofQuotient(BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void amountsAreAdded() {
        assertThat(Money.parse("12.50").plus(Money.parse("15.00"))).isEqualTo(Money.parse("27.50"));
        assertThat(Money.ZERO.plus(Money.parse("0.01")).toString()).isEqualTo("0.01");
        assertThat(Money.parse("1.00").plus(Money.parse("-1.00"))).isEqualTo(Money.ZERO);
    }

    @Test
    void amountsAreSubtractedExactly() {
        assertThat(Money.parse("42.50").minus(Money.parse("25.00"))).isEqualTo(Money.parse("17.50"));
        assertThat(Money.parse("25.00").minus(Money.parse("42.50"))).isEqualTo(Money.parse("-17.50"));
        assertThat(Money.parse("0.01").minus(Money.parse("0.01"))).isEqualTo(Money.ZERO);
        assertThat(Money.parse("10.00").minus(Money.parse("-0.05")).value().scale()).isEqualTo(2);
    }

    @Test
    void aNegatedAmountHasTheOppositeSign() {
        // the amount of a reversal (feature 011 §3: 25.00 reversed is -25.00)
        assertThat(Money.parse("25.00").negate()).isEqualTo(Money.parse("-25.00"));
        assertThat(Money.parse("-1500.00").negate()).isEqualTo(Money.parse("1500.00"));
        assertThat(Money.ZERO.negate()).isEqualTo(Money.ZERO);
        assertThat(Money.ZERO.negate().toString()).isEqualTo("0.00");
        Money amount = Money.parse("403.06");
        assertThat(amount.plus(amount.negate())).isEqualTo(Money.ZERO);
    }

    @Test
    void theSignIsMinusOneZeroOrOne() {
        assertThat(Money.parse("-0.01").signum()).isEqualTo(-1);
        assertThat(Money.ZERO.signum()).isZero();
        assertThat(Money.parse("0.00").signum()).isZero();
        assertThat(Money.parse("0.01").signum()).isEqualTo(1);
    }

    @Test
    void theWireFormatIsParsedStrictly() {
        assertThat(Money.parse("1250.50").value()).isEqualTo(new BigDecimal("1250.50"));
        assertThat(Money.parse("-3.00").toString()).isEqualTo("-3.00");
        assertThat(Money.parse("0.00")).isEqualTo(Money.ZERO);
        for (String bad : new String[] {"1250.5", "1250", "1250.505", "1,250.50", " 1.00", "1.00 ", "+1.00", "1e3",
                ".50", "", "abc", "--1.00"}) {
            assertThatThrownBy(() -> Money.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextIsPlainAndRoundTrips() {
        Money big = Money.of(new BigDecimal("1E+8"));
        assertThat(big.toString()).isEqualTo("100000000.00");
        assertThat(Money.parse(big.toString())).isEqualTo(big);
    }

    @Test
    void equalAmountsAreEqual() {
        assertThat(Money.of(new BigDecimal("1.0"))).isEqualTo(Money.parse("1.00")).hasSameHashCodeAs(Money.parse("1.00"));
        assertThat(Money.parse("1.00")).isNotEqualTo(Money.parse("1.01")).isNotEqualTo("1.00");
    }
}
