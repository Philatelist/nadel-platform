package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * How the documents write amounts, days, months and service periods (feature 016 §2): no-break spaces between the
 * thousands and before the hryvnia sign, a decimal comma, two decimals; days in Kyiv; month names from the bundle.
 */
class DocumentFormatTests {

    private static final String NBSP = " ";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            0.00         | 0,00
            0.05         | 0,05
            5.00         | 5,00
            999.99       | 999,99
            1000.00      | 1_000,00
            1234.56      | 1_234,56
            413.26       | 413,26
            100000.00    | 100_000,00
            1234567.89   | 1_234_567,89
            -1234.50     | -1_234,50
            -5.00        | -5,00
            """)
    void writesAnAmountWithNoBreakSpacesAndADecimalComma(String amount, String written) {
        assertThat(DocumentFormat.amount(Money.parse(amount))).isEqualTo(written.replace("_", NBSP));
    }

    @Test
    void writesMoneyWithTheHryvniaSignAfterANoBreakSpace() {
        assertThat(DocumentFormat.money(Money.parse("1234.56"))).isEqualTo("1" + NBSP + "234,56" + NBSP + "₴");
    }

    @Test
    void writesADayAsDayMonthYear() {
        assertThat(DocumentFormat.day(LocalDate.of(2026, 11, 5))).isEqualTo("05.11.2026");
    }

    @Test
    void theDayOfAnInstantIsTheDayInKyiv() {
        // 22:30 UTC on 31 October is 00:30 on 1 November in Kyiv (UTC+2 in winter)
        Instant instant = Instant.parse("2026-10-31T22:30:00Z");

        assertThat(DocumentFormat.dayOf(instant)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(DocumentFormat.day(instant)).isEqualTo("01.11.2026");
        // in summer Kyiv is UTC+3
        assertThat(DocumentFormat.day(Instant.parse("2026-06-30T21:00:00Z"))).isEqualTo("01.07.2026");
    }

    @Test
    void namesTheMonthsInTheNominativeAndTheGenitive() {
        assertThat(DocumentFormat.month(YearMonth.of(2026, 10))).isEqualTo("жовтень 2026");
        assertThat(DocumentFormat.month(YearMonth.of(2027, 1))).isEqualTo("січень 2027");
        assertThat(DocumentFormat.monthGenitive(YearMonth.of(2026, 11))).isEqualTo("листопада");
        assertThat(DocumentFormat.monthGenitive(YearMonth.of(2026, 3))).isEqualTo("березня");
        Set<String> nominative = new HashSet<>();
        Set<String> genitive = new HashSet<>();
        for (Month month : Month.values()) {
            nominative.add(DocumentFormat.month(YearMonth.of(2026, month)));
            genitive.add(DocumentFormat.monthGenitive(YearMonth.of(2026, month)));
        }
        assertThat(nominative).hasSize(12);
        assertThat(genitive).hasSize(12).doesNotContainAnyElementsOf(nominative);
    }

    @Test
    void aServicePeriodOfOneWholeMonthIsTheMonth() {
        assertThat(DocumentFormat.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEqualTo("жовтень 2026");
        assertThat(DocumentFormat.period(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)))
                .isEqualTo("лютий 2028");
    }

    @Test
    void anyOtherServicePeriodIsItsDays() {
        assertThat(DocumentFormat.period(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 4)))
                .isEqualTo("05.09.2026 – 04.10.2026");
        assertThat(DocumentFormat.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30)))
                .isEqualTo("01.10.2026 – 30.10.2026");
        assertThat(DocumentFormat.period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 31)))
                .isEqualTo("02.10.2026 – 31.10.2026");
        assertThat(DocumentFormat.period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)))
                .isEqualTo("01.01.2026 – 28.02.2026");
        assertThat(DocumentFormat.period(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 15)))
                .isEqualTo("15.07.2026");
    }
}
