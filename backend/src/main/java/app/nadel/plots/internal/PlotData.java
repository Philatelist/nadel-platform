package app.nadel.plots.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import nadel.contract.model.PlotInput;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The editable fields of a plot after validation and normalisation: trimmed text (blank optional text becomes null),
 * the area with exactly two decimal places, the cadastral number in its checked form. The area is never rounded: a
 * value with more than two decimal places is refused.
 */
record PlotData(
        String number,
        @Nullable String street,
        PlotKind kind,
        BigDecimal areaM2,
        @Nullable String cadastralNumber,
        boolean hasBuilding,
        @Nullable String notes) {

    static final int NUMBER_MAX = 20;
    static final int STREET_MAX = 100;
    static final int NOTES_MAX = 2000;
    /** {@code plot.area_m2} is {@code numeric(10,2)}: eight integer digits and two decimals. */
    private static final int AREA_INTEGER_DIGITS = 8;
    private static final int AREA_SCALE = 2;

    /**
     * Validates what Bean Validation cannot (blank number, area rules, cadastral format) and normalises the rest.
     *
     * @throws ApiProblemException 400 {@code VALIDATION_FAILED} listing every invalid field
     */
    static PlotData from(PlotInput input) {
        List<FieldProblem> errors = new ArrayList<>();

        String number = blankToNull(input.getNumber());
        if (number == null) {
            errors.add(new FieldProblem("number", ProblemCodes.REQUIRED));
        } else if (tooLong(number, NUMBER_MAX)) {
            errors.add(new FieldProblem("number", ProblemCodes.TOO_LONG));
        }

        String street = blankToNull(input.getStreet());
        if (street != null && tooLong(street, STREET_MAX)) {
            errors.add(new FieldProblem("street", ProblemCodes.TOO_LONG));
        }

        PlotKind kind = null;
        if (input.getKind() == null) {
            errors.add(new FieldProblem("kind", ProblemCodes.REQUIRED));
        } else {
            kind = PlotKind.valueOf(input.getKind().name());
        }

        BigDecimal area = null;
        if (input.getAreaM2() == null) {
            errors.add(new FieldProblem("areaM2", ProblemCodes.REQUIRED));
        } else if (areaIsValid(input.getAreaM2(), kind)) {
            area = input.getAreaM2().setScale(AREA_SCALE); // only adds zeros: validated to need no rounding
        } else {
            errors.add(new FieldProblem("areaM2", ProblemCodes.AREA_INVALID));
        }

        String cadastral = blankToNull(input.getCadastralNumber());
        if (cadastral != null) {
            var parsed = CadastralNumber.parse(cadastral);
            if (parsed.isPresent()) {
                cadastral = parsed.get().value();
            } else {
                errors.add(new FieldProblem("cadastralNumber", ProblemCodes.CADASTRAL_INVALID));
            }
        }

        String notes = blankToNull(input.getNotes());
        if (notes != null && tooLong(notes, NOTES_MAX)) {
            errors.add(new FieldProblem("notes", ProblemCodes.TOO_LONG));
        }

        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        return new PlotData(number, street, kind, area, cadastral, Boolean.TRUE.equals(input.getHasBuilding()), notes);
    }

    /**
     * At most two decimal places (trailing zeros do not count), at most eight integer digits, never negative, and
     * greater than zero for a plot. {@code kind} is null only when it is missing, which is reported separately; the
     * strictest rule (a plot) is then applied.
     */
    private static boolean areaIsValid(BigDecimal area, @Nullable PlotKind kind) {
        BigDecimal stripped = area.stripTrailingZeros();
        if (stripped.scale() > AREA_SCALE || stripped.precision() - stripped.scale() > AREA_INTEGER_DIGITS) {
            return false;
        }
        return kind == PlotKind.COMMON ? area.signum() >= 0 : area.signum() > 0;
    }

    private static boolean tooLong(String text, int max) {
        return text.codePointCount(0, text.length()) > max;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
