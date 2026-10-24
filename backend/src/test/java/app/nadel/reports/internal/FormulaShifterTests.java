package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The A1 reference shifter of the template filler (ADR 0015): the repeating row 5 of {@code Sheet1} replaced by its
 * copies. Formulas are written as the file stores them, without the leading {@code =}. Values are separated by
 * {@code |} and quoted with backticks, so commas, quotes and {@code #} stay part of the formula.
 */
class FormulaShifterTests {

    private static final String SHEET = "Sheet1";
    private static final int ROW = 5;

    /** Three copies: rows 5, 6 and 7; everything below moves down by two. */
    private final FormulaShifter threeCopies = new FormulaShifter(SHEET, ROW, 3);

    @Test
    void aSumOverTheRepeatingRowGrowsToTheLastCopy() {
        assertThat(threeCopies.shift("SUM(D5:D5)", SHEET)).isEqualTo("SUM(D5:D7)");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "A5            | A5",
        "A4            | A4",
        "A1            | A1",
        "A6            | A8",
        "A9            | A11",
        "D6*2+D4       | D8*2+D4",
        "A6 B6         | A8 B8",
        "A6^2          | A8^2",
        "@A6           | @A8",
    })
    void singleCellsBelowMoveAndTheRestStays(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "$A$6          | $A$8",
        "A$9           | A$11",
        "$B6           | $B8",
        "$A$5          | $A$5",
        "$A$4          | $A$4",
        "SUM($D$4:$D$5) | SUM($D$4:$D$7)",
        "$5:$5         | $5:$7",
    })
    void absoluteRowsMoveTooBecauseRowsAreInserted(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "SUM(D4:D5)    | SUM(D4:D7)",
        "SUM(D5:D9)    | SUM(D5:D11)",
        "SUM(D1:D9)    | SUM(D1:D11)",
        "SUM(D6:D9)    | SUM(D8:D11)",
        "SUM(D1:D4)    | SUM(D1:D4)",
        "SUM(B5:F5)    | SUM(B5:F7)",
        "SUM(D9:D5)    | SUM(D11:D5)",
        "SUM(D5:D1)    | SUM(D7:D1)",
        "SUM(5:5)      | SUM(5:7)",
        "6:7           | 8:9",
        "1:4           | 1:4",
        "4:5           | 4:7",
    })
    void rangesGrowWhenTheyEndOnTheRepeatingRowOrBelowAndMoveWhenBelow(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "SUM(A:A)",
        "$B:$D",
        "_xlfn.XLOOKUP(X1,B:B,C:C)",
        "A:XFD",
    })
    void wholeColumnsNeverChange(String formula) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(formula);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`IF(A6>0,\"A6\",A6)`              | `IF(A8>0,\"A6\",A8)`",
        "`\"say \"\"A6\"\"\"&A6`            | `\"say \"\"A6\"\"\"&A8`",
        "`\"\"&A6`                          | `\"\"&A8`",
        "`\"never closed A6`                | `\"never closed A6`",
    })
    void stringLiteralsAreNeverRead(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`#REF!+A6`             | `#REF!+A8`",
        "`IFERROR(A6,#N/A)`     | `IFERROR(A8,#N/A)`",
        "`IFERROR(A6,#DIV/0!)`  | `IFERROR(A8,#DIV/0!)`",
        "`#NAME?&A6`            | `#NAME?&A8`",
        "`#GETTING_DATA`        | `#GETTING_DATA`",
        "`Sheet1!#REF!`         | `Sheet1!#REF!`",
    })
    void errorLiteralsAreLeftAsWritten(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "LOG10(A6)            | LOG10(A8)",
        "ATAN2(A6,B6)         | ATAN2(A8,B8)",
        "DAYS360(A6,B6)       | DAYS360(A8,B8)",
        "_xlfn.STDEV.S(A6:A9) | _xlfn.STDEV.S(A8:A11)",
        "A1B+A6               | A1B+A8",
        "XFE6+XFD6            | XFE6+XFD8",
        "R6C3                 | R6C3",
        "MyName+Rate2024      | MyName+Rate2024",
        "TRUE                 | TRUE",
        "A1048577             | A1048577",
        "A10485770            | A10485770",
        "A0                   | A0",
        "0:7                  | 0:7",
        "LOG10+A6             | LOG12+A8",
        "d6                   | D8",
    })
    void functionNamesAndNamesThatOnlyLookLikeReferencesAreLeft(String formula, String expected) {
        // LOG10 without a parenthesis is the cell in column LOG, row 10: Excel reads it so and moves it
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "1.5E+6*A6       | 1.5E+6*A8",
        "1E5+A6          | 1E5+A8",
        ".5*A6           | .5*A8",
        "`{1,2;3,4}`     | `{1,2;3,4}`",
        "A5:7            | A5:7",
        "A6:9            | A8:9",
        "6               | 6",
        "$               | $",
        "`=A6`           | `=A8`",
        "``              | ``",
        "ABC+A6          | ABC+A8",
        "`\\Name+A6`     | `\\Name+A8`",
        "1048577:1048577 | 1048577:1048577",
        "XFE:XFE         | XFE:XFE",
        "A6:9:B          | A8:9:B",
        "A6:LOG10(B1)    | A8:LOG10(B1)",
        "Sheet1!A6:B7!x  | Sheet1!A8:B7!x",
        "Sheet1!A6:B7[x] | Sheet1!A8:B7[x]",
    })
    void numbersAndMixedRangesAreNotRanges(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "Sheet1!A6                  | Sheet1!A8",
        "sheet1!A6                  | sheet1!A8",
        "SHEET1!A5:B5               | SHEET1!A5:B7",
        "Sheet1!$A$6:$B$9           | Sheet1!$A$8:$B$11",
        "Sheet1!5:5                 | Sheet1!5:7",
        "`'Sheet1'!A6`              | `'Sheet1'!A8`",
        "Sheet2!A6+A6               | Sheet2!A6+A8",
        "Sheet2!A5:A5               | Sheet2!A5:A5",
        "Sheet1!MyName              | Sheet1!MyName",
        "Sheet1!A6:Sheet1!B7        | Sheet1!A8:Sheet1!B9",
        "Sheet1:Sheet3!A6           | Sheet1:Sheet3!A6",
        "`'Sheet1:Sheet3'!A6`       | `'Sheet1:Sheet3'!A6`",
        "Аркуш1!A6                  | Аркуш1!A6",
    })
    void onlyReferencesToTheRepeatedSheetMove(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{1} on {0}: {2} -> {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "My Sheet | `'My Sheet'!A5:B9`      | `'My Sheet'!A5:B11`",
        "My Sheet | `'my sheet'!A6`         | `'my sheet'!A8`",
        "My Sheet | `'Other'!A6`            | `'Other'!A6`",
        "My Sheet | `'My Sheet'!A6+A6`      | `'My Sheet'!A8+A6`",
        "It's     | `'It''s'!A6`            | `'It''s'!A8`",
        "It's     | `'Its'!A6`              | `'Its'!A6`",
        "Аркуш 1  | `'Аркуш 1'!A6`          | `'Аркуш 1'!A8`",
        "Аркуш1   | `Аркуш1!A6`             | `Аркуш1!A8`",
        "Аркуш1   | `аркуш1!A6`             | `аркуш1!A8`",
    })
    void quotedAndNonLatinSheetNames(String sheet, String formula, String expected) {
        // the formula lives on another sheet, so only its qualified references can point at the repeated one
        assertThat(new FormulaShifter(sheet, ROW, 3).shift(formula, "Elsewhere")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`'never closed!A6` | `'never closed!A6`",
        "`'Sheet1'A6`       | `'Sheet1'A8`",
        "`'Sheet1'`         | `'Sheet1'`",
    })
    void aQuoteThatIsNotASheetQualifierIsLeft(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "`Table1[A6]+A6`                       | `Table1[A6]+A8`",
        "`SUM(Table1[[#This Row],[A6]])+A6`    | `SUM(Table1[[#This Row],[A6]])+A8`",
        "`Table1[Col'[A6]+A6`                  | `Table1[Col'[A6]+A8`",
        "`[@A6]*A6`                            | `[@A6]*A8`",
        "`[1]Sheet1!A6+A6`                     | `[1]Sheet1!A6+A8`",
        "`[1]Sheet1!A6:B7`                     | `[1]Sheet1!A6:B7`",
        "`'[1]Sheet1'!A6`                      | `'[1]Sheet1'!A6`",
        "`[1]!Rate+A6`                         | `[1]!Rate+A8`",
        "`[never closed A6`                    | `[never closed A6`",
        "`[escape at the end'`                 | `[escape at the end'`",
    })
    void structuredAndExternalReferencesAreLeft(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @Test
    void unqualifiedReferencesMoveOnlyOnTheRepeatedSheet() {
        assertThat(threeCopies.shift("A6+Sheet1!A6", "Sheet2")).isEqualTo("A6+Sheet1!A8");
        assertThat(threeCopies.shift("A6", "SHEET1")).isEqualTo("A8");
    }

    @Test
    void aDefinedNameMovesOnlyItsQualifiedReferences() {
        assertThat(threeCopies.shift("Sheet1!$A$1:$F$5", null)).isEqualTo("Sheet1!$A$1:$F$7");
        assertThat(threeCopies.shift("Sheet1!$4:$4", null)).isEqualTo("Sheet1!$4:$4");
        assertThat(threeCopies.shift("Sheet1!$A$1:$F$5,Sheet1!$H$9", null))
                .isEqualTo("Sheet1!$A$1:$F$7,Sheet1!$H$11");
        assertThat(threeCopies.shift("A6", null)).isEqualTo("A6");
    }

    @Test
    void referenceListsAreShiftedLikeFormulas() {
        // sqref of conditional formatting and data validation, the dimension, an auto filter
        assertThat(threeCopies.shift("A5:C5 E7 A1", SHEET)).isEqualTo("A5:C7 E9 A1");
        assertThat(threeCopies.shift("A1:F9", SHEET)).isEqualTo("A1:F11");
        assertThat(threeCopies.shift("A4:F5", SHEET)).isEqualTo("A4:F7");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "A5                  | `#REF!`",
        "$A$5                | `#REF!`",
        "Sheet1!A5           | `Sheet1!#REF!`",
        "`'Sheet1'!A5+1`     | `'Sheet1'!#REF!+1`",
        "SUM(D5:D5)          | `SUM(#REF!)`",
        "SUM(5:5)            | `SUM(#REF!)`",
        "SUM(D4:D6)          | SUM(D4:D5)",
        "SUM(D5:D6)          | SUM(D5:D5)",
        "SUM(D4:D5)          | SUM(D4:D4)",
        "SUM(D6:D9)          | SUM(D5:D8)",
        "A6                  | A5",
        "$A$7                | $A$6",
        "A4                  | A4",
        "SUM(A:A)            | SUM(A:A)",
        "Sheet2!A5           | Sheet2!A5",
    })
    void anEmptyRowSetRemovesTheRowAndReferencesIntoItBecomeRefErrors(String formula, String expected) {
        assertThat(new FormulaShifter(SHEET, ROW, 0).shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "SUM(D5:D5)",
        "A6+$A$9",
        "SUM(D4:D9)",
        "5:5",
    })
    void oneCopyChangesNothing(String formula) {
        assertThat(new FormulaShifter(SHEET, ROW, 1).shift(formula, SHEET)).isEqualTo(formula);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "A1048576                 | `#REF!`",
        "A1048574                 | A1048576",
        "SUM(A1048575:A1048576)   | `SUM(#REF!)`",
        "SUM(A1:A1048576)         | `SUM(#REF!)`",
    })
    void aReferenceMovedPastTheLastRowBecomesARefError(String formula, String expected) {
        assertThat(threeCopies.shift(formula, SHEET)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "copy {1}: {0} -> {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "D5*E5               | 0 | D5*E5",
        "D5*E5               | 1 | D6*E6",
        "D5*E5               | 2 | D7*E7",
        "SUM(D$5:D5)         | 2 | SUM(D$5:D7)",
        "$D$5                | 2 | $D$5",
        "D$5                 | 2 | D$5",
        "E4+D5               | 1 | E5+D6",
        "D5/D$10             | 1 | D6/D$12",
        "D5/D10              | 1 | D6/D13",
        "Sheet1!D10          | 1 | Sheet1!D13",
        "Sheet2!B5           | 2 | Sheet2!B7",
        "Sheet2!B10          | 2 | Sheet2!B12",
        "Sheet2!$B$5         | 2 | Sheet2!$B$5",
        "SUM(A:A)            | 2 | SUM(A:A)",
        "5:5                 | 1 | 6:6",
        "`\"D5\"&D5`         | 1 | `\"D5\"&D6`",
        "`IFERROR(D5,#N/A)`  | 2 | `IFERROR(D7,#N/A)`",
        "SUM(D$5:D$5)        | 2 | SUM(D$5:D$5)",
    })
    void aCopyMovesItsRelativeRowsLikeFillDown(String formula, int copyIndex, String expected) {
        assertThat(threeCopies.copy(formula, copyIndex)).isEqualTo(expected);
    }

    @Test
    void aCopyPastTheLastRowIsARefError() {
        FormulaShifter lastRow = new FormulaShifter(SHEET, FormulaShifter.MAX_ROW, 2);
        assertThat(lastRow.copy("A1048576*2", 0)).isEqualTo("A1048576*2");
        assertThat(lastRow.copy("A1048576*2", 1)).isEqualTo("#REF!*2");
    }

    @Test
    void onlyExistingCopiesCanBeWritten() {
        assertThatThrownBy(() -> threeCopies.copy("A5", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> threeCopies.copy("A5", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormulaShifter(SHEET, ROW, 0).copy("A5", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} by ({1}, {2}) -> {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "A1*B1              | 1  | 0  | A2*B2",
        "$A$1+A$1+$A1       | 3  | 2  | $A$1+C$1+$A4",
        "SUM(A1:B2)         | 0  | 1  | SUM(B1:C2)",
        "A:A                | 5  | 1  | B:B",
        "1:1                | 2  | 0  | 3:3",
        "Sheet2!A1          | 1  | 0  | Sheet2!A2",
        "`'My Sheet'!A1`    | 1  | 0  | `'My Sheet'!A2`",
        "`\"A1\"&A1`        | 1  | 0  | `\"A1\"&A2`",
        "Z1                 | 0  | 1  | AA1",
        "AZ1                | 0  | 1  | BA1",
        "ZZ1                | 0  | 1  | AAA1",
        "XFC1               | 0  | 1  | XFD1",
        "A1                 | 0  | 0  | A1",
        "A1                 | -1 | 0  | `#REF!`",
        "A1                 | 0  | -1 | `#REF!`",
        "XFD1               | 0  | 1  | `#REF!`",
        "A1048576           | 1  | 0  | `#REF!`",
        "SUM(A2:B3)         | -2 | 0  | `SUM(#REF!)`",
        "SUM(A1:B1048576)   | 1  | 0  | `SUM(#REF!)`",
    })
    void offsetPastesAFormulaElsewhere(String formula, int rows, int columns, String expected) {
        assertThat(FormulaShifter.offset(formula, rows, columns)).isEqualTo(expected);
    }

    @Test
    void offsetNeverOverflows() {
        assertThat(FormulaShifter.offset("A1", Integer.MAX_VALUE, 0)).isEqualTo("#REF!");
        assertThat(FormulaShifter.offset("A1", 0, Integer.MIN_VALUE)).isEqualTo("#REF!");
    }

    @Test
    void theRepeatingRowMustBeOnTheSheet() {
        assertThatThrownBy(() -> new FormulaShifter("", ROW, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormulaShifter(SHEET, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormulaShifter(SHEET, FormulaShifter.MAX_ROW + 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormulaShifter(SHEET, ROW, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormulaShifter(SHEET, ROW, FormulaShifter.MAX_ROW + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
