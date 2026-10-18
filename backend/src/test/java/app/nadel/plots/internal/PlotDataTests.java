package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import java.math.BigDecimal;
import java.util.List;
import nadel.contract.model.PlotInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class PlotDataTests {

    private static PlotInput plot(String number, nadel.contract.model.PlotKind kind, String area) {
        return new PlotInput(number, kind, area == null ? null : new BigDecimal(area));
    }

    private static PlotInput validPlot() {
        return plot("12", nadel.contract.model.PlotKind.PLOT, "600");
    }

    private static List<FieldProblem> errorsOf(PlotInput input) {
        var thrown = catchThrowableOfType(ApiProblemException.class,
                () -> PlotData.from(input));
        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.code()).isEqualTo("VALIDATION_FAILED");
        return thrown.errors();
    }

    // ---- normalisation

    @Test
    void trimsTextAndTurnsBlankOptionalTextIntoNull() {
        PlotInput input = validPlot();
        input.setNumber("  12а ");
        input.setStreet("  Garden St ");
        input.setNotes("   ");
        input.setCadastralNumber("  ");

        PlotData data = PlotData.from(input);

        assertThat(data.number()).isEqualTo("12а");
        assertThat(data.street()).isEqualTo("Garden St");
        assertThat(data.notes()).isNull();
        assertThat(data.cadastralNumber()).isNull();
        assertThat(data.kind()).isEqualTo(PlotKind.PLOT);
        assertThat(data.hasBuilding()).isFalse();
    }

    @Test
    void keepsTheCadastralNumberAndTheBuildingFlag() {
        PlotInput input = validPlot();
        input.setCadastralNumber(" 4810136300:02:001:0001 ");
        input.setHasBuilding(true);
        input.setNotes("  corner plot ");

        PlotData data = PlotData.from(input);

        assertThat(data.cadastralNumber()).isEqualTo("4810136300:02:001:0001");
        assertThat(data.hasBuilding()).isTrue();
        assertThat(data.notes()).isEqualTo("corner plot");
    }

    @Test
    void anEmptyStreetIsAbsent() {
        PlotInput input = validPlot();
        input.setStreet("");

        assertThat(PlotData.from(input).street()).isNull();
    }

    // ---- area

    @ParameterizedTest
    @ValueSource(strings = {"600", "600.0", "600.00", "6.5", "0.01", "99999999.99", "6E+2", "600.500"})
    void acceptsAnAreaWithAtMostTwoDecimalsAndStoresItWithTwo(String area) {
        PlotData data = PlotData.from(plot("1", nadel.contract.model.PlotKind.PLOT, area));

        assertThat(data.areaM2()).isEqualByComparingTo(area);
        assertThat(data.areaM2().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "6.001", "600.123", "100000000", "100000000.00", "1E+1000000",
            "1E-1000000"})
    void rejectsAnInvalidAreaOfAPlot(String area) {
        assertThat(errorsOf(plot("1", nadel.contract.model.PlotKind.PLOT, area)))
                .containsExactly(new FieldProblem("areaM2", "AREA_INVALID"));
    }

    @Test
    void theAreaIsNeverRounded() {
        assertThat(errorsOf(plot("1", nadel.contract.model.PlotKind.PLOT, "100.005")))
                .containsExactly(new FieldProblem("areaM2", "AREA_INVALID"));
    }

    @Test
    void commonLandMayHaveAreaZeroButNotNegative() {
        PlotData zero = PlotData.from(plot("R-1", nadel.contract.model.PlotKind.COMMON, "0"));

        assertThat(zero.areaM2()).isEqualByComparingTo("0");
        assertThat(zero.areaM2().scale()).isEqualTo(2);
        assertThat(errorsOf(plot("R-1", nadel.contract.model.PlotKind.COMMON, "-0.01")))
                .containsExactly(new FieldProblem("areaM2", "AREA_INVALID"));
    }

    // ---- required and length

    @Test
    void reportsMissingFieldsAsRequired() {
        PlotInput input = new PlotInput();

        assertThat(errorsOf(input)).containsExactly(
                new FieldProblem("number", "REQUIRED"),
                new FieldProblem("kind", "REQUIRED"),
                new FieldProblem("areaM2", "REQUIRED"));
    }

    @Test
    void aBlankNumberIsRequired() {
        assertThat(errorsOf(plot("   ", nadel.contract.model.PlotKind.PLOT, "600")))
                .containsExactly(new FieldProblem("number", "REQUIRED"));
    }

    @Test
    void reportsTooLongTextFields() {
        PlotInput input = plot("1".repeat(21), nadel.contract.model.PlotKind.PLOT, "600");
        input.setStreet("s".repeat(101));
        input.setNotes("n".repeat(2001));

        assertThat(errorsOf(input)).containsExactly(
                new FieldProblem("number", "TOO_LONG"),
                new FieldProblem("street", "TOO_LONG"),
                new FieldProblem("notes", "TOO_LONG"));
    }

    @Test
    void acceptsTextFieldsAtTheirLimit() {
        PlotInput input = plot("1".repeat(20), nadel.contract.model.PlotKind.PLOT, "600");
        input.setStreet("s".repeat(100));
        input.setNotes("n".repeat(2000));

        assertThat(PlotData.from(input).number()).hasSize(20);
    }

    // ---- cadastral and several errors at once

    @Test
    void rejectsAWrongCadastralFormat() {
        PlotInput input = validPlot();
        input.setCadastralNumber("123:45");

        assertThat(errorsOf(input)).containsExactly(new FieldProblem("cadastralNumber", "CADASTRAL_INVALID"));
    }

    @Test
    void reportsEveryInvalidFieldInOneProblem() {
        PlotInput input = plot(" ", nadel.contract.model.PlotKind.PLOT, "0");
        input.setCadastralNumber("bad");

        assertThat(errorsOf(input)).containsExactly(
                new FieldProblem("number", "REQUIRED"),
                new FieldProblem("areaM2", "AREA_INVALID"),
                new FieldProblem("cadastralNumber", "CADASTRAL_INVALID"));
    }
}
