package app.nadel.problem;

import org.jspecify.annotations.Nullable;
import org.springframework.validation.FieldError;

/**
 * Maps a Bean Validation violation to the field code of the API contract. The exception handler uses it for request
 * bodies; other modules use it to validate a value with the same constraints as the API (for example the import of
 * members checks an e-mail address exactly as the member form does).
 */
public final class ValidationProblems {

    private ValidationProblems() {
    }

    /**
     * {@code NotNull}, {@code NotBlank} and {@code NotEmpty} are {@code REQUIRED}; {@code Size}, {@code Max} and
     * {@code Length} are {@code TOO_LONG}, or {@code REQUIRED} when the rejected value is an empty string;
     * a {@code Pattern} on a share is {@code SHARE_INVALID}, on a tariff rate {@code RATE_INVALID}, on a meter reading
     * {@code METER_VALUE_INVALID}; everything else is {@code INVALID}.
     */
    public static FieldProblem fieldProblem(FieldError error) {
        String constraint = error.getCode() == null ? "" : error.getCode();
        String code = switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> ProblemCodes.REQUIRED;
            case "Size", "Max", "Length" -> isEmptyText(error.getRejectedValue())
                    ? ProblemCodes.REQUIRED : ProblemCodes.TOO_LONG;
            // a share that does not look like n/d is the same mistake as one outside the bounds: SHARE_INVALID
            case "Pattern" -> error.getField().endsWith("share") ? ProblemCodes.SHARE_INVALID
                    : isRate(error.getField()) ? ProblemCodes.RATE_INVALID
                    : isMeterValue(error.getField()) ? ProblemCodes.METER_VALUE_INVALID : ProblemCodes.INVALID;
            default -> ProblemCodes.INVALID;
        };
        return new FieldProblem(error.getField(), code);
    }

    /** Whether the field is a tariff rate: {@code rate}, or {@code firstVersion.rate} of a new tariff. */
    private static boolean isRate(String field) {
        return field.equals("rate") || field.endsWith(".rate");
    }

    /**
     * Whether the field is a meter reading: {@code initialReading} or {@code finalReading} of a meter, or the
     * {@code value} of a reading (also {@code rows[0].value} of a batch).
     */
    private static boolean isMeterValue(String field) {
        return field.endsWith("Reading") || field.equals("value") || field.endsWith(".value");
    }

    /** An empty string that violates a minimum length is a missing value, not a short one. */
    private static boolean isEmptyText(@Nullable Object value) {
        return value instanceof CharSequence text && text.isEmpty();
    }
}
