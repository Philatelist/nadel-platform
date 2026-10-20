package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Header matching: canonical names, case, order, Ukrainian aliases, unknown columns, missing required columns. */
class HeaderMatcherTests {

    private static HeaderMatcher.Match<PlotColumn> match(Object... headers) {
        List<@Nullable RawCell> cells = new ArrayList<>();
        for (Object header : headers) {
            cells.add(switch (header) {
                case null -> null;
                case String text -> new RawCell.Text(text);
                case Boolean flag -> new RawCell.Bool(flag);
                case Number number -> new RawCell.Number(new java.math.BigDecimal(number.toString()), false);
                default -> throw new IllegalArgumentException();
            });
        }
        return HeaderMatcher.match("plots", PlotColumn.class, new RawRow(1, cells));
    }

    @Test
    void theTemplateHeadersAreMatchedByPosition() {
        var match = match("number", "street", "kind", "area_m2", "area_sotky", "cadastral_number", "has_building",
                "notes");

        assertThat(match.indexes()).containsEntry(PlotColumn.NUMBER, 0).containsEntry(PlotColumn.STREET, 1)
                .containsEntry(PlotColumn.KIND, 2).containsEntry(PlotColumn.AREA_M2, 3)
                .containsEntry(PlotColumn.AREA_SOTKY, 4).containsEntry(PlotColumn.CADASTRAL_NUMBER, 5)
                .containsEntry(PlotColumn.HAS_BUILDING, 6).containsEntry(PlotColumn.NOTES, 7);
        assertThat(match.missing()).isEmpty();
    }

    @Test
    void lettersCaseAndSeparatorsDoNotMatter() {
        var match = match("  NUMBER ", "Area_M2", "Cadastral Number", "has-building");

        assertThat(match.indexes()).containsEntry(PlotColumn.NUMBER, 0).containsEntry(PlotColumn.AREA_M2, 1)
                .containsEntry(PlotColumn.CADASTRAL_NUMBER, 2).containsEntry(PlotColumn.HAS_BUILDING, 3);
    }

    @Test
    void columnsMayComeInAnyOrder() {
        var match = match("notes", "kind", "number");

        assertThat(match.indexes()).containsEntry(PlotColumn.NOTES, 0).containsEntry(PlotColumn.KIND, 1)
                .containsEntry(PlotColumn.NUMBER, 2);
        assertThat(match.has(PlotColumn.STREET)).isFalse();
    }

    @Test
    void ukrainianHeadersAreAcceptedInAnyCaseAndOrder() {
        var match = match("ПЛОЩА, СОТ.", "вулиця", "Номер", "Кадастровий номер", "Тип", "Примітки", "Є будівля");

        assertThat(match.indexes()).containsEntry(PlotColumn.AREA_SOTKY, 0).containsEntry(PlotColumn.STREET, 1)
                .containsEntry(PlotColumn.NUMBER, 2).containsEntry(PlotColumn.CADASTRAL_NUMBER, 3)
                .containsEntry(PlotColumn.KIND, 4).containsEntry(PlotColumn.NOTES, 5)
                .containsEntry(PlotColumn.HAS_BUILDING, 6);
        assertThat(match.missing()).isEmpty();
    }

    @Test
    void theSquareMetresHeaderIsTheOneWithTheSquaredSign() {
        // the superscript two is not a letter or a digit, so it drops out in the header and in the alias alike
        assertThat(match("Номер", "Площа, м²").indexes()).containsEntry(PlotColumn.AREA_M2, 1);
        assertThat(match("Номер", "Площа, м2").indexes()).containsEntry(PlotColumn.AREA_M2, 1);
    }

    @Test
    void unknownColumnsAreIgnoredAndEmptyHeaderCellsAreSkipped() {
        var match = match("Remarks", null, "number", 2024, true, "street");

        assertThat(match.indexes()).containsOnlyKeys(PlotColumn.NUMBER, PlotColumn.STREET);
        assertThat(match.indexes()).containsEntry(PlotColumn.NUMBER, 2).containsEntry(PlotColumn.STREET, 5);
    }

    @Test
    void aMissingRequiredHeaderIsReported() {
        var match = match("street", "area_m2");

        assertThat(match.missing()).containsExactly(PlotColumn.NUMBER);
    }

    @Test
    void whenTwoHeadersMatchTheSameColumnTheFirstWins() {
        var match = match("Номер", "number");

        assertThat(match.indexes()).containsEntry(PlotColumn.NUMBER, 0);
    }

    @Test
    void theCellOfAColumnIsLookedUpInARow() {
        var match = match("street", "number");
        RawRow row = new RawRow(2, java.util.Arrays.asList(new RawCell.Text("Garden"), new RawCell.Text("5")));

        assertThat(match.cell(row, PlotColumn.NUMBER)).isEqualTo(new RawCell.Text("5"));
        assertThat(match.cell(row, PlotColumn.NOTES)).isNull();
    }

    @Test
    void normalisationKeepsLowerCaseLettersAndDigitsOnly() {
        assertThat(HeaderMatcher.normalise("Площа, сот.")).isEqualTo("площасот");
        assertThat(HeaderMatcher.normalise("Area (m2)")).isEqualTo("aream2");
        assertThat(HeaderMatcher.normalise(null)).isEmpty();
    }
}
