package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Reading cells: text, yes/no, dates, numbers and the two area columns. */
class CellsTests {

    private static RawCell text(String value) {
        return new RawCell.Text(value);
    }

    private static RawCell number(String value) {
        return new RawCell.Number(new BigDecimal(value), false);
    }

    private static RawCell dateNumber(String serial) {
        return new RawCell.Number(new BigDecimal(serial), true);
    }

    // ---- text

    @Test
    void textIsTrimmedAndBlankIsEmpty() {
        assertThat(Cells.text(text("  Garden street ")).value()).isEqualTo("Garden street");
        assertThat(Cells.text(text("   ")).value()).isNull();
        assertThat(Cells.text(null).value()).isNull();
    }

    @Test
    void aNumberInATextColumnIsItsPlainText() {
        assertThat(Cells.text(number("12")).value()).isEqualTo("12");
        assertThat(Cells.text(number("1E+3")).value()).isEqualTo("1000");
        assertThat(Cells.text(new RawCell.Bool(true)).value()).isEqualTo("true");
    }

    // ---- yes / no

    @ParameterizedTest
    @ValueSource(strings = {"yes", "YES", "Yes", "true", "TRUE", "так", "Так", "ТАК"})
    void yesVariants(String value) {
        assertThat(Cells.yesNo(text(value)).value()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "NO", "false", "False", "ні", "Ні", "НІ"})
    void noVariants(String value) {
        assertThat(Cells.yesNo(text(value)).value()).isFalse();
    }

    @Test
    void aBooleanCellIsTakenAsItIs() {
        assertThat(Cells.yesNo(new RawCell.Bool(true)).value()).isTrue();
        assertThat(Cells.yesNo(new RawCell.Bool(false)).value()).isFalse();
    }

    @Test
    void anythingElseIsNotYesOrNo() {
        assertThat(Cells.yesNo(text("y")).problem()).isEqualTo("YES_NO_INVALID");
        assertThat(Cells.yesNo(text("maybe")).problem()).isEqualTo("YES_NO_INVALID");
        assertThat(Cells.yesNo(number("1")).problem()).isEqualTo("YES_NO_INVALID");
        assertThat(Cells.yesNo(text("  ")).value()).isNull();
        assertThat(Cells.yesNo(null).problem()).isNull();
    }

    // ---- dates

    @ParameterizedTest
    @CsvSource({"43891, 2020-03-01", "61, 1900-03-01", "45000.75, 2023-03-15", "2958465, 9999-12-31"})
    void anExcelDateIsASerialNumberInADateCell(String serial, String expected) {
        assertThat(Cells.date(dateNumber(serial), false).value()).isEqualTo(LocalDate.parse(expected));
    }

    @Test
    void theOldMacDateSystemCountsFrom1904() {
        assertThat(Cells.date(dateNumber("0"), true).value()).isEqualTo(LocalDate.of(1904, 1, 1));
        assertThat(Cells.date(dateNumber("42"), true).value()).isEqualTo(LocalDate.of(1904, 2, 12));
    }

