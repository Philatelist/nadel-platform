package app.nadel.problem;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A business or validation failure that is answered as {@code application/problem+json}.
 * <p>
 * {@code code} is the stable machine-readable value clients switch on. {@code detail} is English text for developers
 * and must not contain personal data. {@code missing} carries the codes of the missing requisites of
 * {@code REQUISITES_MISSING} ({@code IBAN}, {@code EDRPOU}); it is empty on every other problem. {@code limit} carries
 * the limit of {@code INVOICE_BUILD_TOO_LARGE}; it is null on every other problem.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldProblem> errors;
    private final List<String> missing;
    private final @Nullable Integer limit;

    public ApiProblemException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiProblemException(HttpStatus status, String code, String detail, List<FieldProblem> errors) {
        this(status, code, detail, errors, List.of(), null);
    }

    private ApiProblemException(HttpStatus status, String code, String detail, List<FieldProblem> errors,
            List<String> missing, @Nullable Integer limit) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
        this.missing = List.copyOf(missing);
        this.limit = limit;
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
                "The cooperative's requisites needed for the document are not set.", List.of(), missing, null);
    }

    /**
     * 422 {@code INVOICE_BUILD_TOO_LARGE}: a month has more invoiceable plots than one build takes; {@code limit} is
     * the most plots (feature 016 §2.5).
     */
    public static ApiProblemException invoiceBuildTooLarge(int limit) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.INVOICE_BUILD_TOO_LARGE,
                "The month has more invoiceable plots than one build takes.", List.of(), List.of(), limit);
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

    /** The limit of {@code INVOICE_BUILD_TOO_LARGE}; null on every other problem. */
    public @Nullable Integer limit() {
        return limit;
    }
}
