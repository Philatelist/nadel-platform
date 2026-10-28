package app.nadel.problem.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.problem.ProblemWriter;
import app.nadel.problem.ValidationProblems;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import nadel.contract.model.Problem;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an {@code application/problem+json} body of the contract's {@code Problem} shape.
 * Spring MVC's own exceptions go through {@link #handleExceptionInternal}, so they carry a {@code code} too.
 * <p>
 * Texts are fixed English sentences: nothing from the exception message, the request or the database reaches the
 * client, because those can contain personal data or internals.
 */
@RestControllerAdvice
class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<Object> handleApiProblem(ApiProblemException ex) {
        if (ex.status().is5xxServerError()) {
            // A server-side failure with a code of its own (PERSONAL_DATA_UNREADABLE): the code only, never values
            log.error("Answered {} with problem code {}", ex.status().value(), ex.code());
        }
        return problem(ex.status(), ex.code(), ex.getMessage(), ex.errors(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                "You do not have permission to perform this action.", List.of(), null);
    }

    /**
     * Constraints on controller parameters (generated {@code @Min}, {@code @Max}, {@code @Size}) are enforced by method
     * validation on the {@code @Validated} API interfaces, which throws this exception instead of Spring MVC's own.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldProblem> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(parameterName(v), "INVALID"))
                .distinct()
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "The request is not valid.", errors,
                null);
    }

    /**
     * An upload over the configured multipart limit ({@code spring.servlet.multipart.max-file-size} and
     * {@code max-request-size}): 413 with a code the client can switch on. Spring MVC would answer 413 as well, but
     * with the generic {@code REQUEST_REJECTED} code.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ProblemCodes.FILE_TOO_LARGE,
                "The uploaded file is too large.", List.of(), headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        // Only class names: messages of persistence exceptions can contain row values (personal data).
        Throwable root = rootCause(ex);
        log.error("Unhandled exception {} (root cause {})", ex.getClass().getName(), root.getClass().getName());
        log.debug("Unhandled exception details", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.INTERNAL_ERROR,
                "The server could not process the request.", List.of(), null);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ValidationProblems::fieldProblem)
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "The request is not valid.", errors,
                headers);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getParameterValidationResults().stream()
                .map(r -> new FieldProblem(
                        r.getMethodParameter().getParameterName() == null
                                ? "request" : r.getMethodParameter().getParameterName(),
                        "INVALID"))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "The request is not valid.", errors,
                headers);
    }

    /** Every other Spring MVC exception (bad JSON, wrong method, unknown path, ...). */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return problem(statusCode, codeFor(statusCode), detailFor(statusCode), List.of(), headers);
    }

    private static ResponseEntity<Object> problem(HttpStatusCode status, String code, String detail,
            List<FieldProblem> errors, @Nullable HttpHeaders headers) {
        Problem body = ProblemWriter.body(status, code, detail, errors);
        HttpHeaders out = new HttpHeaders();
        if (headers != null) {
            out.putAll(headers);
        }
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(body, out, status);
    }

    /** The last element of the property path, for example {@code size} of {@code listMembers.size}. */
    private static String parameterName(ConstraintViolation<?> violation) {
        String name = "request";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemCodes.MALFORMED_REQUEST;
            case 401 -> ProblemCodes.UNAUTHENTICATED;
            case 403 -> ProblemCodes.PERMISSION_DENIED;
            case 404 -> ProblemCodes.NOT_FOUND;
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? ProblemCodes.INTERNAL_ERROR : "REQUEST_REJECTED";
        };
    }

    private static String detailFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "The request is malformed.";
            case 404 -> "The resource was not found.";
            case 405 -> "The HTTP method is not supported for this resource.";
            case 406 -> "The requested representation is not available.";
            case 415 -> "The content type of the request is not supported.";
            default -> status.is5xxServerError() ? "The server could not process the request."
                    : "The request was rejected.";
        };
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
