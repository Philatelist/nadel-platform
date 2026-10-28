package app.nadel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The proportional split of an amount (feature 015 §2.6, examples 12 and 13): exact shares rounded down to the kopeck,
 * the leftover kopecks to the largest remainders, ties to the earlier weight; the parts always add up to the amount.
 */
class MoneyAllocateTests {

    private static List<Money> amounts(String... values) {
        return Arrays.stream(values).map(Money::parse).toList();
    }

    @Test
    void example13_oneHundredOverThreeEqualDebts_givesTheExtraKopeckToTheFirst() {
        assertThat(Money.parse("100.00").allocate(amounts("100.00", "100.00", "100.00")))
                .containsExactlyElementsOf(amounts("33.34", "33.33", "33.33"));
    }

    @Test
    void example12_twoHundredOverDebts300And100_isOneHundredFiftyAndFifty() {
        assertThat(Money.parse("200.00").allocate(amounts("300.00", "100.00")))
                .containsExactlyElementsOf(amounts("150.00", "50.00"));
    }

    @Test
    void theLeftoverKopecksGoToTheLargestRemaindersNotToTheFirstPart() {
        // 1.00 over 1, 2, 3: exact 0.1666.., 0.3333.., 0.5 -> down 0.16, 0.33, 0.50 (0.01 left); remainders
        // 0.0066.., 0.0033.., 0 -> the first part gets it
        assertThat(Money.parse("1.00").allocate(amounts("1.00", "2.00", "3.00")))
                .containsExactlyElementsOf(amounts("0.17", "0.33", "0.50"));
        // 0.05 over 1, 3, 3: exact 0.00714.., 0.02142.., 0.02142.. -> down 0.00, 0.02, 0.02 (0.01 left); remainders
        // 0.00714.. and 0.00142.. twice -> the first part gets it
        assertThat(Money.parse("0.05").allocate(amounts("1.00", "3.00", "3.00")))
                .containsExactlyElementsOf(amounts("0.01", "0.02", "0.02"));
        // 0.10 over 1, 2, 2, 2: exact 0.01428.., 0.02857.. x3 -> down 0.01, 0.02 x3 (0.03 left); remainders
        // 0.00428.. and 0.00857.. x3 -> the three larger ones, in order
        assertThat(Money.parse("0.10").allocate(amounts("1.00", "2.00", "2.00", "2.00")))
                .containsExactlyElementsOf(amounts("0.01", "0.03", "0.03", "0.03"));
    }

    @Test
    void equalRemaindersGoToTheEarlierWeights() {
        assertThat(Money.parse("0.02").allocate(amounts("1.00", "1.00", "1.00")))
                .containsExactlyElementsOf(amounts("0.01", "0.01", "0.00"));
        assertThat(Money.parse("0.01").allocate(amounts("5.00", "5.00")))
                .containsExactlyElementsOf(amounts("0.01", "0.00"));
    }

    @Test
    void thePartsAlwaysAddUpToTheAmount() {
        Money total = Money.parse("1234.57");
        List<Money> parts = total.allocate(amounts("17.13", "0.01", "999.99", "0.00", "250.00", "3.33"));

        assertThat(parts.stream().reduce(Money.ZERO, Money::plus)).isEqualTo(total);
        assertThat(parts.get(3)).isEqualTo(Money.ZERO);
        assertThat(parts).allSatisfy(part -> assertThat(part.value().scale()).isEqualTo(2));
    }

    @Test
    void anAmountAboveTheWeightsIsStillSplitInProportion() {
        assertThat(Money.parse("500.00").allocate(amounts("300.00", "100.00")))
                .containsExactlyElementsOf(amounts("375.00", "125.00"));
    }

    @Test
    void withEveryWeightZeroTheWholeAmountIsTheFirstPart() {
        assertThat(Money.parse("100.00").allocate(amounts("0.00", "0.00", "0.00")))
                .containsExactlyElementsOf(amounts("100.00", "0.00", "0.00"));
    }

    @Test
    void zeroIsSplitIntoZeros() {
        assertThat(Money.ZERO.allocate(amounts("300.00", "100.00"))).containsExactlyElementsOf(amounts("0.00", "0.00"));
    }

    @Test
    void oneWeightTakesTheWholeAmount() {
        assertThat(Money.parse("42.42").allocate(amounts("7.00"))).containsExactly(Money.parse("42.42"));
    }

    @Test
    void thePartsCannotBeChanged() {
        List<Money> parts = Money.parse("10.00").allocate(amounts("1.00", "1.00"));

        assertThatThrownBy(() -> parts.set(0, Money.ZERO)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNegativeAmountANegativeWeightOrNoWeightIsRefused() {
        assertThatThrownBy(() -> Money.parse("-1.00").allocate(amounts("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1.00").allocate(amounts("1.00", "-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1.00").allocate(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
