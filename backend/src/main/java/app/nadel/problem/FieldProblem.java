package app.nadel.problem;

import org.jspecify.annotations.Nullable;

/**
 * One invalid field of a request.
 *
 * @param field   name of the field, for example {@code lastName} or {@code phones[1]}
 * @param code    stable machine-readable code, for example {@code REQUIRED}
 * @param message optional English text; never contains personal data
 */
public record FieldProblem(String field, String code, @Nullable String message) {

    public FieldProblem(String field, String code) {
        this(field, code, null);
    }
}
