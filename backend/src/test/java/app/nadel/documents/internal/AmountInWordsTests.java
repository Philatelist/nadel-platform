package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The amount in Ukrainian words of a cash receipt (feature 016 §2.8, technical considerations 2.7): the examples of the
 * functional spec, the feminine hryvnia and thousand, the masculine million and billion, the one / few / many forms,
 * 11-14 always many, kopecks always two digits with the agreeing word, the range ends; a negative amount and one above
 * the range are refused.
 */
class AmountInWordsTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            0.05            | нуль гривень 05 копійок
            1.00            | одна гривня 00 копійок
            21.00           | двадцять одна гривня 00 копійок
            101.50          | сто одна гривня 50 копійок
            1234567.89      | один мільйон двісті тридцять чотири тисячі п'ятсот шістдесят сім гривень 89 копійок
            100.00          | сто гривень 00 копійок
            0.00            | нуль гривень 00 копійок
            0.01            | нуль гривень 01 копійка
            0.02            | нуль гривень 02 копійки
            0.04            | нуль гривень 04 копійки
            0.11            | нуль гривень 11 копійок
            0.14            | нуль гривень 14 копійок
            0.21            | нуль гривень 21 копійка
            0.99            | нуль гривень 99 копійок
            2.00            | дві гривні 00 копійок
            3.00            | три гривні 00 копійок
            4.50            | чотири гривні 50 копійок
            5.00            | п'ять гривень 00 копійок
            10.00           | десять гривень 00 копійок
            11.00           | одинадцять гривень 00 копійок
            12.00           | дванадцять гривень 00 копійок
            13.00           | тринадцять гривень 00 копійок
            14.14           | чотирнадцять гривень 14 копійок
            15.00           | п'ятнадцять гривень 00 копійок
            19.00           | дев'ятнадцять гривень 00 копійок
            20.00           | двадцять гривень 00 копійок
            22.00           | двадцять дві гривні 00 копійок
            25.00           | двадцять п'ять гривень 00 копійок
            40.00           | сорок гривень 00 копійок
            99.99           | дев'яносто дев'ять гривень 99 копійок
            111.00          | сто одинадцять гривень 00 копійок
            112.00          | сто дванадцять гривень 00 копійок
            200.00          | двісті гривень 00 копійок
            413.26          | чотириста тринадцять гривень 26 копійок
            999.00          | дев'ятсот дев'яносто дев'ять гривень 00 копійок
            1000.00         | одна тисяча гривень 00 копійок
            1001.00         | одна тисяча одна гривня 00 копійок
            2000.00         | дві тисячі гривень 00 копійок
            2002.02         | дві тисячі дві гривні 02 копійки
            5000.00         | п'ять тисяч гривень 00 копійок
            11000.00        | одинадцять тисяч гривень 00 копійок
            12000.00        | дванадцять тисяч гривень 00 копійок
            21000.00        | двадцять одна тисяча гривень 00 копійок
            22500.00        | двадцять дві тисячі п'ятсот гривень 00 копійок
            1234.56         | одна тисяча двісті тридцять чотири гривні 56 копійок
            100000.00       | сто тисяч гривень 00 копійок
            1000000.00      | один мільйон гривень 00 копійок
            1002000.00      | один мільйон дві тисячі гривень 00 копійок
            2000000.00      | два мільйони гривень 00 копійок
            5000000.00      | п'ять мільйонів гривень 00 копійок
            11000000.00     | одинадцять мільйонів гривень 00 копійок
            21000001.00     | двадцять один мільйон одна гривня 00 копійок
            1000000000.00   | один мільярд гривень 00 копійок
            2000001000.00   | два мільярди одна тисяча гривень 00 копійок
            12000000000.00  | дванадцять мільярдів гривень 00 копійок
            999999999999.99 | дев'ятсот дев'яносто дев'ять мільярдів дев'ятсот дев'яносто дев'ять мільйонів дев'ятсот дев'яносто дев'ять тисяч дев'ятсот дев'яносто дев'ять гривень 99 копійок
            """)
    void writesTheAmountInWords(String amount, String words) {
        assertThat(AmountInWords.of(Money.parse(amount))).isEqualTo(words);
    }

    @Test
    void aNegativeAmountIsRefused() {
        assertThatThrownBy(() -> AmountInWords.of(Money.parse("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAmountAboveTheRangeIsRefused() {
        assertThatThrownBy(() -> AmountInWords.of(Money.parse("1000000000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0, many", "1, one", "2, few", "4, few", "5, many", "11, many", "12, many", "14, many", "21, one",
        "22, few", "111, many", "101, one", "1000, many"})
    void theFormAgreesWithTheNumber(long number, String form) {
        assertThat(AmountInWords.form(number)).isEqualTo(form);
    }
}
