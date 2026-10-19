package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShareTests {

    @Test
    void parsesAFractionAndFormatsItBack() {
        Share share = Share.parse("2/3");

        assertThat(share.numerator()).isEqualTo(2);
        assertThat(share.denominator()).isEqualTo(3);
        assertThat(share).hasToString("2/3");
    }

    @Test
    void reducesToLowestTerms() {
        assertThat(Share.parse("2/4")).isEqualTo(Share.parse("1/2")).hasToString("1/2");
        assertThat(Share.parse("6/9")).hasToString("2/3");
        assertThat(Share.parse("1000/1000")).hasToString("1/1");
        assertThat(Share.of(250, 1000)).hasToString("1/4");
    }

    @Test
    void acceptsTheBounds() {
        assertThat(Share.parse("1/1")).hasToString("1/1");
        assertThat(Share.parse("1/1000")).hasToString("1/1000");
        assertThat(Share.parse("999/1000")).hasToString("999/1000");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "1/", "/2", "0/2", "1/0", "-1/2", "1/-2", "+1/2", "0.5", "1/2/3", " 1/2", "1/2 ",
            "1 / 2", "a/b", "1/2a", "12345/99999", "01/2"})
    void rejectsTextThatIsNotAFraction(String text) {
        assertThatThrownBy(() -> Share.parse(text)).isInstanceOf(InvalidShareException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3/2", "2/1", "1001/1001", "1/1001", "1001/1002"})
    void rejectsAShareOutsideTheBounds(String text) {
        assertThatThrownBy(() -> Share.parse(text)).isInstanceOf(InvalidShareException.class);
    }

    @Test
    void ofChecksTheBoundsAsGiven() {
        assertThatThrownBy(() -> Share.of(0, 2)).isInstanceOf(InvalidShareException.class);
        assertThatThrownBy(() -> Share.of(3, 2)).isInstanceOf(InvalidShareException.class);
        assertThatThrownBy(() -> Share.of(1, 1001)).isInstanceOf(InvalidShareException.class);
    }

    @Test
    void theConstructorNeedsPositiveParts() {
        assertThatThrownBy(() -> new Share(0, 1)).isInstanceOf(InvalidShareException.class);
        assertThatThrownBy(() -> new Share(1, 0)).isInstanceOf(InvalidShareException.class);
        assertThatThrownBy(() -> new Share(-1, 2)).isInstanceOf(InvalidShareException.class);
    }

    @Test
    void thirdsAddUpToExactlyOneWhole() {
        Share third = Share.parse("1/3");

        Share sum = third.plus(third).plus(third);

        assertThat(sum.isWhole()).isTrue();
        assertThat(sum).isEqualTo(Share.WHOLE);
    }

    @Test
    void halfAndThirdAreFiveSixthsNotAWhole() {
        Share sum = Share.parse("1/2").plus(Share.parse("1/3"));

        assertThat(sum).hasToString("5/6");
        assertThat(sum.isWhole()).isFalse();
    }

    @Test
    void aSumMayExceedOneWhole() {
        Share sum = Share.parse("2/3").plus(Share.parse("2/3"));

        assertThat(sum).hasToString("4/3");
        assertThat(sum.compareTo(Share.WHOLE)).isPositive();
    }

    @Test
    void aSumThatDoesNotFitAnIntIsAnArithmeticException() {
        // pairwise coprime denominators: the denominator of the sum is their product
        Stream<Share> shares = Stream.of(Share.of(1, 997), Share.of(1, 991), Share.of(1, 983), Share.of(1, 977));

        assertThatThrownBy(() -> shares.reduce(Share::plus)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparesByCrossMultiplicationWithoutRounding() {
        assertThat(Share.parse("1/3")).isLessThan(Share.parse("1/2"));
        assertThat(Share.parse("2/3")).isGreaterThan(Share.parse("1/2"));
        assertThat(Share.parse("333/1000")).isLessThan(Share.parse("1/3"));
        assertThat(Share.parse("2/4").compareTo(Share.parse("1/2"))).isZero();
    }

    @Test
    void onlyOneOverOneIsWhole() {
        assertThat(Share.parse("2/2").isWhole()).isTrue();
        assertThat(Share.parse("999/1000").isWhole()).isFalse();
    }
}