    @ParameterizedTest
    @CsvSource({"01.03.2020, 2020-03-01", "1.3.2020, 2020-03-01", "31.12.1999, 1999-12-31", " 29.02.2020 , 2020-02-29"})
    void dottedDates(String value, String expected) {
        assertThat(Cells.date(text(value), false).value()).isEqualTo(LocalDate.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31.04.2020", "29.02.2021", "2020-03-01", "01/03/2020", "1.3.20", "tomorrow", "00.01.2020"})
    void otherTextIsNotADate(String value) {
        assertThat(Cells.date(text(value), false).problem()).isEqualTo("DATE_INVALID");
    }

    @Test
    void aNumberThatIsNotInADateCellIsNotADate() {
        assertThat(Cells.date(number("43891"), false).problem()).isEqualTo("DATE_INVALID");
    }

    @Test
    void impossibleSerialsAndBooleansAreNotDates() {
        assertThat(Cells.date(dateNumber("-1"), false).problem()).isEqualTo("DATE_INVALID");
        assertThat(Cells.date(dateNumber("30"), false).problem()).isEqualTo("DATE_INVALID");
        assertThat(Cells.date(dateNumber("2958466"), false).problem()).isEqualTo("DATE_INVALID");
        assertThat(Cells.date(new RawCell.Bool(true), false).problem()).isEqualTo("DATE_INVALID");
    }

    @Test
    void anEmptyDateCellIsEmpty() {
        assertThat(Cells.date(null, false).value()).isNull();
        assertThat(Cells.date(null, false).problem()).isNull();
        assertThat(Cells.date(text(" "), false).value()).isNull();
    }

    // ---- numbers

    @Test
    void excelFloatingPointNoiseIsRemovedFromNumberCells() {
        // Excel stores 0.06 as 0.059999999999999998
        assertThat(Cells.number(number("0.059999999999999998")).value()).isEqualByComparingTo("0.06");
        assertThat(Cells.number(number("0.059999999999999998")).value()).isEqualTo(new BigDecimal("0.06"));
        assertThat(Cells.number(number("600.00000000000011")).value()).isEqualTo(new BigDecimal("600"));
        assertThat(Cells.number(number("1E+2")).value().toPlainString()).isEqualTo("100");
        assertThat(Cells.number(number("0.1")).value()).isEqualTo(new BigDecimal("0.1"));
    }

    @ParameterizedTest
    @CsvSource({"600, 600", "600.5, 600.5", "'600,5', 600.5", "' 1 200,25 ', 1200.25", "-3, -3", ".5, 0.5"})
    void textNumbersAcceptACommaAndSpaces(String value, String expected) {
        assertThat(Cells.number(text(value)).value()).isEqualByComparingTo(expected);
    }

    @Test
    void textNumbersAreNotRounded() {
        assertThat(Cells.number(text("0.059999999999999998")).value()).isEqualTo(new BigDecimal("0.059999999999999998"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.2.3", "1,234.5", "12m", "1e3", "--1", "1,2,3", "∞"})
    void textThatIsNotANumber(String value) {
        assertThat(Cells.number(text(value)).problem()).isEqualTo("NUMBER_INVALID");
    }

    @Test
    void aBooleanIsNotANumberAndEmptyIsEmpty() {
        assertThat(Cells.number(new RawCell.Bool(true)).problem()).isEqualTo("NUMBER_INVALID");
        assertThat(Cells.number(null).value()).isNull();
        assertThat(Cells.number(text("")).problem()).isNull();
    }

    // ---- area

    @Test
    void squareMetresAreTakenAsTheyAre() {
        var result = Cells.area(number("600"), null);

        assertThat(result.problem()).isNull();
        assertThat(result.area().squareMetres()).isEqualByComparingTo("600");
        assertThat(result.sotkyColumn()).isFalse();
    }

    @Test
    void sotkyAreMultipliedByOneHundredExactly() {
        assertThat(Cells.area(null, number("6")).area().squareMetres()).isEqualByComparingTo("600");
        assertThat(Cells.area(null, text("6,5")).area().squareMetres()).isEqualByComparingTo("650");
        assertThat(Cells.area(null, text("0.055")).area().squareMetres()).isEqualByComparingTo("5.5");
        assertThat(Cells.area(null, text("0.0123")).area().squareMetres().toPlainString()).isEqualTo("1.23");
        assertThat(Cells.area(null, number("6")).sotkyColumn()).isTrue();
    }

    @Test
    void bothAreasOrNoneAreNotTakenAsAnArea() {
        var both = Cells.area(number("600"), number("6"));
        assertThat(both.problem()).isEqualTo("AREA_BOTH_OR_NONE");
        assertThat(both.area()).isNull();

        var none = Cells.area(null, text(" "));
        assertThat(none.problem()).isNull();
        assertThat(none.area()).isNull();
    }

    @Test
    void anUnreadableAreaNamesItsColumn() {
        var m2 = Cells.area(text("big"), null);
        assertThat(m2.problem()).isEqualTo("NUMBER_INVALID");
        assertThat(m2.sotkyColumn()).isFalse();

        var sotky = Cells.area(null, text("many"));
        assertThat(sotky.problem()).isEqualTo("NUMBER_INVALID");
        assertThat(sotky.sotkyColumn()).isTrue();
    }
}
