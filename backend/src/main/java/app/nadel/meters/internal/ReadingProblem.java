package app.nadel.meters.internal;

import app.nadel.meters.internal.ReadingValidator.Violation;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A refused reading. A single request answers it as an ordinary problem ({@code code} and {@code errors[{field}]});
 * a row of a batch answers it as a row error that also carries the {@link #params()}.
 */
class ReadingProblem extends ApiProblemException {

    private final @Nullable String field;
    private final Map<String, String> params;

    private ReadingProblem(HttpStatus status, String code, String detail, @Nullable String field,
            Map<String, String> params) {
        super(status, code, detail, field == null ? List.of() : List.of(new FieldProblem(field, code)));
        this.field = field;
        this.params = Map.copyOf(params);
    }

    /** 409 for a broken rule of {@link ReadingValidator}. */
    static ReadingProblem of(Violation violation) {
        return new ReadingProblem(HttpStatus.CONFLICT, violation.code(), detail(violation.code()), violation.field(),
                violation.params());
    }

    /** 409 {@code READING_IN_USE}: a charge uses the readings around the date. */
    static ReadingProblem inUse() {
        return new ReadingProblem(HttpStatus.CONFLICT, ProblemCodes.READING_IN_USE,
                "A charge uses the readings around this date; a change goes through recalculation.", null, Map.of());
    }

    /** 409 {@code READING_STATUS_INVALID}: only a counted reading is corrected or deleted. */
    static ReadingProblem statusInvalid() {
        return new ReadingProblem(HttpStatus.CONFLICT, ProblemCodes.READING_STATUS_INVALID,
                "Only a counted reading can be corrected or deleted.", null, Map.of());
    }

    /** The field of the request the problem is about; null when it is about the reading as a whole. */
    @Nullable String field() {
        return field;
    }

    /** The parameters of the message: ISO dates and meter values with three decimals. */
    Map<String, String> params() {
        return params;
    }

    private static String detail(String code) {
        return switch (code) {
            case ProblemCodes.READING_DATE_IN_FUTURE -> "A reading date cannot be in the future.";
            case ProblemCodes.READING_DATE_OUTSIDE_METER -> "The date is outside the working period of the meter.";
            case ProblemCodes.READING_OUT_OF_ORDER ->
                "The reading is lower than the previous one or higher than the next one.";
            default -> "The reading was refused.";
        };
    }
}
