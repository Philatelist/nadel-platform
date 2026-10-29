package app.nadel.problem;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import nadel.contract.model.Problem;
import nadel.contract.model.ProblemFieldError;
import nadel.contract.model.RequisiteField;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds Problem Details bodies and writes them straight to a servlet response. Security handlers and filters run
 * outside Spring MVC, so they cannot throw {@link ApiProblemException}; they call {@link #write} instead.
 */
@Component
public class ProblemWriter {

    private final JsonMapper mapper;

    ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The response body for a status and a code. {@code detail} must not contain personal data. */
    public static Problem body(HttpStatusCode status, String code, String detail, List<FieldProblem> errors) {
        return body(status, code, detail, errors, List.of());
    }

    /**
     * The response body with the missing requisites of {@code REQUISITES_MISSING} ({@code missing}, left out when
     * empty).
     */
    public static Problem body(HttpStatusCode status, String code, String detail, List<FieldProblem> errors,
            List<String> missing) {
        return body(status, code, detail, errors, missing, null);
    }

    /**
     * The response body with the missing requisites of {@code REQUISITES_MISSING} ({@code missing}, left out when
     * empty) and the limit of {@code INVOICE_BUILD_TOO_LARGE} ({@code limit}, left out when null).
     */
    public static Problem body(HttpStatusCode status, String code, String detail, List<FieldProblem> errors,
            List<String> missing, @Nullable Integer limit) {
        var problem = new Problem();
        problem.setType("about:blank");
        problem.setTitle(title(status));
        problem.setStatus(status.value());
        problem.setCode(code);
        problem.setDetail(detail);
        if (!errors.isEmpty()) {
            problem.setErrors(errors.stream()
                    .map(e -> new ProblemFieldError(e.field(), e.code()).message(e.message()))
                    .toList());
        }
        if (!missing.isEmpty()) {
            problem.setMissing(missing.stream().map(RequisiteField::fromValue).toList());
        }
        if (limit != null) {
            problem.setLimit(limit);
        }
        return problem;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        Problem body = body(status, code, detail, List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(mapper.writeValueAsBytes(body));
    }

    private static String title(HttpStatusCode status) {
        return status instanceof HttpStatus known ? known.getReasonPhrase() : "Error";
    }
}
