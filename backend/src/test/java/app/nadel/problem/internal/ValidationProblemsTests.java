package app.nadel.problem.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.FieldProblem;
import app.nadel.problem.ValidationProblems;
import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;

/** The mapping from Bean Validation constraints to the field codes of the API. */
class ValidationProblemsTests {

    private static FieldProblem map(String field, String constraint, Object rejected) {
        return ValidationProblems.fieldProblem(new FieldError("input", field, rejected, false,
                new String[] {constraint}, null, null));
    }

    @Test
    void missingValuesAreRequired() {
        for (String constraint : new String[] {"NotNull", "NotBlank", "NotEmpty"}) {
            assertThat(map("lastName", constraint, null)).isEqualTo(new FieldProblem("lastName", "REQUIRED"));
        }
    }

    @Test
    void aTooLongValueIsTooLongButAnEmptyOneIsRequired() {
        for (String constraint : new String[] {"Size", "Max", "Length"}) {
            assertThat(map("notes", constraint, "x".repeat(10)).code()).isEqualTo("TOO_LONG");
            assertThat(map("notes", constraint, "").code()).isEqualTo("REQUIRED");
            assertThat(map("notes", constraint, 7).code()).isEqualTo("TOO_LONG");
        }
    }

    @Test
    void aPatternOnAShareIsAnInvalidShareAndAnyOtherPatternIsInvalid() {
        assertThat(map("owners[0].share", "Pattern", "x").code()).isEqualTo("SHARE_INVALID");
        assertThat(map("email", "Pattern", "x").code()).isEqualTo("INVALID");
        assertThat(map("email", "Email", "x").code()).isEqualTo("INVALID");
    }

    @Test
    void aFieldErrorWithoutACodeIsInvalid() {
        FieldError error = new FieldError("input", "email", "x");

        assertThat(ValidationProblems.fieldProblem(error)).isEqualTo(new FieldProblem("email", "INVALID"));
    }
}
