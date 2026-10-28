package app.nadel.problem;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A business or validation failure that is answered as {@code application/problem+json}.
 * <p>
 * {@code code} is the stable machine-readable value clients switch on. {@code detail} is English text for developers
 * and must not contain personal data. {@code missing} carries the codes of the missing requisites of
 * {@code REQUISITES_MISSING} ({@code IBAN}, {@code EDRPOU}); it is empty on every other problem.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldProblem> errors;
    private final List<String> missing;

    public ApiProblemException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiProblemException(HttpStatus status, String code, String detail, List<FieldProblem> errors) {
        this(status, code, detail, errors, List.of());
    }

    private ApiProblemException(HttpStatus status, String code, String detail, List<FieldProblem> errors,
            List<String> missing) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
        this.missing = List.copyOf(missing);
    }

    /**
     * 409 {@code REQUISITES_MISSING}: a document needs requisites that are not set; {@code missing} names them with
     * the codes of the contract's {@code RequisiteField} ({@code IBAN}, {@code EDRPOU}), at least one.
     */
    public static ApiProblemException requisitesMissing(List<String> missing) {
        if (missing.isEmpty()) {
            throw new IllegalArgumentException("At least one requisite is missing");
        }
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.REQUISITES_MISSING,
                "The cooperative's requisites needed for the document are not set.", List.of(), missing);
    }

    /** 404 {@code NOT_FOUND}: the resource does not exist or is not visible to the caller. */
    public static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND, "The resource was not found.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldProblem> errors() {
        return errors;
    }

    /** The missing requisites of {@code REQUISITES_MISSING}; empty on every other problem. */
    public List<String> missing() {
        return missing;
    }
}
