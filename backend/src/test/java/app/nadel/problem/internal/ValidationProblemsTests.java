package app.nadel.problem.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.FieldProblem;
import app.nadel.problem.ValidationProblems;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    /** A body with the password constraint of the contract's {@code InvitationAcceptInput}. */
    record Secret(@Size(min = 8, max = 128) String password, @Size(min = 2) List<String> items) {
    }

    /** A field error as Spring builds it for a request body: wrapping the Bean Validation violation. */
    private static FieldProblem mapViolation(Secret secret, String field) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ConstraintViolation<Secret> violation = factory.getValidator().validate(secret).stream()
                    .filter(v -> v.getPropertyPath().toString().equals(field)).findFirst().orElseThrow();
            FieldError error = new FieldError("input", field, violation.getInvalidValue(), false,
                    new String[] {"Size"}, null, null);
            error.wrap(violation);
            return ValidationProblems.fieldProblem(error);
        }
    }

    @Test
    void aValueUnderTheMinimumIsTooShortAndOverTheMaximumTooLong() {
        assertThat(mapViolation(new Secret("short", null), "password"))
                .isEqualTo(new FieldProblem("password", "TOO_SHORT"));
        assertThat(mapViolation(new Secret("x".repeat(129), null), "password"))
                .isEqualTo(new FieldProblem("password", "TOO_LONG"));
        assertThat(mapViolation(new Secret("", null), "password"))
                .isEqualTo(new FieldProblem("password", "REQUIRED"));
        assertThat(mapViolation(new Secret("long-enough", List.of("one")), "items"))
                .isEqualTo(new FieldProblem("items", "TOO_SHORT"));
    }

    @Test
    void anEmptyListIsRequiredAndAListOverItsLimitIsTooLong() {
        assertThat(map("rows", "Size", List.of()).code()).isEqualTo("REQUIRED");
        assertThat(map("rows", "Size", List.of(1, 2)).code()).isEqualTo("TOO_LONG");
    }

    @Test
    void aPatternOnAShareIsAnInvalidShareAndAnyOtherPatternIsInvalid() {
        assertThat(map("owners[0].share", "Pattern", "x").code()).isEqualTo("SHARE_INVALID");
        assertThat(map("email", "Pattern", "x").code()).isEqualTo("INVALID");
        assertThat(map("email", "Email", "x").code()).isEqualTo("INVALID");
    }

    @Test
    void aPatternOnATariffRateIsAnInvalidRate() {
        assertThat(map("rate", "Pattern", "1.23456").code()).isEqualTo("RATE_INVALID");
        assertThat(map("firstVersion.rate", "Pattern", "-1").code()).isEqualTo("RATE_INVALID");
        assertThat(map("rate", "NotNull", null).code()).isEqualTo("REQUIRED");
        assertThat(map("corporate", "Pattern", "x").code()).isEqualTo("INVALID");
    }

    @Test
    void aPatternOnAMeterReadingIsAnInvalidMeterValue() {
        assertThat(map("initialReading", "Pattern", "1.2345").code()).isEqualTo("METER_VALUE_INVALID");
        assertThat(map("finalReading", "Pattern", "-1").code()).isEqualTo("METER_VALUE_INVALID");
        assertThat(map("value", "Pattern", "1e3").code()).isEqualTo("METER_VALUE_INVALID");
        assertThat(map("rows[0].value", "Pattern", "x").code()).isEqualTo("METER_VALUE_INVALID");
        assertThat(map("initialReading", "NotNull", null).code()).isEqualTo("REQUIRED");
        assertThat(map("values", "Pattern", "x").code()).isEqualTo("INVALID");
    }

    @Test
    void aFieldErrorWithoutACodeIsInvalid() {
        FieldError error = new FieldError("input", "email", "x");

        assertThat(ValidationProblems.fieldProblem(error)).isEqualTo(new FieldProblem("email", "INVALID"));
    }
}
