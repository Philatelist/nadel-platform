package app.nadel.problem;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A business or validation failure that is answered as {@code application/problem+json}.
 * <p>
 * {@code code} is the stable machine-readable value clients switch on. {@code detail} is English text for developers
 * and must not contain personal data.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldProblem> errors;

    public ApiProblemException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiProblemException(HttpStatus status, String code, String detail, List<FieldProblem> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
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
}
